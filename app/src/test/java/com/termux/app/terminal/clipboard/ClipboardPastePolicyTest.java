package com.termux.app.terminal.clipboard;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ClipboardPastePolicyTest {
    @Test public void selectsOnlyExplicitTextImageAndTextUris() {
        assertEquals(ClipboardPastePolicy.Kind.TEXT, ClipboardPastePolicy.classify("hello", true, true, "content"));
        assertEquals(ClipboardPastePolicy.Kind.TEXT, ClipboardPastePolicy.classify("", false, false, null));
        assertEquals(ClipboardPastePolicy.Kind.IMAGE, ClipboardPastePolicy.classify(null, true, true, "content"));
        assertEquals(ClipboardPastePolicy.Kind.TEXT_URI, ClipboardPastePolicy.classify(null, false, true, "content"));
        assertEquals(ClipboardPastePolicy.Kind.WEB_URI, ClipboardPastePolicy.classify(null, false, true, "https"));
        assertEquals(ClipboardPastePolicy.Kind.UNSUPPORTED, ClipboardPastePolicy.classify(null, false, false, "content"));
        assertEquals(ClipboardPastePolicy.Kind.UNSUPPORTED, ClipboardPastePolicy.classify(null, false, true, "file"));
        assertEquals(ClipboardPastePolicy.Kind.UNSUPPORTED, ClipboardPastePolicy.classify(null, false, true, null));
    }
    @Test public void readerHandlesAProviderThatReturnsZeroBeforeData() throws Exception {
        java.io.InputStream input = new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)) {
            private boolean first = true;
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                if (first) { first = false; return 0; }
                return super.read(buffer, offset, length);
            }
        };
        assertEquals("ok", ClipboardTextReader.read(input));
    }
    @Test public void textReaderSupportsUtf8AndEnforcesLimit() throws Exception {
        assertEquals("hello 🌎\n", ClipboardTextReader.read(new ByteArrayInputStream("hello 🌎\n".getBytes(StandardCharsets.UTF_8))));
        byte[] atLimit = new byte[ClipboardTextReader.MAX_BYTES]; java.util.Arrays.fill(atLimit, (byte) 'x');
        assertEquals(atLimit.length, ClipboardTextReader.read(new ByteArrayInputStream(atLimit)).length());
        try { ClipboardTextReader.read(new ByteArrayInputStream(new byte[ClipboardTextReader.MAX_BYTES + 1])); fail(); }
        catch (IOException expected) { }
    }
}
