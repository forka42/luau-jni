package io.github.pingsocc.luaujni;

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
        return "/io/github/pingsocc/luaujni/natives/" + platform() + "/" + System.mapLibraryName(NAME);
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
}
