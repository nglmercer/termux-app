package com.termux.app.extrakeys;

import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class ExtraKeyPropertyAppendTest extends TestCase {
    public void testPreservesOriginalAndPropertiesEscapeSemantics() throws Exception {
        byte[] original = "# comment\nother=preserve\nextra-keys=[[OLD]]".getBytes(StandardCharsets.UTF_8);
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[{key:'\\\\',display:'你好',popup:'TAB'}]]");
        byte[] append = ExtraKeyPropertyAppend.prepare(original, layout);
        byte[] combined = new byte[original.length + append.length];
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(append, 0, combined, original.length, append.length);
        Properties properties = new Properties();
        properties.load(new InputStreamReader(new ByteArrayInputStream(combined), StandardCharsets.UTF_8));
        assertEquals("preserve", properties.getProperty("other"));
        assertEquals(layout.toJson(), properties.getProperty("extra-keys"));
        assertTrue(new String(combined, StandardCharsets.UTF_8).startsWith("# comment\nother=preserve\nextra-keys=[[OLD]]\n"));
    }
    public void testRejectsUnfinishedContinuationAndInvalidUtf8() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC]]");
        reject("a=one\\\n".getBytes(StandardCharsets.UTF_8), layout);
        reject("a=one\\".getBytes(StandardCharsets.UTF_8), layout);
        reject(new byte[]{(byte)0xc3, 0x28}, layout);
        byte[] safe = "a=one\\\\\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(ExtraKeyPropertyAppend.prepare(safe, layout).length > 0);
    }
    public void testCrLfAndBlankLineTerminateContinuation() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[ESC]]");
        byte[] original = "a=one\\\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] append = ExtraKeyPropertyAppend.prepare(original, layout);
        byte[] combined = new byte[original.length + append.length];
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(append, 0, combined, original.length, append.length);
        Properties properties = new Properties();
        properties.load(new InputStreamReader(new ByteArrayInputStream(combined), StandardCharsets.UTF_8));
        assertEquals(layout.toJson(), properties.getProperty("extra-keys"));
    }
    public void testRejectsMalformedExistingProperties() throws Exception {
        reject("a=\\uXYZ1\n".getBytes(StandardCharsets.UTF_8), ExtraKeyLayout.parse("[[ESC]]"));
    }
    private static void reject(byte[] original, ExtraKeyLayout layout) throws Exception {
        try { ExtraKeyPropertyAppend.prepare(original, layout); fail(); } catch (IOException expected) { }
    }
}
