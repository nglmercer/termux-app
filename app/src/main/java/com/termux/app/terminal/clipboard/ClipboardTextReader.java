package com.termux.app.terminal.clipboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A bounded UTF-8 reader for explicitly pasted text content URIs. Run on an I/O worker. */
public final class ClipboardTextReader {
    public static final int MAX_BYTES = 1024 * 1024;
    private ClipboardTextReader() { }
    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Text unavailable");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                if (out.size() == MAX_BYTES) throw new IOException("Clipboard text too large");
                out.write(value);
            } else {
                if (count > MAX_BYTES - out.size()) throw new IOException("Clipboard text too large");
                out.write(buffer, 0, count);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
