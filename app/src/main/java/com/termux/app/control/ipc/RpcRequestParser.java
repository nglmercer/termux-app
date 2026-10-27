package com.termux.app.control.ipc;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JSON-RPC request parsing. No transport or authorization is provided here. */
public final class RpcRequestParser {
    public static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VALUES = 128;
    private static final int MAX_STRING = 4096;

    public static final class InvalidRequest extends Exception {
        public final boolean parseError;
        public InvalidRequest() { this(false); }
        public InvalidRequest(boolean parseError) { super("Invalid JSON-RPC request"); this.parseError = parseError; }
    }

    public static final class Request {
        public final String method;
        public final Object id;
        public final boolean hasId;
        public final Object params;
        private Request(String method, Object id, boolean hasId, Object params) {
            this.method = method; this.id = id; this.hasId = hasId; this.params = params;
        }
    }

    public Request parse(byte[] bytes) throws InvalidRequest {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) throw new InvalidRequest();
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new InvalidRequest(true); }
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            int[] count = {0};
            Object root = readValue(reader, 0, count);
            if (reader.peek() != JsonToken.END_DOCUMENT || !(root instanceof Map)) throw new InvalidRequest();
            Map<?, ?> object = (Map<?, ?>) root;
            if (!"2.0".equals(object.get("jsonrpc")) || !(object.get("method") instanceof String))
                throw new InvalidRequest();
            String method = (String) object.get("method");
            if (method.isEmpty() || method.length() > 128 || method.startsWith("rpc.")) throw new InvalidRequest();
            boolean hasId = object.containsKey("id");
            Object id = object.get("id");
            if (hasId && id != null && !(id instanceof String) && !(id instanceof BigDecimal)) throw new InvalidRequest();
            if (id instanceof String && ((String) id).length() > 128) throw new InvalidRequest();
            Object params = object.get("params");
            if (object.containsKey("params") && !(params instanceof Map) && !(params instanceof List))
                throw new InvalidRequest();
            return new Request(method, id, hasId, params);
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new InvalidRequest(true);
        }
    }

    private Object readValue(JsonReader reader, int depth, int[] count) throws IOException, InvalidRequest {
        if (depth > MAX_DEPTH || ++count[0] > MAX_VALUES) throw new InvalidRequest();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                Map<String, Object> map = new LinkedHashMap<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > MAX_STRING || !isValidUnicode(name) || map.containsKey(name)) throw new InvalidRequest();
                    map.put(name, readValue(reader, depth + 1, count));
                }
                reader.endObject();
                return Collections.unmodifiableMap(map);
            case BEGIN_ARRAY:
                List<Object> list = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNext()) list.add(readValue(reader, depth + 1, count));
                reader.endArray();
                return Collections.unmodifiableList(list);
            case STRING:
                String value = reader.nextString();
                if (value.length() > MAX_STRING || !isValidUnicode(value)) throw new InvalidRequest();
                return value;
            case NUMBER:
                String number = reader.nextString();
                if (number.length() > 128) throw new InvalidRequest();
                return new BigDecimal(number);
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return null;
            default: throw new InvalidRequest();
        }
    }

    private static boolean isValidUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

}
