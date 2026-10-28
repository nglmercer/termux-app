package com.termux.app.extrakeys;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Construct an append-only override without rewriting unrelated properties or comments. */
public final class ExtraKeyPropertyAppend {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    private ExtraKeyPropertyAppend() { }

    public static byte[] prepare(byte[] original, ExtraKeyLayout layout) throws IOException {
        if (original == null || original.length > MAX_FILE_BYTES || layout == null) throw new IOException("Invalid properties file");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original));
            Properties previous = new Properties();
            previous.load(new InputStreamReader(new ByteArrayInputStream(original), StandardCharsets.UTF_8));
        } catch (CharacterCodingException | IllegalArgumentException e) {
            throw new IOException("Invalid properties encoding", e);
        }
        if (hasUnfinishedContinuation(original)) throw new IOException("Properties file ends with a continuation");
        String value = layout.toJson();
        // Java Properties consumes backslash escapes even in values; preserve JSON escapes.
        String line = (needsNewline(original) ? "\n" : "") + "extra-keys=" + value.replace("\\", "\\\\") + "\n";
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(line));
            byte[] append = new byte[encoded.remaining()]; encoded.get(append);
            if (append.length > MAX_FILE_BYTES - original.length) throw new IOException("Properties file limit exceeded");
            return append;
        } catch (CharacterCodingException e) { throw new IOException("Invalid layout text", e); }
    }

    private static boolean needsNewline(byte[] data) {
        return data.length > 0 && data[data.length - 1] != '\n' && data[data.length - 1] != '\r';
    }

    /** A final continued physical line would absorb the appended property. */
    private static boolean hasUnfinishedContinuation(byte[] data) {
        int end = data.length;
        if (end > 0 && data[end - 1] == '\n') { end--; if (end > 0 && data[end - 1] == '\r') end--; }
        else if (end > 0 && data[end - 1] == '\r') end--;
        int slashCount = 0;
        while (end > 0 && data[end - 1] == '\\') { slashCount++; end--; }
        return (slashCount & 1) != 0;
    }
}
