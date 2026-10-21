package io.github.pingsocc.luaujni;

/** An error raised in Luau (a call's, or bytecode that doesn't load): its message is Luau's. */
public final class LuauError extends RuntimeException {
    public LuauError(String message) { super(message); }
}
