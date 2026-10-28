package com.termux.app.control;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class LocalControlOptionsTest {
    @Test public void closeNeedsBothWriteModeAndExplicitConsent() {
        Set<ControlCore.Scope> read = LocalControlOptions.scopes(false, false, true);
        assertTrue(read.contains(ControlCore.Scope.SESSIONS_READ));
        assertFalse(read.contains(ControlCore.Scope.SESSIONS_WRITE));
        assertFalse(read.contains(ControlCore.Scope.SESSIONS_CLOSE));
        assertFalse(LocalControlOptions.scopes(true, false, false).contains(ControlCore.Scope.SESSIONS_CLOSE));
        Set<ControlCore.Scope> allowed = LocalControlOptions.scopes(true, true, true);
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_CLOSE));
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_OUTPUT));
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_WRITE));
    }
}
