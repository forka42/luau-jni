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
#include <string>
#include <unordered_map>
#include <vector>

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

// ---------------------------------------------------------------------------------------------- fast functions
//
// A fast function's call crosses once: its arguments are written into the VM's frame (native memory Java reads as a
// direct ByteBuffer), Java is called once (FastCallbacks.call(id, L, base)), and its results are read back from the
// frame. No call back into native code per argument or result, and no Java string made for a name: strings that are
// atoms (names registered with registerAtoms) cross as their number. Handles (tagged userdata holding a 64-bit id: an
// object of Java's) cross as tag and id, and the same id always comes back as the same userdata (a weak cache), so
// Luau sees one object. LuauJni.Frame (Java) reads and writes the same layout; keep the two in sync.

constexpr int FRAME_SIZE = 8192, FRAMES = 32;              // a frame per nested call: 32 deep at most
constexpr int HEADER = 16, SLOT = 24, MAX_SLOTS = 64;      // header: nargs, nresults, string top, (spare)
constexpr int RESULTS = HEADER + MAX_SLOTS * SLOT;         // results' slots, after the arguments'
constexpr int STRINGS = RESULTS + MAX_SLOTS * SLOT;        // strings' bytes, to the frame's end

// A slot's type (LuauJni.Frame's T_ constants).
enum : int8_t { T_NONE = 0, T_NIL, T_BOOLEAN, T_NUMBER, T_VECTOR, T_STRING, T_HANDLE, T_STACK };

// A handle's userdata: Java's id for the object, its tag (kept for the destructor, which isn't told it), and its record
// in the VM's store (fields: see Fields), if it has one (-1: none).
// retired: Java has retired its id (retireHandle) while Luau still held this; its id is reusable once it's collected.
struct Handle { int64_t id; int32_t tag; int32_t record; int32_t retired; };

// ---- fields: a handle's plain data, in native memory both sides read, served without calling Java
//
// A tag's fields (defineFields) are names (atoms) at offsets in its handles' records, in the VM's store (a block of
// native memory Java writes through a ByteBuffer: storeInit). Reading one (__index) is answered here, from the record;
// writing a writable one (__newindex) is done here, its bit set in the record's changed mask (a record's first 8
// bytes are the bridge's: which of its fields Luau changed), and the record listed for Java (drainChanged) the first time
// one of its fields is changed since Java last asked, so the list is a record long however often it's written. Anything else (a method, a name that's no field, a read-only field written) calls the
// tag's Java fast function, as before.
enum : int8_t { F_NUMBER = 1, F_VECTOR, F_BOOLEAN, F_ATOM, F_HANDLE, F_FLOAT };
struct Field2 { int32_t offset = -1; int8_t type = 0; bool writable = false; int8_t bit = 0; };
struct Fields {
    std::vector<Field2> byAtom;                             // atom -> field (offset -1: not one)
    int fallback = -1;                                      // the Java fast function for everything else
};

jmethodID fastCallMethod;

struct Fast {
    jobject callbacks = nullptr;                            // the VM's FastCallbacks (a global reference)
    char* frame = nullptr;
    int depth = 0;
    int caches[128];                                        // by tag: a weak-valued table, id -> handle (made when first needed)
    std::unordered_map<std::string, int16_t> atoms;         // name -> atom
    std::vector<int> atomRefs;                              // atom -> a ref to its string
    std::vector<int64_t> collected;                         // handles collected since Java last asked: tag, id, tag, id...
    std::vector<int64_t> reusable;                          // retired ids whose handle has been collected since: tag, id...
    char* store = nullptr;                                  // handles' records (storeInit)
    int64_t storeSize = 0;
    Fields fields[128];                                     // by tag
    std::vector<int64_t> changed;                           // records changed since Java last asked: tag, id, record...
};

Fast* fastOf(lua_State* L) { return static_cast<Fast*>(lua_callbacks(L)->userdata); }

