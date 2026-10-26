package io.github.forka42.luaujni;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.forka42.luaujni.LuauJni.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fast functions (one crossing a call, through the frame), handles, and fields (a handle's data in native memory). */
class FastTest {
    private long L, ref;
    private Frame frame;
    private FastCallbacks callbacks = (id, l, base) -> 0;
    private final List<String> seen = new ArrayList<>();

    @BeforeAll static void load() { LuauJni.load(); }

    @BeforeEach void open() {
        L = LuauJni.open();
        openLibs(L);
        ref = newGlobalRef((FastCallbacks) (id, l, base) -> callbacks.call(id, l, base));
        frame = new Frame(fastInit(L, ref));
        registerAtoms(L, new byte[][]{b("Name"), b("Speed"), b("Jump"), b("Position")});   // atoms 0, 1, 2, 3
    }

    @AfterEach void close() {
        LuauJni.close(L);
        deleteGlobalRef(ref);
    }

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private void run(String source) {
        byte[] code = compile(b(source), 1, 1, null);
        assertNull(compileError(code), () -> compileError(code));
        LuauJni.load(L, b("=test"), code);
        LuauJni.call(L, 0, -1);
    }

    private void fn(int id, String name) { pushFastFunction(L, id, false, b(name)); setGlobal(L, b(name)); }

    @Test void argumentsAndResultsCrossInTheFrame() {
        callbacks = (id, l, base) -> {
            seen.add(frame.count(base) + ": " + frame.type(base, 0) + " " + frame.number(base, 0) + ", " + frame.truthy(base, 1)
                    + ", " + frame.string(l, base, 2) + " (atom " + frame.atom(base, 2) + "), " + frame.atom(base, 3)
                    + ", " + frame.x(base, 4) + " " + frame.y(base, 4) + " " + frame.z(base, 4) + ", " + frame.type(base, 5) + ", " + frame.isNil(base, 6));
            frame.retNumber(base, 0, 42);
            frame.retString(l, base, 1, "héllo");
            frame.retAtom(base, 2, 1);
            frame.retVector(base, 3, 1, 2, 3);
            frame.retBoolean(base, 4, true);
            frame.retNil(base, 5);
            return 6;
        };
        fn(0, "f");
        run("local a, b, c, d, e, g = f(1.5, false, 'Hello', 'Speed', vector.create(4, 5, 6), {}, nil)"
                + " assert(a == 42 and b == 'héllo' and c == 'Speed' and d == vector.create(1, 2, 3) and e == true and g == nil)");
        assertEquals(List.of("7: " + Frame.T_NUMBER + " 1.5, false, Hello (atom -1), 1, 4.0 5.0 6.0, " + Frame.T_STACK + ", true"), seen);
    }

    @Test void errorsAndYields() {
        callbacks = (id, l, base) -> id == 0 ? frame.error(l, base, "no good") : Frame.yield(0);
        fn(0, "bad");
        fn(1, "pause");
        var e = assertThrows(LuauError.class, () -> run("bad()"));
        assertTrue(e.getMessage().contains("no good"), e.getMessage());
        run("co = coroutine.create(function() pause() done = true end) coroutine.resume(co) assert(not done) coroutine.resume(co) assert(done)");
    }

    // A call into Luau from a fast function, which calls one again: each has a frame of its own.
    @Test void nestedCallsHaveTheirOwnFrames() {
        callbacks = (id, l, base) -> {
            if (id == 0) {
                double x = frame.number(base, 0);
                getGlobal(l, b("inner"));
                pushNumber(l, x * 10);
                LuauJni.call(l, 1, 1);
                double inner = toNumber(l, -1);
                setTop(l, -2);
                frame.retNumber(base, 0, x + inner);
                return 1;
            }
            frame.retNumber(base, 0, frame.number(base, 0) + 1);
            return 1;
        };
        fn(0, "outer");
        fn(1, "plus");
        run("function inner(y) return plus(y) end assert(outer(2) == 2 + 21)");
    }

    // A handle is one object in Luau for as long as it's held (the same id, the same userdata), with its tag's
    // metatable: properties through __index, methods through __namecall (the method's name first, as its atom).
    @Test void handlesAreObjects() {
        callbacks = (id, l, base) -> {
            if (id == 0) { frame.retHandle(base, 0, 1, 7); return 1; }
            if (id == 1) {                                           // __index
                if (frame.handle(base, 0) == 7 && frame.atom(base, 1) == 0) { frame.retString(l, base, 0, "Seven"); return 1; }
                return frame.error(l, base, "no such member");
            }
            seen.add(frame.atom(base, 0) + " " + frame.handle(base, 1) + " " + frame.number(base, 2));   // __namecall
            return 0;
        };
        newTable(L);
        pushFastFunction(L, 1, false, b("__index")); setField(L, -2, b("__index"));
        pushFastFunction(L, 2, true, b("__namecall")); setField(L, -2, b("__namecall"));
        setHandleMetatable(L, 1);
        fn(0, "get");
        run("local a, b = get(), get() assert(rawequal(a, b) and a.Name == 'Seven' and typeof(a) == 'userdata') a:Jump(3)");
        assertEquals(List.of("2 7 3.0"), seen);
        pushHandle(L, 1, 7);
        assertEquals(7, toHandle(L, -1, 1));
        assertEquals(-1, toHandle(L, -1, 2), "another tag's");
        setTop(L, 0);
        // Once Luau holds it no more, Java's told.
        collectGarbage(L);
        collectGarbage(L);
        long[] gone = drainCollected(L);
        assertEquals(2, gone.length, java.util.Arrays.toString(gone));
        assertEquals(1, gone[0]);
        assertEquals(7, gone[1]);
    }

    // Each tag has its own handles: the same id under two tags is two objects, each with its own tag.
    @Test void tagsDontShareHandles() {
        newTable(L); setHandleMetatable(L, 1);
        newTable(L); setHandleMetatable(L, 2);
        pushHandle(L, 1, 7);
        pushHandle(L, 2, 7);
        assertEquals(7, toHandle(L, -2, 1));
        assertEquals(7, toHandle(L, -1, 2));
        assertEquals(-1, toHandle(L, -1, 1), "tag 2's handle isn't tag 1's");
        setTop(L, 0);
    }

    // A retired id with no handle left in Luau is reusable at once; one Luau still holds becomes reusable only once
    // that handle's been collected, and never while a script keeps it.
    @Test void retiredIdsAreReusableOnlyOnceLuauLetsGo() {
        newTable(L); setHandleMetatable(L, 1);
        callbacks = (id, l, base) -> { frame.retHandle(base, 0, 1, (long) frame.number(base, 0)); return 1; };
        fn(0, "get");

        // Never pushed, or pushed and let go before: reusable now.
        assertTrue(retireHandle(L, 1, 3));
        pushHandle(L, 1, 4); setTop(L, 0);
        collectGarbage(L); collectGarbage(L);
        assertTrue(retireHandle(L, 1, 4), "its handle was collected: nothing holds it");
        assertNull(drainReusable(L), "nothing waits to be reusable");

        // Held by a script: not reusable, however often the collector runs.
        run("kept = get(5)");
        assertFalse(retireHandle(L, 1, 5));
        for (int i = 0; i < 3; i++) collectGarbage(L);
        assertNull(drainReusable(L), "a script still has it");
        run("assert(kept ~= nil)");

        // Let go: listed as reusable once collected, and only once.
        run("kept = nil");
        collectGarbage(L); collectGarbage(L);
        assertArrayEquals(new long[]{1, 5}, drainReusable(L));
        assertNull(drainReusable(L), "listed once");

        // A handle collected without being retired isn't listed as reusable (only drainCollected sees it).
        run("local tmp = get(6)");
        setTop(L, 0);
        collectGarbage(L); collectGarbage(L);
        assertNull(drainReusable(L));
    }

    // Ids reused lowest first, as a place churning objects reuses them: the ids stay dense and Luau's heap stays put
    // (ids that only ever grow make the tag's cache grow, and rehash, without end).
    @Test void reusedIdsKeepTheCacheSmall() {
        newTable(L); setHandleMetatable(L, 1);
        var free = new java.util.BitSet();
        int next = 1, live = 2000;
        var held = new java.util.ArrayDeque<Long>();
        for (int i = 0; i < live; i++) { pushHandle(L, 1, next); held.add((long) next++); setTop(L, 0); }
        long heapAtRound5 = -1;
        for (int round = 0; round < 50; round++) {
            for (int k = 0; k < 500; k++) {
                long old = held.poll();
                if (retireHandle(L, 1, old)) free.set((int) old);
                long[] back = drainReusable(L);
                if (back != null) for (int j = 1; j < back.length; j += 2) free.set((int) back[j]);
                int id = free.isEmpty() ? next++ : free.nextSetBit(0);
                free.clear(id);
                pushHandle(L, 1, id);
                setTop(L, 0);
                held.add((long) id);
            }
            collectGarbage(L);
            if (round == 5) heapAtRound5 = heapKb();
        }
        assertTrue(next <= live * 2 + 1, "ids stayed dense: the highest is " + (next - 1));
        assertTrue(heapKb() <= heapAtRound5 + 64, "the heap stayed put: " + heapAtRound5 + " KB, then " + heapKb());
    }

    // Stepping the collector finishes cycles: a retired handle let go becomes reusable without a full collection.
    @Test void gcStepsFinishCycles() {
        newTable(L); setHandleMetatable(L, 1);
        pushHandle(L, 1, 9);
        assertFalse(retireHandle(L, 1, 9));
        setTop(L, 0);
        int before = gcCount(L);
        assertTrue(before > 0);
        boolean finished = false;
        for (int i = 0; i < 10_000 && !finished; i++) finished = gcStep(L, 64);
        assertTrue(finished, "a cycle finished");
        for (int i = 0; i < 10_000 && drainReusablePeek() == null; i++) gcStep(L, 64);
        assertArrayEquals(new long[]{1, 9}, reusable);
    }

    private long[] reusable;
    private long[] drainReusablePeek() { long[] r = drainReusable(L); if (r != null) reusable = r; return reusable; }

    private long heapKb() {
        run("heap = gcinfo()");
        getGlobal(L, b("heap"));
        long kb = (long) toNumber(L, -1);
        setTop(L, 0);
        return kb;
    }

    // Fields: read and written from a record in native memory without calling Java; anything else falls back to Java.
    @Test void fieldsAreReadAndWrittenInTheRecord() {
        callbacks = (id, l, base) -> { seen.add("fallback " + frame.string(l, base, 1)); frame.retNumber(base, 0, -1); return 1; };
        ByteBuffer store = storeInit(L, 4096).order(ByteOrder.LITTLE_ENDIAN);
        int rec = 64;
        store.putDouble(rec + 8, 16);                            // Speed
        store.putFloat(rec + 16, 1); store.putFloat(rec + 20, 2); store.putFloat(rec + 24, 3);   // Position
        store.putInt(rec + 28, 0);                               // Name: "Name" (atom 0)
        defineFields(L, 3, new int[]{1, 3, 0}, new int[]{8, 16, 28}, new byte[]{F_NUMBER, F_VECTOR, F_ATOM}, new boolean[]{true, true, false}, 0);
        newTable(L);
        pushFieldIndex(L, 3); setField(L, -2, b("__index"));
        pushFieldNewIndex(L, 3); setField(L, -2, b("__newindex"));
        setHandleMetatable(L, 3);
        pushHandleRecord(L, 3, 99, rec);
        setGlobal(L, b("p"));
        run("assert(p.Speed == 16 and p.Position == vector.create(1, 2, 3) and p.Name == 'Name') assert(p.Other == -1)");
        assertEquals(List.of("fallback Other"), seen);
        assertArrayEquals(new long[0], drainChanged(L), "nothing written yet");
        run("p.Speed = 20 p.Speed = 24 p.Position = vector.create(7, 8, 9)");
        assertEquals(24, store.getDouble(rec + 8));
        assertEquals(8, store.getFloat(rec + 20));
        assertArrayEquals(new long[]{3, 99, rec}, drainChanged(L), "the record, once");
        assertEquals(0b11, store.getLong(rec), "Speed and Position changed");
        // A read-only field, or one given the wrong type, goes to Java.
        seen.clear();
        run("p.Name = 'x' p.Speed = 'fast'");
        assertEquals(List.of("fallback Name", "fallback Speed"), seen);
        // Java's own writes are what Luau reads.
        store.putDouble(rec + 8, 5);
        run("assert(p.Speed == 5)");
    }
}
