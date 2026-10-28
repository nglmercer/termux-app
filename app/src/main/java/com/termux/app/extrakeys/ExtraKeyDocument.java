package com.termux.app.extrakeys;

import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Versioned portable document containing one extra-key layout. */
public final class ExtraKeyDocument {
    public static final int MAX_BYTES = 256 * 1024 + 64;
    private ExtraKeyDocument() { }

    public static ExtraKeyLayout read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Missing input");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                if (bytes.size() == MAX_BYTES) throw new IOException("Document too large");
                bytes.write(value);
            } else {
                if (count > MAX_BYTES - bytes.size()) throw new IOException("Document too large");
                bytes.write(buffer, 0, count);
            }
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8", e); }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            Set<String> names = new HashSet<>();
            boolean version = false; JsonElement rows = null;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!names.add(name)) throw new IOException("Duplicate field");
                if ("version".equals(name)) {
                    if (reader.peek() != JsonToken.NUMBER || new BigDecimal(reader.nextString()).intValueExact() != 1)
                        throw new IOException("Unsupported version");
                    version = true;
                } else if ("rows".equals(name)) {
                    rows = ExtraKeyLayout.read(reader, 0, new int[]{0});
                } else throw new IOException("Unknown document field");
            }
            reader.endObject();
            if (!version || rows == null || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid document");
            return ExtraKeyLayout.parse(rows.toString());
        } catch (IllegalStateException | NumberFormatException | ArithmeticException e) {
            throw new IOException("Invalid document", e);
        }
    }

    public static void write(OutputStream output, ExtraKeyLayout layout) throws IOException {
        if (output == null || layout == null) throw new IOException("Missing output or layout");
        String text = "{\"version\":1,\"rows\":" + layout.toJson() + "}\n";
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if (encoded.remaining() > MAX_BYTES) throw new IOException("Document too large");
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            output.write(bytes); output.flush();
        } catch (CharacterCodingException e) { throw new IOException("Invalid layout text", e); }
    }
}
