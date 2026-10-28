package com.termux.app.terminal.clipboard;

/** Decision for a pending picker result. Session handles must refer to existing live sessions. */
public final class ImagePickerDelivery {
    public enum Decision { WAIT, REJECT, IMPORT }
    private ImagePickerDelivery() { }
    public static Decision decide(boolean visible, boolean serviceReady, boolean originalExists,
                                  boolean currentAttached, boolean sameSession, boolean running) {
        if (!visible || !serviceReady) return Decision.WAIT;
        if (originalExists && !running) return Decision.REJECT;
        if (originalExists && !currentAttached) return Decision.WAIT;
        return originalExists && sameSession && running ? Decision.IMPORT : Decision.REJECT;
    }
}
