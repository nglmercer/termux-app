package com.termux.app.control.ipc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.OutputSubscriptions;
import com.termux.terminal.OutputReplayBuffer;
import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class RpcOutputResumeTest extends TestCase {
    private static final class Backend implements ControlCore.Backend {
        public boolean isCloseEnabled() { return false; }
        public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { return null; }
        public void write(String id, byte[] data) { }
        public void rename(String id, String name) { }
        public void close(String id) { }
        public void resize(String id, int c, int r, int w, int h) { }
    }
    private final FrameCodec codec = new FrameCodec(RpcRequestParser.MAX_REQUEST_BYTES);
    private final ServiceThreadDispatch dispatch = new ServiceThreadDispatch(Runnable::run, () -> false, 20);
    private final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
    private final OutputReplayBuffer buffer = new OutputReplayBuffer(16);
    private OutputSubscriptions registry() {
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> {
            opens.incrementAndGet();
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return buffer; }
                public void close() { closes.incrementAndGet(); buffer.close(); }
            };
        }, () -> 100, (task, delay) -> () -> {});
        registry.setRetentionEnabled(true); return registry;
    }
    private byte[] frames(String... requests) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String request : requests) codec.write(out, request.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
    private JsonObject json(ByteArrayInputStream in) throws Exception {
        return JsonParser.parseString(new String(codec.read(in), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private ByteArrayInputStream run(OutputSubscriptions registry, Set<ControlCore.Scope> scopes, String... requests) throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(new Backend()), dispatch, scopes, () -> true, registry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(frames(requests)), output);
        return new ByteArrayInputStream(output.toByteArray());
    }
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";
    private static final String SUBSCRIBE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.output.subscribe\",\"params\":{\"id\":\"s\",\"resumable\":true}}";
    public void testResumeAcrossConnectionsRequiresSeparateScopeAndSecret() throws Exception {
        OutputSubscriptions registry = registry();
        ByteArrayInputStream denied = run(registry, EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT), HELLO, SUBSCRIBE);
        json(denied); assertEquals("DENIED", json(denied).getAsJsonObject("error").get("message").getAsString());
        assertEquals(0, opens.get());
        EnumSet<ControlCore.Scope> scopes = EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
        ByteArrayInputStream first = run(registry, scopes, HELLO, SUBSCRIBE);
        json(first); String token = json(first).getAsJsonObject("result").get("resumeToken").getAsString();
        assertTrue(token.matches("[0-9a-f]{64}")); assertEquals(1, opens.get()); assertEquals(0, closes.get());
        buffer.append(new byte[]{1,2,3}, 0, 3);
        String resume = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.output.resume\",\"params\":{\"id\":\"s\",\"token\":\"" + token + "\"}}";
        String read = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.output.read\",\"params\":{\"id\":\"s\",\"offset\":1,\"limit\":8}}";
        ByteArrayInputStream second = run(registry, scopes, HELLO, resume, read);
        json(second); assertTrue(json(second).getAsJsonObject("result").get("ok").getAsBoolean());
        assertEquals(2, json(second).getAsJsonObject("result").get("length").getAsInt());
        assertTrue(java.util.Arrays.equals(new byte[]{2,3}, codec.read(second)));
        assertEquals(1, opens.get()); assertEquals(0, closes.get());
        registry.setRetentionEnabled(false); assertEquals(1, closes.get()); registry.close();
    }
    public void testInvalidResumableTypeRejected() throws Exception {
        OutputSubscriptions registry = registry();
        String invalid = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.output.subscribe\",\"params\":{\"id\":\"s\",\"resumable\":null}}";
        ByteArrayInputStream result = run(registry, EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME), HELLO, invalid);
        json(result); assertEquals(-32602, json(result).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(0, opens.get()); registry.close();
    }
}
