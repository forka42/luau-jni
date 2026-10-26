// luau-jni: Luau's C API for Java, through JNI (io.github.forka42.luaujni.LuauJni). Strings cross as byte arrays, so
// any Luau string (not only valid UTF-8) survives the trip. A Java function is a C closure with two upvalues: a JNI
// global reference to its LuauJni.Callbacks (as light userdata) and its index there; it calls Callbacks.call(index, L),
// and a Java exception becomes a Luau error once Java has returned. Calls that can run Luau code (a metamethod) are
// made protected, so a Luau error never unwinds through a JNI frame: it's thrown in Java as a LuauError.
#include <jni.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <random>

#include "lua.h"
#include "lualib.h"
#include "luacode.h"
#include "luacodegen.h"

#include "Luau/CodeGen.h"
#include "Luau/Common.h"
#include "Luau/ExperimentalFlags.h"

#include "ldo.h"
#include "lstate.h"

namespace {

JavaVM* vm;
jmethodID callMethod, messageMethod, toStringMethod;
jclass errorClass;

lua_State* S(jlong L) { return reinterpret_cast<lua_State*>(L); }

jbyteArray bytes(JNIEnv* env, const char* s, size_t n) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(n));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(s));
    return out;
}

// A Java byte[] as a NUL-terminated C string: on the stack when it's short (most are: names, keys), else the heap.
class Str {
public:
    Str(JNIEnv* env, jbyteArray b) {
        n = b ? env->GetArrayLength(b) : 0;
        p = n < static_cast<jsize>(sizeof small) ? small : static_cast<char*>(malloc(static_cast<size_t>(n) + 1));
        if (n > 0) env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte*>(p));
        p[n] = 0;
    }
    ~Str() { if (p != small) free(p); }
    Str(const Str&) = delete;
    Str& operator=(const Str&) = delete;

    const char* c() const { return p; }
    size_t size() const { return static_cast<size_t>(n); }

private:
    char small[256];
    char* p;
    jsize n;
};

// The error on top of L's stack, popped, thrown in Java as a LuauError.
void throwError(JNIEnv* env, lua_State* L) {
    size_t n = 0;
    const char* m = lua_tolstring(L, -1, &n);
    char* copy = static_cast<char*>(malloc(n + 1));
    if (m) memcpy(copy, m, n);
    copy[m ? n : 0] = 0;
    lua_pop(L, 1);
    env->ThrowNew(errorClass, m ? copy : "(error object is not a string)");
    free(copy);
}

// f(L, ud) as lua_pcall would run it: an error leaves the stack as it was, with the error on top, and returns non-zero.
int protect(lua_State* L, Pfunc f, void* ud) { return luaD_pcall(L, f, ud, savestack(L, L->top), 0); }

// lua_Type, as LuauJni's TYPE_ constants (stable whatever Luau's own numbering is).
jint typeOf(int t) {
    switch (t) {
        case LUA_TNONE: return 0;
        case LUA_TNIL: return 1;
        case LUA_TBOOLEAN: return 2;
        case LUA_TLIGHTUSERDATA: return 3;
        case LUA_TNUMBER: return 4;
        case LUA_TINTEGER: return 5;
        case LUA_TVECTOR: return 6;
        case LUA_TSTRING: return 7;
        case LUA_TTABLE: return 8;
        case LUA_TFUNCTION: return 9;
        case LUA_TUSERDATA: return 10;
        case LUA_TTHREAD: return 11;
        case LUA_TBUFFER: return 12;
        default: return 13;
    }
}

int javaFunction(lua_State* L) {
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    auto callbacks = static_cast<jobject>(lua_tolightuserdata(L, lua_upvalueindex(1)));
    int index = static_cast<int>(lua_tonumber(L, lua_upvalueindex(2)));
    jint n = env->CallIntMethod(callbacks, callMethod, index, reinterpret_cast<jlong>(L));
    if (jthrowable t = env->ExceptionOccurred()) {
        env->ExceptionClear();
        // (Its message; its toString when it has none.)
        auto m = static_cast<jstring>(env->CallObjectMethod(t, messageMethod));
        if (env->ExceptionCheck()) { env->ExceptionClear(); m = nullptr; }
        if (!m) {
            m = static_cast<jstring>(env->CallObjectMethod(t, toStringMethod));
            if (env->ExceptionCheck()) { env->ExceptionClear(); m = nullptr; }
        }
        const char* c = m ? env->GetStringUTFChars(m, nullptr) : nullptr;
        lua_pushstring(L, c ? c : "Java error");
        if (c) env->ReleaseStringUTFChars(m, c);
        env->DeleteLocalRef(t);
        if (m) env->DeleteLocalRef(m);
        lua_error(L);   // (Java's frames are all gone by now)
    }
    return n;
}

struct Field {
    int index;
    const char* name;
    int type;
};

