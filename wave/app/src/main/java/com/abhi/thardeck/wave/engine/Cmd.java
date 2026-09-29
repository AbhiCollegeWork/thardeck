package com.abhi.thardeck.wave.engine;

/** A command the engine can emit. The names are the protocol words. */
public enum Cmd {
    VOL_UP, VOL_DOWN, PLAY_PAUSE, NEXT, PREV;

    /** Parses a protocol word, or returns null. */
    public static Cmd parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }
}
