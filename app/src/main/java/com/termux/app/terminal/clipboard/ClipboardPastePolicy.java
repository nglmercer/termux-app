package com.termux.app.terminal.clipboard;

/** Selects a paste path using metadata only; never coerces arbitrary clipboard data. */
public final class ClipboardPastePolicy {
    public enum Kind { TEXT, IMAGE, TEXT_URI, WEB_URI, UNSUPPORTED }
    private ClipboardPastePolicy() { }
    public static Kind classify(CharSequence text, boolean image, boolean textMime, String scheme) {
        if (text != null) return Kind.TEXT;
        if (image) return Kind.IMAGE;
        if (!textMime || scheme == null) return Kind.UNSUPPORTED;
        if ("content".equalsIgnoreCase(scheme)) return Kind.TEXT_URI;
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return Kind.WEB_URI;
        return Kind.UNSUPPORTED;
    }
}
