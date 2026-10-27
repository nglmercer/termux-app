package com.termux.app.control.ipc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Four-byte big-endian length-prefixed frames. This class does not open a transport. */
public final class FrameCodec {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private final int mLimit;

    public FrameCodec(int limit) {
        if (limit <= 0 || limit > MAX_FRAME_BYTES) throw new IllegalArgumentException("Invalid frame limit");
        mLimit = limit;
    }

    /** Returns null only for EOF before a new frame; partial headers and bodies are errors. */
    public byte[] read(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) return null;
        int second = readByte(input);
        int third = readByte(input);
        int fourth = readByte(input);
        long length = ((long) first << 24) | ((long) second << 16) | ((long) third << 8) | fourth;
        if (length == 0 || length > mLimit) throw new IOException("Invalid frame length");
        byte[] body = new byte[(int) length];
        int offset = 0;
        while (offset < body.length) {
            int n = input.read(body, offset, body.length - offset);
            if (n < 0) throw new EOFException("Truncated frame body");
            if (n == 0) {
                body[offset++] = (byte) readByte(input);
            } else {
                offset += n;
            }
        }
        return body;
    }

    /** The caller must serialize writes and apply deadlines at the transport layer. */
    public void write(OutputStream output, byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > mLimit) throw new IOException("Invalid frame length");
        int length = body.length;
        output.write((length >>> 24) & 0xff);
        output.write((length >>> 16) & 0xff);
        output.write((length >>> 8) & 0xff);
        output.write(length & 0xff);
        output.write(body);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("Truncated frame");
        return value;
    }
}
