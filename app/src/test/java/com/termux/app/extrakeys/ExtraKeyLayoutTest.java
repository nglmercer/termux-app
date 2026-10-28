package com.termux.app.extrakeys;

import com.google.gson.JsonObject;
import junit.framework.TestCase;
import java.io.IOException;

public class ExtraKeyLayoutTest extends TestCase {
    public void testPresetsRoundTripWithoutSharingState() throws Exception {
        for (ExtraKeyLayout.Preset preset : ExtraKeyLayout.Preset.values()) {
            ExtraKeyLayout a = ExtraKeyLayout.preset(preset);
            ExtraKeyLayout b = ExtraKeyLayout.parse(a.toJson());
            assertEquals(a.toJson(), b.toJson());
            int count = b.keyCount(0);
            a.removeKey(0, 0);
            assertEquals(count, b.keyCount(0));
        }
    }
    public void testExistingSyntaxAndAdvancedFieldsSurviveMove() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC, {macro:'CTRL f d',display:'exit',popup:{key:'END',custom:{x:3}},future:true}],['TAB']]");
        layout.moveKey(0, 1, 1, 1);
        ExtraKeyLayout roundTrip = ExtraKeyLayout.parse(layout.toJson());
        JsonObject item = roundTrip.key(1, 1).getAsJsonObject();
        assertEquals("CTRL f d", item.get("macro").getAsString());
        assertEquals("END", item.getAsJsonObject("popup").get("key").getAsString());
        assertEquals(3, item.getAsJsonObject("popup").getAsJsonObject("custom").get("x").getAsInt());
        assertTrue(item.get("future").getAsBoolean());
    }
    public void testReturnedKeyCannotMutateLayout() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[{key:'ESC',popup:'TAB'}]]");
        layout.key(0, 0).getAsJsonObject().addProperty("key", "changed");
        assertEquals("ESC", layout.key(0, 0).getAsJsonObject().get("key").getAsString());
    }
    public void testMoveValidationIsAtomic() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC,TAB],[CTRL]]");
        String before = layout.toJson();
        try { layout.moveKey(0, 0, 1, 3); fail(); } catch (IndexOutOfBoundsException expected) { }
        assertEquals(before, layout.toJson());
        layout.moveKey(0, 0, 0, 1);
        assertEquals("TAB", layout.key(0, 0).getAsString());
        assertEquals("ESC", layout.key(0, 1).getAsString());
    }
    public void testRejectsAmbiguousOrUnsupportedInputs() throws Exception {
        reject("[[{key:'ESC',key:'TAB'}]]"); reject("[[{key:'ESC',macro:'CTRL c'}]]");
        reject("[[{key:'ESC',popup:{key:'A',key:'B'}}]]"); reject("[[12]]");
        reject("[[ESC],[TAB],[CTRL],[ALT]]"); reject("[[ESC]] trailing");
    }
    public void testRowAndKeyLimits() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC]]");
        layout.addRow(); layout.addRow();
        try { layout.addRow(); fail(); } catch (IllegalStateException expected) { }
        for (int i = 0; i < ExtraKeyLayout.MAX_KEYS_PER_ROW; i++) layout.addKey(1, "X");
        try { layout.addKey(1, "Y"); fail(); } catch (IllegalStateException expected) { }
        String before = layout.toJson();
        try { layout.moveKey(0, 0, 1, 0); fail(); } catch (IllegalStateException expected) { }
        assertEquals(before, layout.toJson());
    }
    private static void reject(String input) throws Exception {
        try { ExtraKeyLayout.parse(input); fail("Expected rejection"); } catch (IOException expected) { }
    }
}
