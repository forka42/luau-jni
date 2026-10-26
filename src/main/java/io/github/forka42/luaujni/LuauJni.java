package io.github.forka42.luaujni;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Luau's C API, through JNI: its compiler, its VM, and its native code generator. A VM (or a thread of one) is a
 * {@code long}, its {@code lua_State*}; strings are byte arrays, as Luau's are (any bytes, not only UTF-8). Stack
 * indices are Lua's (1 the first, -1 the top). Errors raised in Luau are thrown as {@link LuauError}, the stack left as
 * it was before the call.
 *
 * <p>The native library is loaded with {@link #load()} (or {@link #load(Path)}, {@link #loadLibrary(Path)}) before
 * anything else here is called. Every Luau flag but the experimental ones is on, as in luau-java and Luau's own tools.
 */
public final class LuauJni {
    private LuauJni() {}

    /** What {@link #type} returns: Luau's lua_Type, numbered for this API (and so the same whatever Luau's own are). */
    public static final int TYPE_NONE = 0, TYPE_NIL = 1, TYPE_BOOLEAN = 2, TYPE_LIGHTUSERDATA = 3, TYPE_NUMBER = 4,
            TYPE_INTEGER = 5, TYPE_VECTOR = 6, TYPE_STRING = 7, TYPE_TABLE = 8, TYPE_FUNCTION = 9, TYPE_USERDATA = 10,
            TYPE_THREAD = 11, TYPE_BUFFER = 12, TYPE_OTHER = 13;

    /** Luau's pseudo-indices: the registry, and the running function's environment (its globals). */
    public static final int REGISTRY_INDEX = -8000 - 2000, GLOBALS_INDEX = -8000 - 2002;

    /**
     * The Java side of the functions {@link #pushFunction} makes: Luau calling the function at index calls this with
     * the thread calling it. It reads the arguments off L's stack, pushes its results, and returns how many. An
     * exception thrown here is raised in Luau as an error (its message), once Java has returned.
     */
    @FunctionalInterface
    public interface Callbacks {
        int call(int index, long L);
    }

    // ------------------------------------------------------------------------------------------------- the library

    private static final String NAME = "luaujni";
    private static volatile boolean loaded;

    /** This machine's platform, as the natives jars name it: windows-x64, linux-x64, linux-arm64, macos-arm64, ... */
    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String a = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String arch = a.equals("amd64") || a.equals("x86_64") ? "x64" : a.equals("aarch64") || a.equals("arm64") ? "arm64" : a;
        return (os.contains("windows") ? "windows" : os.contains("mac") ? "macos" : "linux") + "-" + arch;
    }

    /** Where this platform's library is in its natives jar. */
    public static String resource() {
        return "/io/github/forka42/luaujni/natives/" + platform() + "/" + System.mapLibraryName(NAME);
    }

    public static boolean isLoaded() { return loaded; }

    /**
     * Loads the native library: the file the system property {@code luaujni.library.path} names, if it's set; else the
     * natives jar's, copied out into the temporary folder ({@link #load(Path)}).
     */
    public static synchronized void load() {
        if (loaded) return;
        String path = System.getProperty("luaujni.library.path");
        if (path != null) loadLibrary(Path.of(path));
        else load(Path.of(System.getProperty("java.io.tmpdir"), "luau-jni"));
    }

    /**
     * Loads the natives jar's library, copied out into a folder of dir's named for its contents (so each version has its
     * own, written once, and one already there is used as it is).
     */
    public static synchronized void load(Path dir) {
        if (loaded) return;
        byte[] bytes;
        try (InputStream in = LuauJni.class.getResourceAsStream(resource())) {
            if (in == null) throw new UnsatisfiedLinkError("No luau-jni library for " + platform() + " (" + resource() + "): is its natives jar there?");
            bytes = in.readAllBytes();
        } catch (IOException e) { throw new UncheckedIOException(e); }
        Path lib = dir.resolve(NAME + "-" + hash(bytes)).resolve(System.mapLibraryName(NAME));
        try {
            if (!same(lib, bytes)) {
                Files.createDirectories(lib.getParent());
                Path tmp = Files.createTempFile(lib.getParent(), NAME, ".part");
                try {
                    Files.write(tmp, bytes);
                    Files.move(tmp, lib, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(tmp); }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        loadLibrary(lib);
    }

    /** Loads the library from file (one already copied out, or built). */
    public static synchronized void loadLibrary(Path file) {
        if (loaded) return;
        System.load(file.toAbsolutePath().toString());
        loaded = true;
    }

    private static boolean same(Path p, byte[] bytes) throws IOException {
        return Files.isRegularFile(p) && Files.size(p) == bytes.length && java.util.Arrays.equals(Files.readAllBytes(p), bytes);
    }

    private static String hash(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) s.append(String.format("%02x", d[i]));
            return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------------------------------------- the compiler

    /**
     * Source to bytecode, or, when it doesn't compile, a 0 byte and then the error (as UTF-8): see {@link #compileError}.
     * mutableGlobals: globals the compiler mustn't take as constant (Luau's mutableGlobals), or null.
     */
    public static native byte[] compile(byte[] source, int optimizationLevel, int debugLevel, byte[][] mutableGlobals);

    /** compile's error, or null when it compiled. */
    public static String compileError(byte[] compiled) {
        return compiled.length > 0 && compiled[0] == 0
                ? new String(compiled, 1, compiled.length - 1, java.nio.charset.StandardCharsets.UTF_8) : null;
    }

    // ------------------------------------------------------------------------------------------------- the VM

    /** A new VM (luaL_newstate; 0 if there's no memory for one). */
    public static native long open();
    public static native void close(long L);
    public static native void openLibs(long L);
    /**
     * Makes the VM's globals and libraries read-only (luaL_sandbox). Code loaded after runs in a safe environment, where
     * Luau's built-in calls (math, vector, buffer, string...) take their fast paths: set the globals first.
     */
    public static native void sandbox(long L);
    /** A full garbage collection now. */
    public static native void collectGarbage(long L);

    /** Whether the native code generator can run here. */
    public static native boolean codegenSupported();
    /** The native code generator for this VM (codegenSupported first). */
    public static native void codegenCreate(long L);
    /** The Luau function at index, and the ones in it, compiled to machine code: 0 when done, else why not (Luau's CodeGenCompilationResult). */
    public static native int codegenCompile(long L, int index);

    public static native int top(long L);
    public static native void setTop(long L, int index);
    /** Room for n more values on the stack; false if there can't be. */
    public static native boolean checkStack(long L, int n);
    public static native int type(long L, int index);
    public static native boolean toBoolean(long L, int index);
    public static native double toNumber(long L, int index);
    /** The string at index (or the number, as Luau writes it), or null. */
    public static native byte[] toBytes(long L, int index);

    public static native void pushNil(long L);
    public static native void pushBoolean(long L, boolean b);
    public static native void pushNumber(long L, double n);
    public static native void pushBytes(long L, byte[] s);
    /**
     * A Java function: calling it calls callbacks' call(index, L). callbacks is {@link #newGlobalRef} of a
     * {@link Callbacks}, which must stay (not deleteGlobalRef'd) for as long as the VM can call the function.
     */
    public static native void pushFunction(long L, long callbacks, int index, byte[] name);
    public static native void newTable(long L);

    /** t[name] (t at index) pushed, and its type; metamethods run, protected. */
    public static native int getField(long L, int index, byte[] name);
    /** t[name] = the value on top (popped; left there when it throws); metamethods run, protected. */
    public static native void setField(long L, int index, byte[] name);
    /** t[name] pushed, and its type, without metamethods. */
    public static native int rawGetField(long L, int index, byte[] name);
    /** t[i] = the value on top (popped), without metamethods. */
    public static native void rawSetI(long L, int index, int i);
    /** t[i] pushed (t at index), and its type, without metamethods. */
    public static native int rawGetI(long L, int index, int i);
    /** getField and setField on the running function's environment. */
    public static native int getGlobal(long L, byte[] name);
    public static native void setGlobal(long L, byte[] name);

    /** A reference to the value at index, which keeps it alive (lua_ref). */
    public static native int ref(long L, int index);
    public static native void unref(long L, int ref);
    /** The referenced value pushed, and its type. */
    public static native int getRef(long L, int ref);

    public static native void pushValue(long L, int index);
    public static native void remove(long L, int index);
    public static native void insert(long L, int index);

    /** Bytecode loaded as a function on the stack (in L's environment). */
    public static native void load(long L, byte[] chunkName, byte[] bytecode);
    /**
     * A new thread with an environment of its own, and that environment: [thread, env] (luaL_sandboxthread: Luau's safe
     * env, as Roblox runs scripts in, where globals resolve as code loads and pairs/next keep the VM's fast path).
     */
    public static native void pushSandbox(long L);
    /** The thread at index, as a lua_State* for the calls here, or 0. */
    public static native long toThread(long L, int index);
    /** Moves n values from the top of from's stack to to's (threads of one VM). */
    public static native void xmove(long from, long to, int n);
    /** Calls the function under nargs arguments, protected (lua_pcall); nresults -1 for all of them. */
    public static native void call(long L, int nargs, int nresults);

    /** A JNI global reference to o (for pushFunction), until deleteGlobalRef. */
    public static native long newGlobalRef(Object o);
    public static native void deleteGlobalRef(long ref);

    // ------------------------------------------------------------------------------------------------- fast functions

    /**
     * The Java side of fast functions ({@link #pushFastFunction}): a call crosses once. Its arguments are in the VM's
     * {@link Frame} at base (read them there, not off the stack, though they're on it too); its results go in the frame
     * too, and the return value says how many: n &gt;= 0 results; -1, an error (its message the first result:
     * {@link Frame#error}); -2 - n, a yield with n results ({@link Frame#yield}). One per VM ({@link #fastInit}). An
     * exception thrown here is raised in Luau as an error too, but more slowly than returning one.
     */
    @FunctionalInterface
    public interface FastCallbacks {
        int call(int id, long L, int base);
    }

    /**
     * Fast functions for this VM: callbacks is {@link #newGlobalRef} of its {@link FastCallbacks}, kept while the VM is.
     * Returns the VM's frames' memory, for a {@link Frame}; null if it has them already.
     */
    public static native java.nio.ByteBuffer fastInit(long L, long callbacks);
    /**
     * Names that cross fast functions' frames as numbers ({@link Frame#atom}, {@link Frame#retAtom}): names[i] is atom i
     * (from 0; continuing from the last call's). Register them before code runs: a string's atom is worked out once.
     */
    public static native void registerAtoms(long L, byte[][] names);
    /**
     * A fast function: calling it calls the VM's FastCallbacks with id. namecall: it's a {@code __namecall} metamethod,
     * and the method's name comes first in its frame (a string, its atom if it has one), before self and the arguments.
     */
    public static native void pushFastFunction(long L, int id, boolean namecall, byte[] name);
    /** The table on top (popped) as the metatable for handles with this tag (1 to 127, set once a tag). */
    public static native void setHandleMetatable(long L, int tag);
    /** The handle for (tag, id): the same userdata for as long as Luau holds it, a new one after. */
    public static native void pushHandle(long L, int tag, long id);
    /** The id of the handle at index if it's one with this tag (0: any), else -1. */
    public static native long toHandle(long L, int index, int tag);
    /** Handles Luau has collected since this was last asked (it holds them no more): tag, id, tag, id... */
    public static native long[] drainCollected(long L);
    public static native void pushVector(long L, float x, float y, float z);
    /**
     * The Luau buffer at index as a direct ByteBuffer over its own memory, not a copy (bulk data: a thousand transforms
     * in one call), or null when it isn't a buffer. It's valid only while the buffer is: use it during the call it's an
     * argument of, and keep no reference after.
     */
    public static native java.nio.ByteBuffer bufferView(long L, int index);
    /** A new Luau buffer of size bytes (zeroed) pushed, and a view of it (valid while the buffer is, as bufferView's). */
    public static native java.nio.ByteBuffer newBuffer(long L, int size);

    // ------------------------------------------------------------------------------------------------- fields

    /**
     * A field's type ({@link #defineFields}), as it's kept in a record: a number (8 bytes, a double); a vector (12: three
     * floats); a boolean (4: an int, 0 or 1); a name (4: an atom, -1 for nil); a handle (16: its tag, then its record (or
     * -1), then its id; tag 0 for nil); a float (4: a number kept as a float, as a GPU reads it).
     */
    public static final byte F_NUMBER = 1, F_VECTOR = 2, F_BOOLEAN = 3, F_ATOM = 4, F_HANDLE = 5, F_FLOAT = 6;

    /**
     * The handles' records: native memory, zeroed, size bytes, which Java writes their fields in (little-endian) and Luau
     * reads them from without calling Java ({@link #pushFieldIndex}). A record's first 8 bytes are its changed mask: bit
     * i set when Luau wrote field i (defineFields' order); fields go after them. After {@link #fastInit}; null if it has one.
     */
    public static native java.nio.ByteBuffer storeInit(long L, long size);
    /**
     * Handles with this tag have fields: atoms[i] is at offsets[i] in each one's record, of types[i] (F_), and Luau may
     * write it if writable[i]. Anything else that's read or written on them calls the fast function fallback (-1: none,
     * an error), as {@code __index(self, key)} or {@code __newindex(self, key, value)}.
     */
    public static native void defineFields(long L, int tag, int[] atoms, int[] offsets, byte[] types, boolean[] writable, int fallback);
    /** The tag's {@code __index} and {@code __newindex} (for its metatable), which serve its fields from records. */
    public static native void pushFieldIndex(long L, int tag);
    public static native void pushFieldNewIndex(long L, int tag);
    /** A handle whose fields are in the record at offset record in the store. */
    public static native void pushHandleRecord(long L, int tag, long id, int record);
    /**
     * The records whose fields Luau wrote since this was last asked: tag, id, record (its offset), ... each once, however
     * often written; which fields, its changed mask says. Clear the mask (write 0) once they're seen to.
     */
    public static native long[] drainChanged(long L);

    /**
     * A VM's fast functions' frames, read and written in place (luau_jni.cpp's layout: keep the two in sync). A call's
     * frame is at base (its FastCallbacks' argument): its arguments' slots (from 0: a __namecall's method name first),
     * then its results' slots, which the call fills from 0, then string bytes. Methods take base so that nothing need be
     * made per call; one Frame for the VM serves every call, nested ones too.
     */
    public static final class Frame {
        public static final int FRAME_SIZE = 8192, HEADER = 16, SLOT = 24, MAX_SLOTS = 64;
        public static final int RESULTS = HEADER + MAX_SLOTS * SLOT, STRINGS = RESULTS + MAX_SLOTS * SLOT;
        /**
         * A slot's type: none (past the last argument), nil, boolean, number, vector, string, handle, or on the stack
         * (anything else: a table, a function... read it at {@link #stackIndex} with the C API's calls).
         */
        public static final int T_NONE = 0, T_NIL = 1, T_BOOLEAN = 2, T_NUMBER = 3, T_VECTOR = 4, T_STRING = 5, T_HANDLE = 6, T_STACK = 7;

        private final java.nio.ByteBuffer b;
        private final byte[] scratch = new byte[FRAME_SIZE];

        public Frame(java.nio.ByteBuffer frames) { this.b = frames.order(java.nio.ByteOrder.LITTLE_ENDIAN); }

        private static int arg(int base, int i) { return base + HEADER + i * SLOT; }
        private static int res(int base, int k) { return base + RESULTS + k * SLOT; }

        /** How many argument slots the call has (at most MAX_SLOTS: the rest are on the stack). */
        public int count(int base) { return b.getInt(base); }
        public int type(int base, int i) { return i >= 0 && i < count(base) ? b.get(arg(base, i)) : T_NONE; }
        public boolean isNil(int base, int i) { int t = type(base, i); return t == T_NIL || t == T_NONE; }
        public double number(int base, int i) { return type(base, i) == T_NUMBER ? b.getDouble(arg(base, i) + 8) : 0; }
        /** Luau's truth: anything but nil and false. */
        public boolean truthy(int base, int i) {
            int t = type(base, i);
            return t == T_BOOLEAN ? b.getInt(arg(base, i) + 4) != 0 : t != T_NIL && t != T_NONE;
        }
        public float x(int base, int i) { return b.getFloat(arg(base, i) + 8); }
        public float y(int base, int i) { return b.getFloat(arg(base, i) + 12); }
        public float z(int base, int i) { return b.getFloat(arg(base, i) + 16); }
        /** A string's atom ({@link #registerAtoms}), or -1 (not a string, or not a registered name). */
        public int atom(int base, int i) { return type(base, i) == T_STRING ? b.getInt(arg(base, i) + 4) : -1; }
        /** A string's bytes' length, or -1 when it's not a string in the frame. */
        public int stringLength(int base, int i) { return type(base, i) == T_STRING ? b.getInt(arg(base, i) + 12) : -1; }
        /** A string (as UTF-8), or null when it isn't one (a long one is read off the stack). */
        public String string(long L, int base, int i) {
            int t = type(base, i);
            if (t == T_STACK) { byte[] s = toBytes(L, stackIndex(base, i)); return s == null ? null : new String(s, java.nio.charset.StandardCharsets.UTF_8); }
            if (t != T_STRING) return null;
            int at = base + b.getInt(arg(base, i) + 8), n = b.getInt(arg(base, i) + 12);
            b.get(at, scratch, 0, n);
            return new String(scratch, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        }
        public int handleTag(int base, int i) { return type(base, i) == T_HANDLE ? b.getInt(arg(base, i) + 4) : 0; }
        /** A handle's id, or -1 when it isn't one. */
        public long handle(int base, int i) { return type(base, i) == T_HANDLE ? b.getLong(arg(base, i) + 8) : -1; }
        /** Where a T_STACK argument is on the stack. */
        public int stackIndex(int base, int i) { return b.getInt(arg(base, i) + 4); }

        // ---- results: slot k (from 0); the call returns how many it filled

        public void retNil(int base, int k) { b.put(res(base, k), (byte) T_NIL); }
        public void retBoolean(int base, int k, boolean v) { int s = res(base, k); b.put(s, (byte) T_BOOLEAN); b.putInt(s + 4, v ? 1 : 0); }
        public void retNumber(int base, int k, double v) { int s = res(base, k); b.put(s, (byte) T_NUMBER); b.putDouble(s + 8, v); }
        public void retVector(int base, int k, float x, float y, float z) {
            int s = res(base, k); b.put(s, (byte) T_VECTOR); b.putFloat(s + 8, x); b.putFloat(s + 12, y); b.putFloat(s + 16, z);
        }
        /** A registered name (pushed as the VM's own string for it: nothing to copy). */
        public void retAtom(int base, int k, int atom) { int s = res(base, k); b.put(s, (byte) T_STRING); b.putInt(s + 4, atom); }
        public void retHandle(int base, int k, int tag, long id) { int s = res(base, k); b.put(s, (byte) T_HANDLE); b.putInt(s + 4, tag); b.putLong(s + 8, id); }
        /** The value at a stack index (one pushed with the C API's calls, say). */
        public void retStack(int base, int k, int index) { int s = res(base, k); b.put(s, (byte) T_STACK); b.putInt(s + 4, index); }
        /** A string: its bytes into the frame, or pushed (and left on the stack) when there's no room. */
        public void retString(long L, int base, int k, byte[] v) {
            int top = b.getInt(base + 8);
            if (top + v.length > FRAME_SIZE) { pushBytes(L, v); retStack(base, k, top(L)); return; }
            b.put(base + top, v);
            b.putInt(base + 8, top + v.length);
            int s = res(base, k); b.put(s, (byte) T_STRING); b.putInt(s + 4, -1); b.putInt(s + 8, top); b.putInt(s + 12, v.length);
        }
        public void retString(long L, int base, int k, String v) {
            // (Plain ASCII straight into the frame; anything else encoded first.)
            int n = v.length(), top = b.getInt(base + 8);
            boolean ascii = top + n <= FRAME_SIZE;
            for (int j = 0; ascii && j < n; j++) if (v.charAt(j) >= 0x80) ascii = false;
            if (!ascii) { retString(L, base, k, v.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return; }
            for (int j = 0; j < n; j++) b.put(base + top + j, (byte) v.charAt(j));
            b.putInt(base + 8, top + n);
            int s = res(base, k); b.put(s, (byte) T_STRING); b.putInt(s + 4, -1); b.putInt(s + 8, top); b.putInt(s + 12, n);
        }
        /** An error with this message: return what this returns. */
        public int error(long L, int base, String message) { retString(L, base, 0, message); return -1; }
        /** A yield with n results (filled first): return what this returns. */
        public static int yield(int n) { return -2 - n; }
    }
}