int16_t userAtom(lua_State* L, const char* s, size_t n) {
    Fast* f = fastOf(L);
    if (!f || f->atoms.empty()) return -1;
    auto it = f->atoms.find(std::string(s, n));
    return it == f->atoms.end() ? -1 : it->second;
}

// A handle collected (Luau holds it no more): its tag and id kept till Java asks (drainCollected).
void destructor(lua_State* L, void* p) {
    Fast* f = fastOf(L);
    if (!f) return;
    auto* h = static_cast<Handle*>(p);
    f->collected.push_back(h->tag);
    f->collected.push_back(h->id);
    if (h->retired) { f->reusable.push_back(h->tag); f->reusable.push_back(h->id); }
}

inline void put32(char* p, int32_t v) { memcpy(p, &v, 4); }
inline void put64(char* p, int64_t v) { memcpy(p, &v, 8); }
inline void putD(char* p, double v) { memcpy(p, &v, 8); }
inline void putF(char* p, float v) { memcpy(p, &v, 4); }
inline int32_t get32(const char* p) { int32_t v; memcpy(&v, p, 4); return v; }
inline int64_t get64(const char* p) { int64_t v; memcpy(&v, p, 8); return v; }
inline double getD(const char* p) { double v; memcpy(&v, p, 8); return v; }
inline float getF(const char* p) { float v; memcpy(&v, p, 4); return v; }

// A string in a slot: its atom (or -1), and its bytes in the frame's string area (offset, length from the frame's base);
// one that doesn't fit is T_STACK (Java reads it off the stack).
void slotString(char* base, char* s, int index, const char* str, size_t n, int atom, int32_t& top) {
    if (top + static_cast<int64_t>(n) > FRAME_SIZE) { s[0] = T_STACK; put32(s + 4, index); return; }
    s[0] = T_STRING;
    put32(s + 4, atom);
    put32(s + 8, top);
    put32(s + 12, static_cast<int32_t>(n));
    memcpy(base + top, str, n);
    top += static_cast<int32_t>(n);
}

void writeArg(lua_State* L, int i, char* base, char* s, int32_t& top) {
    switch (lua_type(L, i)) {
        case LUA_TNIL: s[0] = T_NIL; break;
        case LUA_TBOOLEAN: s[0] = T_BOOLEAN; put32(s + 4, lua_toboolean(L, i)); break;
        case LUA_TNUMBER: s[0] = T_NUMBER; putD(s + 8, lua_tonumber(L, i)); break;
        case LUA_TVECTOR: {
            const float* v = lua_tovector(L, i);
            s[0] = T_VECTOR; putF(s + 8, v[0]); putF(s + 12, v[1]); putF(s + 16, v[2]);
            break;
        }
        case LUA_TSTRING: {
            int atom = -1;
            size_t n = 0;
            const char* str = lua_tostringatom(L, i, &atom);
            lua_tolstring(L, i, &n);
            slotString(base, s, i, str, n, atom, top);
            break;
        }
        case LUA_TUSERDATA: {
            int tag = lua_userdatatag(L, i);
            if (tag > 0 && lua_getuserdatadtor(L, tag) == destructor) {
                auto* h = static_cast<Handle*>(lua_touserdatatagged(L, i, tag));
                s[0] = T_HANDLE; put32(s + 4, tag); put64(s + 8, h->id);
                break;
            }
            s[0] = T_STACK; put32(s + 4, i);
            break;
        }
        default: s[0] = T_STACK; put32(s + 4, i); break;   // (tables, functions, threads, buffers: on the stack)
    }
}

// The userdata for a handle: the one Luau has already, if it has one (so it's the same object), else a new one.
// A tag's cache pushed: its handles by id, values weak (one userdata an id while Luau holds it). Small ids are integer
// keys (a Luau table's array part, when they're dense: an id reused costs nothing); others are number keys.
void pushCache(lua_State* L, Fast* f, int tag) {
    int& ref = f->caches[tag & 127];
    if (ref == LUA_NOREF) {
        lua_newtable(L);
        lua_newtable(L);
        lua_pushstring(L, "v");
        lua_setfield(L, -2, "__mode");
        lua_setmetatable(L, -2);
        ref = lua_ref(L, -1);
        return;
    }
    lua_getref(L, ref);
}

