package io.github.pingsocc.luaujni;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.pingsocc.luaujni.LuauJni.*;
import static org.junit.jupiter.api.Assertions.*;

class LuauJniTest {
    private long L;
    private final List<Callbacks> keep = new ArrayList<>();
    private final List<Long> refs = new ArrayList<>();

    @BeforeAll static void load() { LuauJni.load(); }

    @BeforeEach void open() {
        L = LuauJni.open();
        assertNotEquals(0, L);
        openLibs(L);
    }

    @AfterEach void close() {
        LuauJni.close(L);
        refs.forEach(LuauJni::deleteGlobalRef);
    }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static byte[] compiled(String source) {
        byte[] code = compile(b(source), 1, 1, null);
        assertNull(compileError(code), () -> compileError(code));
        return code;
    }

    private void run(String source) {
        LuauJni.load(L, b("=test"), compiled(source));
        call(L, 0, 0);
    }

    private void function(String name, Callbacks fn) {
        keep.add(fn);
        long ref = newGlobalRef(fn);
        refs.add(ref);
        pushFunction(L, ref, 0, b(name));
        setGlobal(L, b(name));
    }

    @Test void compilesAndRuns() {
        LuauJni.load(L, b("=test"), compiled("return 6 * 7, 'hi'"));
        call(L, 0, 2);
        assertEquals(TYPE_NUMBER, type(L, 1));
        assertEquals(42, toNumber(L, 1));
        assertEquals("hi", new String(toBytes(L, 2), StandardCharsets.UTF_8));
        assertEquals(2, top(L));
    }

    @Test void compileErrors() {
        String error = compileError(compile(b("local = 1"), 1, 1, null));
        assertNotNull(error);
        assertTrue(error.contains("Expected identifier"), error);
    }

    @Test void runtimeErrorsThrowAndLeaveTheStack() {
        pushNumber(L, 1);
        LuauJni.load(L, b("=test"), compiled("error('boom')"));
        LuauError e = assertThrows(LuauError.class, () -> call(L, 0, 0));
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
        assertEquals(1, top(L));
    }

    @Test void stringsKeepTheirBytes() {
        byte[] binary = {0, (byte) 0xff, (byte) 0xc3, 'a', (byte) 0x80};
        pushBytes(L, binary);
        assertArrayEquals(binary, toBytes(L, -1));
        assertNull(toBytes(L, 99));
    }

    @Test void javaFunctions() {
        function("add", (index, T) -> {
            pushNumber(T, toNumber(T, 1) + toNumber(T, 2));
            return 1;
        });
        run("result = add(2, 3)");
        assertEquals(TYPE_NUMBER, getGlobal(L, b("result")));
        assertEquals(5, toNumber(L, -1));
    }

    @Test void javaExceptionsBecomeLuauErrors() {
        function("fail", (index, T) -> { throw new IllegalStateException("from java"); });
        run("ok, message = pcall(fail)");
        getGlobal(L, b("ok"));
        assertFalse(toBoolean(L, -1));
        getGlobal(L, b("message"));
        assertTrue(new String(toBytes(L, -1), StandardCharsets.UTF_8).contains("from java"));
        LuauJni.load(L, b("=test"), compiled("fail()"));
        assertThrows(LuauError.class, () -> call(L, 0, 0));
    }

    // A metamethod's error from getField is thrown in Java, not let unwind through JNI (which would end the process).
    @Test void metamethodErrorsAreProtected() {
        run("t = setmetatable({}, {__index = function() error('no such field') end, __newindex = function() error('read only') end})");
        getGlobal(L, b("t"));
        int before = top(L);
        LuauError get = assertThrows(LuauError.class, () -> getField(L, -1, b("x")));
        assertTrue(get.getMessage().contains("no such field"), get.getMessage());
        assertEquals(before, top(L));
        pushNumber(L, 1);
        LuauError set = assertThrows(LuauError.class, () -> setField(L, -2, b("x")));
        assertTrue(set.getMessage().contains("read only"), set.getMessage());
        assertEquals(before + 1, top(L));                  // (the value still there: the stack as before the call)
        setTop(L, -2);
        assertEquals(TYPE_NIL, rawGetField(L, -1, b("x")));
    }

    @Test void sandboxedThreads() {
        pushSandbox(L);                                    // [thread, env]
        assertEquals(TYPE_TABLE, type(L, -1));
        pushNumber(L, 7);
        setField(L, -2, b("seven"));
        long thread = toThread(L, -2);
        assertNotEquals(0, thread);
        LuauJni.load(thread, b("=sandboxed"), compiled("answer = seven * 6; return answer"));
        xmove(thread, L, 1);
        call(L, 0, 1);
        assertEquals(42, toNumber(L, -1));
        setTop(L, -2);
        assertEquals(TYPE_NUMBER, getField(L, -1, b("answer")));
        assertEquals(TYPE_NIL, getGlobal(L, b("answer")));   // (the VM's own globals untouched)
    }

    @Test void references() {
        newTable(L);
        int ref = ref(L, -1);
        setTop(L, 0);
        collectGarbage(L);
        assertEquals(TYPE_TABLE, getRef(L, ref));
        unref(L, ref);
    }

    @Test void mutableGlobals() {
        byte[] code = compile(b("return game"), 1, 1, new byte[][]{b("game")});
        assertNull(compileError(code));
    }

    @Test void nativeCode() {
        if (!codegenSupported()) return;
        codegenCreate(L);
        LuauJni.load(L, b("=jit"), compiled("local s = 0 for i = 1, 1000 do s += i end return s"));
        assertEquals(0, codegenCompile(L, -1));
        call(L, 0, 1);
        assertEquals(500500, toNumber(L, -1));
    }

    @Test void deepCallbacks() {
        // Luau → Java → Luau → Java, with an error at the bottom caught by the Luau in between.
        function("again", (index, T) -> {
            LuauJni.load(T, b("=inner"), compiled("return pcall(function() error('deep') end)"));
            call(T, 0, 2);
            return 2;
        });
        run("ok, message = again()");
        getGlobal(L, b("ok"));
        assertFalse(toBoolean(L, -1));
    }
}
