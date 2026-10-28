package com.termux.app.control;

import java.util.EnumSet;

/** Local UI consent selects scopes; peers cannot request or elevate them. */
public final class LocalControlOptions {
    private LocalControlOptions() { }
    public static EnumSet<ControlCore.Scope> scopes(boolean write, boolean output, boolean close) {
        return scopes(write, output, close, false);
    }
    public static EnumSet<ControlCore.Scope> scopes(boolean write, boolean output, boolean close, boolean resume) {
        EnumSet<ControlCore.Scope> result = EnumSet.of(ControlCore.Scope.SESSIONS_READ);
        if (write) result.add(ControlCore.Scope.SESSIONS_WRITE);
        if (output) result.add(ControlCore.Scope.SESSIONS_OUTPUT);
        if (output && resume) result.add(ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
        if (write && close) result.add(ControlCore.Scope.SESSIONS_CLOSE);
        return result;
    }
}
