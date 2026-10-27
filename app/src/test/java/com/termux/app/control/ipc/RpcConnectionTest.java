package com.termux.app.control.ipc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RpcConnectionTest extends TestCase {
    private static final class Backend implements ControlCore.Backend {
        int calls;
        String target;
        byte[] received;
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() {
            calls++;
            return Collections.singletonList(new ControlCore.SessionInfo("s1", ControlCore.State.RUNNING, 42, "test"));
        }
        @Override public ControlCore.SessionInfo create(String name, int c, int r, int w, int h) { calls++; return null; }
        @Override public void write(String id, byte[] data) { calls++; target = id; received = data; }
        @Override public void rename(String id, String name) { calls++; }
        @Override public void close(String id) { calls++; }
        @Override public void resize(String id, int c, int r, int w, int h) { calls++; }
    }
    private final FrameCodec codec = new FrameCodec(RpcRequestParser.MAX_REQUEST_BYTES);
    private final Backend backend = new Backend();
    private final ServiceThreadDispatch dispatch = new ServiceThreadDispatch(Runnable::run, () -> false, 20);

    private byte[] requests(String... json) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String value : json) codec.write(out, value.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private JsonObject read(ByteArrayInputStream input) throws Exception {
        byte[] frame = codec.read(input);
        assertNotNull(frame);
        return JsonParser.parseString(new String(frame, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public void testWrongUidCannotInvokeOrReceiveData() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ), () -> true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1001, new ByteArrayInputStream(requests(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.list\"}")), out);
        assertEquals(0, backend.calls);
        assertEquals(0, out.size());
    }

    public void testSameUidNegotiatesAndScopesAreEnforced() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ), () -> true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(requests(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}",
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.list\"}",
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.close\",\"params\":{\"id\":\"s1\"}}")), out);
        ByteArrayInputStream responses = new ByteArrayInputStream(out.toByteArray());
        assertEquals(1, read(responses).getAsJsonObject("result").get("version").getAsInt());
        assertEquals("s1", read(responses).getAsJsonObject("result").getAsJsonArray("sessions").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("DENIED", read(responses).getAsJsonObject("error").get("message").getAsString());
        assertNull(codec.read(responses));
        assertEquals(1, backend.calls);
    }

    public void testBinaryFrameTargetsExplicitSessionWithoutCorruptingNextRequest() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ, ControlCore.Scope.SESSIONS_WRITE), () -> true);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.write\",\"params\":{\"id\":\"s1\",\"length\":3}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, new byte[]{0, (byte) 255, 3});
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.list\"}".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(input.toByteArray()), out);
        ByteArrayInputStream responses = new ByteArrayInputStream(out.toByteArray());
        read(responses);
        assertTrue(read(responses).getAsJsonObject("result").get("ok").getAsBoolean());
        assertEquals("s1", backend.target);
        assertTrue(java.util.Arrays.equals(new byte[]{0, (byte) 255, 3}, backend.received));
        assertEquals(3, read(responses).get("id").getAsInt());
        assertEquals(2, backend.calls);
    }

    public void testDeniedBinaryWriteDoesNotReadPayload() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ), () -> true);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.write\",\"params\":{\"id\":\"s1\",\"length\":3}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, new byte[]{1, 2, 3});
        ByteArrayInputStream source = new ByteArrayInputStream(input.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, source, out);
        ByteArrayInputStream responses = new ByteArrayInputStream(out.toByteArray());
        read(responses);
        assertEquals("DENIED", read(responses).getAsJsonObject("error").get("message").getAsString());
        assertEquals(7, source.available());
        assertEquals(0, backend.calls);
    }

    public void testMismatchedBinaryLengthDoesNotWrite() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), () -> true);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.write\",\"params\":{\"id\":\"s1\",\"length\":3}}".getBytes(StandardCharsets.UTF_8));
        codec.write(input, new byte[]{1, 2});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(input.toByteArray()), out);
        ByteArrayInputStream responses = new ByteArrayInputStream(out.toByteArray());
        read(responses);
        assertEquals(-32602, read(responses).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(0, backend.calls);
    }

    public void testStoppingBeforeServiceDispatchDoesNotExecuteRequest() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        ServiceThreadDispatch delayed = new ServiceThreadDispatch(task -> {
            running.set(false);
            task.run();
        }, () -> false, 1);
        RpcConnection connection = new RpcConnection(new ControlCore(backend), delayed,
            EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), running::get);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(requests(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}")), out);
        assertEquals(0, out.size());
        assertEquals(0, backend.calls);
    }

    public void testInvalidJsonReturnsParseErrorAndStops() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(backend), dispatch, Collections.emptySet(), () -> true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        connection.serve(1000, 1000, new ByteArrayInputStream(requests("{not-json}")), out);
        JsonObject response = read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(-32700, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(0, backend.calls);
    }
}
