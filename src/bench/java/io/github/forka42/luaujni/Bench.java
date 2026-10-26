package io.github.forka42.luaujni;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What crossing between Luau and Java costs, and what Luau's own code costs: each case timed over many rounds after
 * warming up, the best round's time per iteration reported (./gradlew bench).
 *
 *   luau O1 / O2, interpreted / native   a loop of arithmetic, no calls out (the VM's own speed)
 *   call: empty                         Luau calling a Java function that does nothing
 *   call: property read                 f("12345", "Name") -> a number: two strings in, a lookup, a number out
 *   call: 3 numbers in, 3 out           f(x, y, z) -> x, y, z (a vector's worth)
 *   java -> luau                        Java calling a Luau function with a number, and reading its result
 */
public final class Bench {
    private Bench() {}

    static final int N = 1_000_000, ROUNDS = 15, WARMUP = 5;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] code(String source, int opt) {
        byte[] c = LuauJni.compile(b(source), opt, 1, null);
        String e = LuauJni.compileError(c);
        if (e != null) throw new IllegalStateException(e);
        return c;
    }

    // The function the chunk returns, called with n: the best time of the rounds, per iteration (ns).
    static double timeLuau(long L, int fnRef, int n) {
        double best = Double.MAX_VALUE;
        for (int r = 0; r < WARMUP + ROUNDS; r++) {
            LuauJni.getRef(L, fnRef);
            LuauJni.pushNumber(L, n);
            long t = System.nanoTime();
            LuauJni.call(L, 1, 1);
            long dt = System.nanoTime() - t;
            LuauJni.setTop(L, 0);
            if (r >= WARMUP) best = Math.min(best, (double) dt / n);
        }
        return best;
    }

    // A chunk returning a function, kept by a ref (natively compiled first when native).
    static int function(long L, String source, int opt, boolean nat) {
        LuauJni.load(L, b("=bench"), code(source, opt));
        if (nat) LuauJni.codegenCompile(L, -1);
        LuauJni.call(L, 0, 1);
        int ref = LuauJni.ref(L, -1);
        LuauJni.setTop(L, 0);
        return ref;
    }

    static final String ARITH = """
            return function(n)
                local s = 0
                for i = 1, n do s = s + (i * 0.5) % 7 end
                return s
            end
            """;

    // Classic (the C API, a call at a time): what Mortar's bridge does now.
    static final class Classic implements LuauJni.Callbacks {
        final Map<String, Double> props = new HashMap<>(Map.of("12345/Name", 3.0));
        @Override public int call(int index, long L) {
            switch (index) {
                case 0: return 0;
                case 1: {
                    String id = new String(LuauJni.toBytes(L, 1), StandardCharsets.UTF_8);
                    String k = new String(LuauJni.toBytes(L, 2), StandardCharsets.UTF_8);
                    Double v = props.get(id + "/" + k);
                    LuauJni.pushNumber(L, v == null ? 0 : v);
                    return 1;
                }
                case 2: {
                    double x = LuauJni.toNumber(L, 1), y = LuauJni.toNumber(L, 2), z = LuauJni.toNumber(L, 3);
                    LuauJni.pushNumber(L, x); LuauJni.pushNumber(L, y); LuauJni.pushNumber(L, z);
                    return 3;
                }
                default: return 0;
            }
        }
    }

    // Fast functions (one crossing a call: the frame): the same calls, and the ways a game's API is called (a property
    // through __index, a method through __namecall), on a handle (tag 1, id 42) and registered names.
    static final int A_NAME = 0, A_SPEED = 1, A_GETSPEED = 2;
    static final class Fast implements LuauJni.FastCallbacks {
        LuauJni.Frame frame;
        double speed = 3;
        @Override public int call(int id, long L, int base) {
            LuauJni.Frame f = frame;
            switch (id) {
                case 0: return 0;
                case 1: case 4: {                                           // get(part, "Speed"), part.Speed
                    if (f.handle(base, 0) == 42 && f.atom(base, 1) == A_SPEED) { f.retNumber(base, 0, speed); return 1; }
                    f.retNil(base, 0);
                    return 1;
                }
                case 2: f.retVector(base, 0, f.x(base, 0), f.y(base, 0), f.z(base, 0)); return 1;
                case 3: f.retNumber(base, 0, f.number(base, 0)); f.retNumber(base, 1, f.number(base, 1)); f.retNumber(base, 2, f.number(base, 2)); return 3;
                case 5: {                                                   // part:GetSpeed()
                    if (f.atom(base, 0) == A_GETSPEED) { f.retNumber(base, 0, speed); return 1; }
                    return f.error(L, base, "no such method");
                }
                case 6: f.retString(L, base, 0, f.string(L, base, 0)); return 1;
                default: return 0;
            }
        }
    }

    static void row(String what, double ns) { System.out.printf(Locale.ROOT, "  %-40s %9.1f ns%n", what, ns); }

    public static void main(String[] args) {
        LuauJni.load();
        System.out.println("luau-jni benchmarks (" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + (System.getProperty("org.graalvm.nativeimage.imagecode") != null ? ", native image" : "") + ")");
        long L = LuauJni.open();
        LuauJni.openLibs(L);
        boolean nat = LuauJni.codegenSupported();
        if (nat) LuauJni.codegenCreate(L);

        System.out.println("Luau's own code (per loop iteration):");
        for (int opt : new int[]{1, 2}) {
            row("luau O" + opt + ", interpreted", timeLuau(L, function(L, ARITH, opt, false), N));
            if (nat) row("luau O" + opt + ", native", timeLuau(L, function(L, ARITH, opt, true), N));
        }

        System.out.println("Calls from Luau into Java and back (per call), classic API:");
        var classic = new Classic();
        long cref = LuauJni.newGlobalRef(classic);
        String[] names = {"empty", "get", "vec"};
        for (int i = 0; i < names.length; i++) { LuauJni.pushFunction(L, cref, i, b(names[i])); LuauJni.setGlobal(L, b(names[i])); }
        row("call: empty", timeLuau(L, function(L, "local f = empty return function(n) for i = 1, n do f() end end", 1, false), N));
        row("call: property read", timeLuau(L, function(L, "local f = get return function(n) local s = 0 for i = 1, n do s = s + f(\"12345\", \"Name\") end return s end", 1, false), N));
        row("call: 3 numbers in, 3 out", timeLuau(L, function(L, "local f = vec return function(n) local s = 0 for i = 1, n do local x, y, z = f(i, 2, 3) s = s + x + y + z end return s end", 1, false), N));

        System.out.println("Calls from Luau into Java and back (per call), fast functions:");
        var fast = new Fast();
        long fref = LuauJni.newGlobalRef(fast);
        fast.frame = new LuauJni.Frame(LuauJni.fastInit(L, fref));
        LuauJni.registerAtoms(L, new byte[][]{b("Name"), b("Speed"), b("GetSpeed")});
        String[] fastNames = {"fempty", "fget", "fvec", "fnum3", "", "", "fecho"};
        for (int i = 0; i < fastNames.length; i++) if (!fastNames[i].isEmpty()) { LuauJni.pushFastFunction(L, i, false, b(fastNames[i])); LuauJni.setGlobal(L, b(fastNames[i])); }
        LuauJni.newTable(L);
        LuauJni.pushFastFunction(L, 4, false, b("__index")); LuauJni.setField(L, -2, b("__index"));
        LuauJni.pushFastFunction(L, 5, true, b("__namecall")); LuauJni.setField(L, -2, b("__namecall"));
        LuauJni.setHandleMetatable(L, 1);
        LuauJni.pushHandle(L, 1, 42); LuauJni.setGlobal(L, b("part"));
        row("fast: empty", timeLuau(L, function(L, "local f = fempty return function(n) for i = 1, n do f() end end", 1, false), N));
        row("fast: empty, given (part, \"Speed\")", timeLuau(L, function(L, "local f, p = fempty, part return function(n) for i = 1, n do f(p, \"Speed\") end end", 1, false), N));
        row("fast: empty, given (1, 2)", timeLuau(L, function(L, "local f = fempty return function(n) for i = 1, n do f(1, 2) end end", 1, false), N));
        row("fast: property read f(part, \"Speed\")", timeLuau(L, function(L, "local f, p = fget, part return function(n) local s = 0 for i = 1, n do s = s + f(p, \"Speed\") end return s end", 1, false), N));
        row("fast: part.Speed (__index)", timeLuau(L, function(L, "local p = part return function(n) local s = 0 for i = 1, n do s = s + p.Speed end return s end", 1, false), N));
        row("fast: part:GetSpeed() (__namecall)", timeLuau(L, function(L, "local p = part return function(n) local s = 0 for i = 1, n do s = s + p:GetSpeed() end return s end", 1, false), N));
        row("fast: 3 numbers in, 3 out", timeLuau(L, function(L, "local f = fnum3 return function(n) local s = 0 for i = 1, n do local x, y, z = f(i, 2, 3) s = s + x + y + z end return s end", 1, false), N));
        row("fast: vector in, vector out", timeLuau(L, function(L, "local f = fvec return function(n) local s = 0 for i = 1, n do local v = f(vector.create(i, 2, 3)) s = s + v.x end return s end", 1, false), N));
        row("fast: string in, string out (not a name)", timeLuau(L, function(L, "local f = fecho return function(n) local s = 0 for i = 1, n do s = s + #f(\"hello world\") end return s end", 1, false), N));
        if (nat) row("fast: part.Speed, native", timeLuau(L, function(L, "local p = part return function(n) local s = 0 for i = 1, n do s = s + p.Speed end return s end", 1, true), N));

        System.out.println("Fields: a handle's plain data in native memory, read and written without calling Java:");
        var store = LuauJni.storeInit(L, 1 << 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // A record at 0: its changed mask (0), Speed (number, at 8), Position (vector, at 16), Name (a name, at 28, read-only).
        store.putDouble(8, 3.0); store.putFloat(16, 1); store.putFloat(20, 2); store.putFloat(24, 3); store.putInt(28, A_NAME);
        LuauJni.defineFields(L, 2, new int[]{A_SPEED, 3, A_NAME}, new int[]{8, 16, 28},
                new byte[]{LuauJni.F_NUMBER, LuauJni.F_VECTOR, LuauJni.F_ATOM}, new boolean[]{true, true, false}, 4);
        LuauJni.registerAtoms(L, new byte[][]{b("Position")});              // (atom 3)
        LuauJni.newTable(L);
        LuauJni.pushFieldIndex(L, 2); LuauJni.setField(L, -2, b("__index"));
        LuauJni.pushFieldNewIndex(L, 2); LuauJni.setField(L, -2, b("__newindex"));
        LuauJni.pushFastFunction(L, 5, true, b("__namecall")); LuauJni.setField(L, -2, b("__namecall"));
        LuauJni.setHandleMetatable(L, 2);
        LuauJni.pushHandleRecord(L, 2, 43, 0); LuauJni.setGlobal(L, b("rec"));
        // (What they read is what's there.)
        LuauJni.load(L, b("=check"), code("return rec.Speed, rec.Position.y, rec.Name, rec:GetSpeed()", 1));
        LuauJni.call(L, 0, 4);
        String got = LuauJni.toNumber(L, 1) + " " + LuauJni.toNumber(L, 2) + " " + new String(LuauJni.toBytes(L, 3), StandardCharsets.UTF_8) + " " + LuauJni.toNumber(L, 4);
        LuauJni.setTop(L, 0);
        if (!got.equals("3.0 2.0 Name 3.0")) throw new IllegalStateException("fields read back wrong: " + got);
        row("field: rec.Speed (number)", timeLuau(L, function(L, "local p = rec return function(n) local s = 0 for i = 1, n do s = s + p.Speed end return s end", 1, false), N));
        row("field: rec.Position (vector)", timeLuau(L, function(L, "local p = rec return function(n) local s = 0 for i = 1, n do s = s + p.Position.x end return s end", 1, false), N));
        row("field: rec.Speed = i", timeLuau(L, function(L, "local p = rec return function(n) for i = 1, n do p.Speed = i end end", 1, false), N));
        row("field: rec.Position = vector", timeLuau(L, function(L, "local p = rec return function(n) for i = 1, n do p.Position = vector.create(i, 2, 3) end end", 1, false), N));
        LuauJni.drainChanged(L); store.putLong(0, 0);
        row("field: rec:GetSpeed() (a method: Java)", timeLuau(L, function(L, "local p = rec return function(n) local s = 0 for i = 1, n do s = s + p:GetSpeed() end return s end", 1, false), N));
        if (nat) row("field: rec.Speed, native", timeLuau(L, function(L, "local p = rec return function(n) local s = 0 for i = 1, n do s = s + p.Speed end return s end", 1, true), N));
        LuauJni.load(L, b("=write"), code("rec.Speed = 7 rec.Position = vector.create(4, 5, 6)", 1));
        LuauJni.call(L, 0, 0);
        long[] changed = LuauJni.drainChanged(L);
        if (store.getDouble(8) != 7 || store.getFloat(20) != 5 || changed.length != 3 || store.getLong(0) != 0b11) throw new IllegalStateException("fields written wrong");
        System.out.println("  (read and written correctly; one record told, its mask " + Long.toBinaryString(store.getLong(0)) + ")");

        System.out.println("Calls from Java into Luau (per call), classic API:");
        int add = function(L, "return function(x) return x + 1 end", 1, false);
        double best = Double.MAX_VALUE;
        int n = N / 4;
        for (int r = 0; r < WARMUP + ROUNDS; r++) {
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) {
                LuauJni.getRef(L, add);
                LuauJni.pushNumber(L, i);
                LuauJni.call(L, 1, 1);
                LuauJni.toNumber(L, -1);
                LuauJni.setTop(L, 0);
            }
            if (r >= WARMUP) best = Math.min(best, (double) (System.nanoTime() - t) / n);
        }
        row("java -> luau: f(x) -> x + 1", best);

        LuauJni.close(L);
        LuauJni.deleteGlobalRef(cref);
    }
}
