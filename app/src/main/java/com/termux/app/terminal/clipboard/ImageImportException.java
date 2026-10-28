package com.termux.app.terminal.clipboard;

import java.io.IOException;

/** Failure category that can be shown without exposing a provider URI or file contents. */
public final class ImageImportException extends IOException {
    public enum Reason { ACCESS, UNSUPPORTED, SIZE, CACHE }
    private final Reason mReason;

    public ImageImportException(Reason reason) {
        super(reason.name());
        mReason = reason;
    }

    public Reason getReason() {
        return mReason;
    }
}