inline bool smallId(int64_t id) { return id >= 1 && id <= INT32_MAX; }

// cache[id] pushed (the cache at -1).
void cacheGet(lua_State* L, int64_t id) {
    if (smallId(id)) { lua_rawgeti(L, -1, static_cast<int>(id)); return; }
    lua_pushnumber(L, static_cast<double>(id));
    lua_rawget(L, -2);
}

void pushHandle(lua_State* L, Fast* f, int tag, int64_t id, int32_t record = -1) {
    pushCache(L, f, tag);
    cacheGet(L, id);
    if (lua_type(L, -1) == LUA_TUSERDATA) { lua_remove(L, -2); return; }
    lua_pop(L, 1);
    auto* h = static_cast<Handle*>(lua_newuserdatataggedwithmetatable(L, sizeof(Handle), tag));
    h->id = id; h->tag = tag; h->record = record; h->retired = 0;
    if (smallId(id)) {
        lua_pushvalue(L, -1);
        lua_rawseti(L, -3, static_cast<int>(id));
    } else {
        lua_pushnumber(L, static_cast<double>(id));
        lua_pushvalue(L, -2);
        lua_rawset(L, -4);
    }
    lua_remove(L, -2);
}

void pushResult(lua_State* L, Fast* f, char* base, const char* s) {
    switch (s[0]) {
        case T_BOOLEAN: lua_pushboolean(L, get32(s + 4)); break;
        case T_NUMBER: lua_pushnumber(L, getD(s + 8)); break;
        case T_VECTOR: lua_pushvector(L, getF(s + 8), getF(s + 12), getF(s + 16)); break;
        case T_STRING: {
            int atom = get32(s + 4);
            if (atom >= 0 && atom < static_cast<int>(f->atomRefs.size())) lua_getref(L, f->atomRefs[atom]);
            else lua_pushlstring(L, base + get32(s + 8), static_cast<size_t>(get32(s + 12)));
            break;
        }
        case T_HANDLE: pushHandle(L, f, get32(s + 4), get64(s + 8)); break;
        case T_STACK: lua_pushvalue(L, get32(s + 4)); break;
        default: lua_pushnil(L); break;
    }
}

// upvalue 1: the function's id; upvalue 2: whether it's a __namecall (the method's name comes first, as a string).
int callJava(lua_State* L, Fast* f, int id, bool namecall);

int fastFunction(lua_State* L) {
    Fast* f = fastOf(L);
    int id = static_cast<int>(lua_tonumber(L, lua_upvalueindex(1)));
    bool namecall = lua_toboolean(L, lua_upvalueindex(2));
    return callJava(L, f, id, namecall);
}