void getFieldP(lua_State* L, void* ud) {
    auto* f = static_cast<Field*>(ud);
    f->type = lua_getfield(L, f->index, f->name);
}

void setFieldP(lua_State* L, void* ud) {
    auto* f = static_cast<Field*>(ud);
    lua_setfield(L, f->index, f->name);
}

// Every Luau flag on but the experimental ones, as luau-java (and Luau's own command-line tools) have them.
void defaultFlags() {
    for (Luau::FValue<bool>* flag = Luau::FValue<bool>::list; flag; flag = flag->next)
        if (strncmp(flag->name, "Luau", 4) == 0 && !Luau::isAnalysisFlagExperimental(flag->name))
            flag->value = true;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* jvm, void*) {
    vm = jvm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass callbacks = env->FindClass("io/github/forka42/luaujni/LuauJni$Callbacks");
    if (!callbacks) return JNI_ERR;
    callMethod = env->GetMethodID(callbacks, "call", "(IJ)I");
    jclass throwable = env->FindClass("java/lang/Throwable");
    messageMethod = env->GetMethodID(throwable, "getMessage", "()Ljava/lang/String;");
    toStringMethod = env->GetMethodID(throwable, "toString", "()Ljava/lang/String;");
    jclass error = env->FindClass("io/github/forka42/luaujni/LuauError");
    if (!callMethod || !messageMethod || !toStringMethod || !error) return JNI_ERR;
    errorClass = static_cast<jclass>(env->NewGlobalRef(error));
    defaultFlags();
    return JNI_VERSION_1_6;
}

#define FN(ret, name, ...) JNIEXPORT ret JNICALL Java_io_github_forka42_luaujni_LuauJni_##name(JNIEnv* env, jclass, ##__VA_ARGS__)

// Bytecode, or (Luau's convention) a 0 byte and the error.
FN(jbyteArray, compile, jbyteArray source, jint optimizationLevel, jint debugLevel, jobjectArray mutableGlobals) {
    Str s(env, source);
    jsize globals = mutableGlobals ? env->GetArrayLength(mutableGlobals) : 0;
    auto** names = static_cast<char**>(calloc(static_cast<size_t>(globals) + 1, sizeof(char*)));
    for (jsize i = 0; i < globals; i++) {
        auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(mutableGlobals, i));
        jsize n = env->GetArrayLength(b);
        names[i] = static_cast<char*>(malloc(static_cast<size_t>(n) + 1));
        env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte*>(names[i]));
        names[i][n] = 0;
        env->DeleteLocalRef(b);
    }
    lua_CompileOptions options = {};
    options.optimizationLevel = optimizationLevel;
    options.debugLevel = debugLevel;
    options.mutableGlobals = const_cast<const char* const*>(names);
    size_t size = 0;
    char* code = luau_compile(s.c(), s.size(), &options, &size);
    for (jsize i = 0; i < globals; i++) free(names[i]);
    free(names);
    jbyteArray out = bytes(env, code, size);
    free(code);
    return out;
}

// A new VM, its pointers' encoding keyed at random (tostring(t) then doesn't show a script a real address).
FN(jlong, open) {
    lua_State* L = luaL_newstate();
    if (!L) return 0;
    std::random_device random;
    auto next = [&] { return (static_cast<uint64_t>(random()) << 32) | random(); };
    lua_setpointerencodekey(L, next(), next(), next(), next());
    return reinterpret_cast<jlong>(L);
}
FN(void, close, jlong L) { lua_close(S(L)); }
FN(void, openLibs, jlong L) { luaL_openlibs(S(L)); }
FN(void, collectGarbage, jlong L) { lua_gc(S(L), LUA_GCCOLLECT, 0); }

FN(jboolean, codegenSupported) { return luau_codegen_supported() ? JNI_TRUE : JNI_FALSE; }
FN(void, codegenCreate, jlong L) { luau_codegen_create(S(L)); }
// The Luau function at i (and the ones inside it) compiled to machine code; Luau's CodeGenCompilationResult (0: done).
FN(jint, codegenCompile, jlong L, jint i) {
    if (!lua_isLfunction(S(L), i)) return static_cast<jint>(Luau::CodeGen::CodeGenCompilationResult::Count);
    Luau::CodeGen::CompilationOptions options;
    return static_cast<jint>(Luau::CodeGen::compile(S(L), i, options).result);
}

FN(jint, top, jlong L) { return lua_gettop(S(L)); }
FN(void, setTop, jlong L, jint i) { lua_settop(S(L), i); }
FN(jboolean, checkStack, jlong L, jint n) { return lua_checkstack(S(L), n) ? JNI_TRUE : JNI_FALSE; }
FN(jint, type, jlong L, jint i) { return typeOf(lua_type(S(L), i)); }
FN(jboolean, toBoolean, jlong L, jint i) { return lua_toboolean(S(L), i) ? JNI_TRUE : JNI_FALSE; }
FN(jdouble, toNumber, jlong L, jint i) { return lua_tonumber(S(L), i); }

