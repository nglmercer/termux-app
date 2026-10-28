package com.termux.app.extrakeys;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ExtraKeyRecoveryTest {
    @Test public void restoresPreviewOnlyForSameBaseline() throws Exception {
        byte[] proof = new byte[32];
        ExtraKeyRecovery result = ExtraKeyRecovery.restore("[[TAB]]", "[[ESC]]", proof, proof.clone(), false);
        assertEquals("[[\"TAB\"]]", result.layout.toJson());
        assertEquals("[[ESC]]", result.original);
        assertFalse(result.saveBlocked);
        byte[] changed = proof.clone(); changed[0] = 1;
        assertTrue(ExtraKeyRecovery.restore("[[TAB]]", "[[ESC]]", proof, changed, false).saveBlocked);
        assertTrue(ExtraKeyRecovery.restore("[[TAB]]", "[[ESC]]", proof, proof, true).saveBlocked);
        assertTrue(ExtraKeyRecovery.restore("[[TAB]]", "[[ESC]]", null, null, false).saveBlocked);
        assertTrue(ExtraKeyRecovery.restore("[[TAB]]", "[[ESC]]", new byte[1], new byte[1], false).saveBlocked);
    }

    @Test public void rejectsInvalidSavedLayouts() throws Exception {
        try { ExtraKeyRecovery.restore("bad", "[[ESC]]", new byte[32], new byte[32], false); fail(); }
        catch (IOException expected) { }
        try { ExtraKeyRecovery.restore("[[TAB]]", null, new byte[32], new byte[32], false); fail(); }
        catch (IOException expected) { }
    }
}