// The call, through the frame: the stack's values as its arguments (a __namecall's method name first), its results
// pushed (or its error raised, or a yield).
int callJava(lua_State* L, Fast* f, int id, bool namecall) {
    if (f->depth >= FRAMES) luaL_error(L, "Java calls nested too deeply");
    char* base = f->frame + static_cast<size_t>(f->depth) * FRAME_SIZE;
    int n = lua_gettop(L), slot = 0;
    int32_t top = STRINGS;
    if (namecall) {
        int atom = -1;
        const char* m = lua_namecallatom(L, &atom);
        if (!m) luaL_error(L, "not a method call");
        slotString(base, base + HEADER, 0, m, strlen(m), atom, top);
        slot = 1;
    }
    for (int i = 1; i <= n && slot < MAX_SLOTS; i++, slot++) writeArg(L, i, base, base + HEADER + slot * SLOT, top);
    put32(base, slot);
    put32(base + 8, top);
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    f->depth++;
    jint r = env->CallIntMethod(f->callbacks, fastCallMethod, id, reinterpret_cast<jlong>(L), static_cast<jint>(base - f->frame));
    f->depth--;
    if (jthrowable t = env->ExceptionOccurred()) {
        env->ExceptionClear();
        auto m = static_cast<jstring>(env->CallObjectMethod(t, messageMethod));
        if (env->ExceptionCheck()) { env->ExceptionClear(); m = nullptr; }
        if (!m) { m = static_cast<jstring>(env->CallObjectMethod(t, toStringMethod)); if (env->ExceptionCheck()) { env->ExceptionClear(); m = nullptr; } }
        const char* c = m ? env->GetStringUTFChars(m, nullptr) : nullptr;
        lua_pushstring(L, c ? c : "Java error");
        if (c) env->ReleaseStringUTFChars(m, c);
        env->DeleteLocalRef(t);
        if (m) env->DeleteLocalRef(m);
        lua_error(L);
    }
    // r: the results (>= 0); -1, an error (its message the first result); <= -2, a yield with -r - 2 results.
    int count = r >= 0 ? r : r == -1 ? 1 : -r - 2;
    if (count > MAX_SLOTS) count = MAX_SLOTS;
    if (!lua_checkstack(L, count + 2)) luaL_error(L, "stack overflow");
    for (int k = 0; k < count; k++) pushResult(L, f, base, base + RESULTS + k * SLOT);
    if (r == -1) lua_error(L);
    if (r <= -2) return lua_yield(L, count);
    return count;
}

// A handle's field, if key (a string) names one of its tag's: nullptr if not (or it has no record).
const Field2* fieldOf(lua_State* L, Fast* f, int tag, Handle** h, int keyIndex, int* atom) {
    *h = static_cast<Handle*>(lua_touserdatatagged(L, 1, tag));
    *atom = -1;
    if (!*h || (*h)->record < 0 || !lua_tostringatom(L, keyIndex, atom) || *atom < 0) return nullptr;
    const Fields& fs = f->fields[tag];
    if (*atom >= static_cast<int>(fs.byAtom.size())) return nullptr;
    const Field2& fd = fs.byAtom[*atom];
    if (fd.offset < 0 || (*h)->record + fd.offset + 16 > f->storeSize) return nullptr;
    return &fd;
}

// __index(self, key): a field from the record; anything else, the tag's Java function. upvalue 1: the tag.
int fieldIndex(lua_State* L) {
    Fast* f = fastOf(L);
    int tag = static_cast<int>(lua_tonumber(L, lua_upvalueindex(1)));
    Handle* h;
    int atom;
    if (const Field2* fd = fieldOf(L, f, tag, &h, 2, &atom)) {
        const char* r = f->store + h->record + fd->offset;
        switch (fd->type) {
            case F_NUMBER: lua_pushnumber(L, getD(r)); return 1;
            case F_FLOAT: lua_pushnumber(L, getF(r)); return 1;
            case F_VECTOR: lua_pushvector(L, getF(r), getF(r + 4), getF(r + 8)); return 1;
            case F_BOOLEAN: lua_pushboolean(L, get32(r)); return 1;
            case F_ATOM: {
                int a = get32(r);
                if (a >= 0 && a < static_cast<int>(f->atomRefs.size())) lua_getref(L, f->atomRefs[a]); else lua_pushnil(L);
                return 1;
            }
            case F_HANDLE: {                                    // (tag, record, id: 0 tag, nil)
                int t = get32(r);
                if (t > 0 && t < 128) pushHandle(L, f, t, get64(r + 8), get32(r + 4)); else lua_pushnil(L);
                return 1;
            }
            default: break;
        }
    }
    int fallback = f->fields[tag].fallback;
    if (fallback < 0) luaL_error(L, "%s is not a valid member", lua_tostring(L, 2) ? lua_tostring(L, 2) : "?");
    return callJava(L, f, fallback, false);
}

