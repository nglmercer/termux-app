package com.termux.app.control.ipc;

import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class FrameCodecTest extends TestCase {
    public void testFramesRoundTripAndCleanEof() throws Exception {
        FrameCodec codec = new FrameCodec(8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        codec.write(output, new byte[]{0, (byte) 255, 2});
        codec.write(output, new byte[]{9});
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());
        assertTrue(Arrays.equals(new byte[]{0, (byte) 255, 2}, codec.read(input)));
        assertTrue(Arrays.equals(new byte[]{9}, codec.read(input)));
        assertNull(codec.read(input));
    }

    public void testRejectsOversizeBeforeReadingBody() throws Exception {
        FrameCodec codec = new FrameCodec(16);
        InputStream input = new ByteArrayInputStream(new byte[]{0, 0, 1, 0}) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                throw new AssertionError("Body must not be read");
            }
        };
        try { codec.read(input); fail(); } catch (IOException expected) { }
        try { codec.read(new ByteArrayInputStream(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255})); fail(); }
        catch (IOException expected) { }
        try { codec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 0})); fail(); }
        catch (IOException expected) { }
    }

    public void testTruncationAndWriteLimits() throws Exception {
        FrameCodec codec = new FrameCodec(4);
        try { codec.read(new ByteArrayInputStream(new byte[]{0, 0})); fail(); }
        catch (EOFException expected) { }
        try { codec.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 3, 1})); fail(); }
        catch (EOFException expected) { }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { codec.write(output, new byte[5]); fail(); } catch (IOException expected) { }
        try { codec.write(output, new byte[0]); fail(); } catch (IOException expected) { }
        assertEquals(0, output.size());
    }

    public void testPartialReads() throws Exception {
        FrameCodec codec = new FrameCodec(4);
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{0, 0, 0, 3, 1, 2, 3}) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        assertTrue(Arrays.equals(new byte[]{1, 2, 3}, codec.read(input)));
    }
}