// The string (or number, as Luau writes it) at i, or null.
FN(jbyteArray, toBytes, jlong L, jint i) {
    int t = lua_type(S(L), i);
    if (t != LUA_TSTRING && t != LUA_TNUMBER) return nullptr;
    size_t n = 0;
    const char* s = lua_tolstring(S(L), i, &n);
    return s ? bytes(env, s, n) : nullptr;
}

FN(void, pushNil, jlong L) { lua_pushnil(S(L)); }
FN(void, pushBoolean, jlong L, jboolean b) { lua_pushboolean(S(L), b); }
FN(void, pushNumber, jlong L, jdouble d) { lua_pushnumber(S(L), d); }
FN(void, pushBytes, jlong L, jbyteArray b) {
    Str s(env, b);
    lua_pushlstring(S(L), s.c(), s.size());
}
// A Java function: callbacks is a global reference (newGlobalRef) to a LuauJni.Callbacks, kept while the VM is.
FN(void, pushFunction, jlong L, jlong callbacks, jint index, jbyteArray name) {
    Str s(env, name);
    lua_pushlightuserdata(S(L), reinterpret_cast<void*>(callbacks));
    lua_pushnumber(S(L), index);
    lua_pushcclosure(S(L), javaFunction, s.c(), 2);
}
FN(void, newTable, jlong L) { lua_newtable(S(L)); }

FN(jint, getField, jlong L, jint i, jbyteArray k) {
    Str s(env, k);
    Field f{i, s.c(), LUA_TNIL};
    if (protect(S(L), getFieldP, &f) != 0) { throwError(env, S(L)); return 0; }
    return typeOf(f.type);
}
FN(void, setField, jlong L, jint i, jbyteArray k) {
    Str s(env, k);
    Field f{i, s.c(), 0};
    if (protect(S(L), setFieldP, &f) != 0) throwError(env, S(L));
}
FN(jint, rawGetField, jlong L, jint i, jbyteArray k) {
    Str s(env, k);
    return typeOf(lua_rawgetfield(S(L), i, s.c()));
}
FN(void, rawSetI, jlong L, jint i, jint n) { lua_rawseti(S(L), i, n); }
FN(jint, getGlobal, jlong L, jbyteArray k) {
    Str s(env, k);
    Field f{LUA_GLOBALSINDEX, s.c(), LUA_TNIL};
    if (protect(S(L), getFieldP, &f) != 0) { throwError(env, S(L)); return 0; }
    return typeOf(f.type);
}
FN(void, setGlobal, jlong L, jbyteArray k) {
    Str s(env, k);
    Field f{LUA_GLOBALSINDEX, s.c(), 0};
    if (protect(S(L), setFieldP, &f) != 0) throwError(env, S(L));
}
FN(jint, ref, jlong L, jint i) { return lua_ref(S(L), i); }
FN(void, unref, jlong L, jint r) { lua_unref(S(L), r); }
FN(jint, getRef, jlong L, jint r) { return typeOf(lua_getref(S(L), r)); }

FN(void, pushValue, jlong L, jint i) { lua_pushvalue(S(L), i); }
FN(void, remove, jlong L, jint i) { lua_remove(S(L), i); }
FN(void, insert, jlong L, jint i) { lua_insert(S(L), i); }

FN(void, load, jlong L, jbyteArray chunk, jbyteArray code) {
    Str name(env, chunk);
    Str data(env, code);
    if (luau_load(S(L), name.c(), data.c(), data.size(), 0) != 0) throwError(env, S(L));
}
// A thread with a global table of its own (Luau's sandboxed thread: its safe env), and that table: [thread, env].
FN(void, pushSandbox, jlong L) {
    lua_State* t = lua_newthread(S(L));
    luaL_sandboxthread(t);
    lua_pushvalue(t, LUA_GLOBALSINDEX);
    lua_xmove(t, S(L), 1);
}
// The thread at i (a lua_State*, for LuauJni's other calls), or 0.
FN(jlong, toThread, jlong L, jint i) { return reinterpret_cast<jlong>(lua_tothread(S(L), i)); }
// Moves n values from the top of from's stack to to's.
FN(void, xmove, jlong from, jlong to, jint n) { lua_xmove(S(from), S(to), n); }
FN(void, call, jlong L, jint nargs, jint nresults) {
    if (lua_pcall(S(L), nargs, nresults, 0) != LUA_OK) throwError(env, S(L));
}

FN(jlong, newGlobalRef, jobject o) { return reinterpret_cast<jlong>(env->NewGlobalRef(o)); }
FN(void, deleteGlobalRef, jlong ref) { env->DeleteGlobalRef(reinterpret_cast<jobject>(ref)); }

}  // extern "C"