// __newindex(self, key, value): a writable field written in the record (and the handle put on the changed list);
// anything else, the tag's Java function. upvalue 1: the tag.
int fieldNewIndex(lua_State* L) {
    Fast* f = fastOf(L);
    int tag = static_cast<int>(lua_tonumber(L, lua_upvalueindex(1)));
    Handle* h;
    int atom;
    const Field2* fd = fieldOf(L, f, tag, &h, 2, &atom);
    if (fd && fd->writable) {
        char* r = f->store + h->record + fd->offset;
        bool done = true;
        switch (fd->type) {
            case F_NUMBER: if (lua_type(L, 3) == LUA_TNUMBER) putD(r, lua_tonumber(L, 3)); else done = false; break;
            case F_FLOAT: if (lua_type(L, 3) == LUA_TNUMBER) putF(r, static_cast<float>(lua_tonumber(L, 3))); else done = false; break;
            case F_VECTOR: if (const float* v = lua_tovector(L, 3)) { putF(r, v[0]); putF(r + 4, v[1]); putF(r + 8, v[2]); } else done = false; break;
            case F_BOOLEAN: if (lua_type(L, 3) == LUA_TBOOLEAN) put32(r, lua_toboolean(L, 3)); else done = false; break;
            default: done = false; break;
        }
        if (done) {
            char* rec = f->store + h->record;
            uint64_t mask = static_cast<uint64_t>(get64(rec));
            if (mask == 0) { f->changed.push_back(tag); f->changed.push_back(h->id); f->changed.push_back(h->record); }
            put64(rec, static_cast<int64_t>(mask | (uint64_t{1} << fd->bit)));
            return 0;
        }
    }
    int fallback = f->fields[tag].fallback;
    if (fallback < 0) luaL_error(L, "%s cannot be assigned to", lua_tostring(L, 2) ? lua_tostring(L, 2) : "?");
    return callJava(L, f, fallback, false);
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
    jclass fast = env->FindClass("io/github/forka42/luaujni/LuauJni$FastCallbacks");
    if (!fast) return JNI_ERR;
    fastCallMethod = env->GetMethodID(fast, "call", "(IJI)I");
    if (!fastCallMethod) return JNI_ERR;
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
FN(void, close, jlong L) {
    Fast* f = fastOf(S(L));
    lua_close(S(L));                                      // (handles' destructors run: f's still there for them)
    if (f) { free(f->frame); free(f->store); delete f; }
}
FN(void, openLibs, jlong L) { luaL_openlibs(S(L)); }
// The VM's globals and libraries made read-only (luaL_sandbox): code loaded after runs in a safe environment, where
// Luau's built-in calls (math, vector, buffer...) take their fast paths.
FN(void, sandbox, jlong L) { luaL_sandbox(S(L)); }
FN(void, collectGarbage, jlong L) { lua_gc(S(L), LUA_GCCOLLECT, 0); }
// A step of the incremental collector, about kb kilobytes of its work (0: Luau's own step); true if it finished a cycle.
FN(jboolean, gcStep, jlong L, jint kb) { return lua_gc(S(L), LUA_GCSTEP, kb) ? JNI_TRUE : JNI_FALSE; }
// Luau's heap, in kilobytes.
FN(jint, gcCount, jlong L) { return lua_gc(S(L), LUA_GCCOUNT, 0); }

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
FN(jint, rawGetI, jlong L, jint i, jint n) { return typeOf(lua_rawgeti(S(L), i, n)); }
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

// Fast functions for this VM: callbacks a global reference (newGlobalRef) to its FastCallbacks; the frame back, as a
// direct ByteBuffer (FRAMES frames of FRAME_SIZE bytes).
FN(jobject, fastInit, jlong L, jlong callbacks) {
    lua_State* l = S(L);
    if (fastOf(l)) return nullptr;
    auto* f = new Fast();
    f->callbacks = reinterpret_cast<jobject>(callbacks);
    f->frame = static_cast<char*>(calloc(FRAMES, FRAME_SIZE));
    lua_callbacks(l)->userdata = f;
    lua_callbacks(l)->useratom = userAtom;
    for (int& c : f->caches) c = LUA_NOREF;                  // (the handles' caches: made a tag at a time, when first needed)
    return env->NewDirectByteBuffer(f->frame, static_cast<jlong>(FRAMES) * FRAME_SIZE);
}

// Names that cross as their number (atom i: names[i]) in fast functions' frames. Before any code runs: a string's atom
// is worked out once, the first time it's asked for.
FN(void, registerAtoms, jlong L, jobjectArray names) {
    lua_State* l = S(L);
    Fast* f = fastOf(l);
    if (!f) return;
    jsize n = env->GetArrayLength(names);
    for (jsize i = 0; i < n && i < 32767; i++) {
        auto b = static_cast<jbyteArray>(env->GetObjectArrayElement(names, i));
        Str s(env, b);
        env->DeleteLocalRef(b);
        f->atoms[std::string(s.c(), s.size())] = static_cast<int16_t>(f->atomRefs.size());
        lua_pushlstring(l, s.c(), s.size());
        f->atomRefs.push_back(lua_ref(l, -1));
        lua_pop(l, 1);
    }
}

FN(void, pushFastFunction, jlong L, jint id, jboolean namecall, jbyteArray name) {
    Str s(env, name);
    lua_pushnumber(S(L), id);
    lua_pushboolean(S(L), namecall);
    lua_pushcclosure(S(L), fastFunction, s.c(), 2);
}

// The table on top (popped) as the metatable of handles with this tag (1 to 127; once a tag).
FN(void, setHandleMetatable, jlong L, jint tag) {
    lua_setuserdatametatable(S(L), tag);
    lua_setuserdatadtor(S(L), tag, destructor);
}
FN(void, pushHandle, jlong L, jint tag, jlong id) {
    Fast* f = fastOf(S(L));
    if (f) pushHandle(S(L), f, tag, id); else lua_pushnil(S(L));
}
// The id of the handle at index if it's one with this tag (0: any tag), else -1.
FN(jlong, toHandle, jlong L, jint i, jint tag) {
    int t = lua_userdatatag(S(L), i);
    if (t <= 0 || (tag != 0 && t != tag) || lua_getuserdatadtor(S(L), t) != destructor) return -1;
    return static_cast<Handle*>(lua_touserdatatagged(S(L), i, t))->id;
}
// The handles collected since last asked: tag, id, tag, id...
// Java will push (tag, id) no more till it reuses the id. true: no handle for it is left in Luau, the id is reusable now;
// false: Luau still holds one, and the id is listed as reusable (drainReusable) once that's been collected.
FN(jboolean, retireHandle, jlong L, jint tag, jlong id) {
    lua_State* l = S(L);
    Fast* f = fastOf(l);
    if (!f) return JNI_TRUE;
    pushCache(l, f, tag);
    cacheGet(l, id);
    bool held = lua_type(l, -1) == LUA_TUSERDATA;
    if (held) static_cast<Handle*>(lua_touserdata(l, -1))->retired = 1;
    lua_pop(l, 2);
    return held ? JNI_FALSE : JNI_TRUE;
}
// Retired ids whose handle Luau has collected since this was last asked: tag, id, tag, id... (null: none).
FN(jlongArray, drainReusable, jlong L) {
    Fast* f = fastOf(S(L));
    if (!f || f->reusable.empty()) return nullptr;
    jsize n = static_cast<jsize>(f->reusable.size());
    jlongArray out = env->NewLongArray(n);
    env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(f->reusable.data()));
    f->reusable.clear();
    return out;
}
FN(jlongArray, drainCollected, jlong L) {
    Fast* f = fastOf(S(L));
    jsize n = f ? static_cast<jsize>(f->collected.size()) : 0;
    jlongArray out = env->NewLongArray(n);
    if (n > 0) { env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(f->collected.data())); f->collected.clear(); }
    return out;
}
FN(void, pushVector, jlong L, jfloat x, jfloat y, jfloat z) { lua_pushvector(S(L), x, y, z); }

