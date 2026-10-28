package com.termux.app.extrakeys;
import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ExtraKeyDocumentTest extends TestCase {
    public void testRoundTripPreservesAdvancedFields() throws Exception {
        ExtraKeyLayout original = ExtraKeyLayout.parse("[[{macro:'CTRL c',display:'停止',popup:{key:'ESC',future:{v:7}}}]]");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ExtraKeyDocument.write(out, original);
        ExtraKeyLayout parsed = ExtraKeyDocument.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(original.toJson(), parsed.toJson());
    }
    public void testRejectsUnknownDuplicateAndFutureVersion() throws Exception {
        reject("{\"version\":2,\"rows\":[[\"ESC\"]]}");
        reject("{\"version\":1,\"version\":1,\"rows\":[[\"ESC\"]]}");
        reject("{\"version\":1,\"rows\":[[{\"key\":\"A\",\"key\":\"B\"}]]}");
        reject("{\"version\":1,\"rows\":[[\"ESC\"]],\"extra\":true}");
        reject("{\"version\":1,\"rows\":[[\"ESC\"]]} true");
    }
    public void testRejectsInvalidUtf8AndOversizedDocument() throws Exception {
        try { ExtraKeyDocument.read(new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28})); fail(); } catch (IOException expected) { }
        try { ExtraKeyDocument.read(new ByteArrayInputStream(new byte[ExtraKeyDocument.MAX_BYTES + 1])); fail(); } catch (IOException expected) { }
    }
    private static void reject(String text) throws Exception {
        try { ExtraKeyDocument.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))); fail(); } catch (IOException expected) { }
    }
}