// The Luau buffer at index, as a direct ByteBuffer over its own memory (no copy), or null if it isn't one. Valid while
// the buffer is: during the call it's an argument of, while it's on the stack.
FN(jobject, bufferView, jlong L, jint i) {
    size_t n = 0;
    void* p = lua_tobuffer(S(L), i, &n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}
// A new Luau buffer of size bytes (zeroed) pushed, as a direct ByteBuffer over it (valid while the buffer is).
FN(jobject, newBuffer, jlong L, jint size) {
    void* p = lua_newbuffer(S(L), static_cast<size_t>(size));
    return env->NewDirectByteBuffer(p, size);
}

// The handles' records' memory (bytes long, zeroed), as a direct ByteBuffer; null if there's one already.
FN(jobject, storeInit, jlong L, jlong size) {
    Fast* f = fastOf(S(L));
    if (!f || f->store || size <= 0) return nullptr;
    f->store = static_cast<char*>(calloc(1, static_cast<size_t>(size)));
    if (!f->store) return nullptr;
    f->storeSize = size;
    return env->NewDirectByteBuffer(f->store, size);
}
// A tag's fields: atoms[i] at offsets[i] in a record, of types[i] (Fields' F_ types), writable[i] or not; anything
// else that's asked of (or set on) its handles calls the fast function fallback (-1: none: it's an error).
FN(void, defineFields, jlong L, jint tag, jintArray atoms, jintArray offsets, jbyteArray types, jbooleanArray writable, jint fallback) {
    Fast* f = fastOf(S(L));
    if (!f || tag <= 0 || tag >= 128) return;
    jsize n = env->GetArrayLength(atoms);
    std::vector<jint> a(n), o(n);
    std::vector<jbyte> t(n);
    std::vector<jboolean> w(n);
    env->GetIntArrayRegion(atoms, 0, n, a.data());
    env->GetIntArrayRegion(offsets, 0, n, o.data());
    env->GetByteArrayRegion(types, 0, n, t.data());
    env->GetBooleanArrayRegion(writable, 0, n, w.data());
    Fields& fs = f->fields[tag];
    fs.fallback = fallback;
    for (jsize i = 0; i < n; i++) {
        if (a[i] < 0) continue;
        if (a[i] >= static_cast<jint>(fs.byAtom.size())) fs.byAtom.resize(static_cast<size_t>(a[i]) + 1);
        fs.byAtom[a[i]] = Field2{o[i], t[i], w[i] == JNI_TRUE, static_cast<int8_t>(i & 63)};
    }
}
// __index and __newindex for a tag with fields (for its metatable).
FN(void, pushFieldIndex, jlong L, jint tag) { lua_pushnumber(S(L), tag); lua_pushcclosure(S(L), fieldIndex, "__index", 1); }
FN(void, pushFieldNewIndex, jlong L, jint tag) { lua_pushnumber(S(L), tag); lua_pushcclosure(S(L), fieldNewIndex, "__newindex", 1); }
// A handle with a record (its offset in the store).
FN(void, pushHandleRecord, jlong L, jint tag, jlong id, jint record) {
    Fast* f = fastOf(S(L));
    if (f) pushHandle(S(L), f, tag, id, record); else lua_pushnil(S(L));
}
// The records whose fields Luau changed since last asked: tag, id, record, ... (their changed masks: Java clears them).
FN(jlongArray, drainChanged, jlong L) {
    Fast* f = fastOf(S(L));
    jsize n = f ? static_cast<jsize>(f->changed.size()) : 0;
    jlongArray out = env->NewLongArray(n);
    if (n > 0) { env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(f->changed.data())); f->changed.clear(); }
    return out;
}

FN(jlong, newGlobalRef, jobject o) { return reinterpret_cast<jlong>(env->NewGlobalRef(o)); }
FN(void, deleteGlobalRef, jlong ref) { env->DeleteGlobalRef(reinterpret_cast<jobject>(ref)); }

}  // extern "C"
