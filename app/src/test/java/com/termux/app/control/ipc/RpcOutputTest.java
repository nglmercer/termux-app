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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RpcOutputTest extends TestCase {
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
    private OutputSubscriptions registry() {
        return new OutputSubscriptions(() -> true, id -> {
            opens.incrementAndGet();
            OutputReplayBuffer ring = new OutputReplayBuffer(16);
            ring.append(new byte[]{0, (byte)255, 27}, 0, 3);
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return ring; }
                public void close() { closes.incrementAndGet(); ring.close(); }
            };
        });
    }
    private byte[] requestFrames(String... requests) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String request : requests) codec.write(out, request.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
    private JsonObject json(ByteArrayInputStream input) throws Exception {
        return JsonParser.parseString(new String(codec.read(input), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";
    private static final String SUBSCRIBE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.output.subscribe\",\"params\":{\"id\":\"s\"}}";
    private static final String READ = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.output.read\",\"params\":{\"id\":\"s\",\"offset\":0,\"limit\":8}}";

    public void testOutputNeedsSeparateScope() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(new Backend()), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ), () -> true, registry());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        connection.serve(1, 1, new ByteArrayInputStream(requestFrames(HELLO, SUBSCRIBE, READ)), output);
        ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
        json(frames);
        assertEquals("DENIED", json(frames).getAsJsonObject("error").get("message").getAsString());
        assertEquals("DENIED", json(frames).getAsJsonObject("error").get("message").getAsString());
        assertEquals(0, opens.get()); assertNull(codec.read(frames));
    }
    public void testBinaryOutputAndDisconnectCleanup() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(new Backend()), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT), () -> true, registry());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        connection.serve(1, 1, new ByteArrayInputStream(requestFrames(HELLO, SUBSCRIBE, READ)), output);
        ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
        json(frames); assertTrue(json(frames).getAsJsonObject("result").get("ok").getAsBoolean());
        JsonObject metadata = json(frames).getAsJsonObject("result");
        assertEquals(3, metadata.get("length").getAsInt()); assertEquals(3, metadata.get("nextOffset").getAsInt());
        assertTrue(java.util.Arrays.equals(new byte[]{0, (byte)255, 27}, codec.read(frames)));
        assertNull(codec.read(frames)); assertEquals(1, opens.get()); assertEquals(1, closes.get());
    }
    public void testEmptyReadDoesNotEmitBinaryFrame() throws Exception {
        RpcConnection connection = new RpcConnection(new ControlCore(new Backend()), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT), () -> true, registry());
        String empty = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.output.read\",\"params\":{\"id\":\"s\",\"offset\":3,\"limit\":8}}";
        String invalid = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.output.read\",\"params\":{\"id\":\"s\",\"offset\":4,\"limit\":8}}";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        connection.serve(1, 1, new ByteArrayInputStream(requestFrames(HELLO, SUBSCRIBE, empty, invalid)), output);
        ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
        json(frames); json(frames);
        assertEquals(0, json(frames).getAsJsonObject("result").get("length").getAsInt());
        assertEquals("INVALID_REQUEST", json(frames).getAsJsonObject("error").get("message").getAsString());
        assertNull(codec.read(frames)); assertEquals(1, closes.get());
    }
    public void testDisconnectDuringSubscriptionCreationRevokesSource() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> {
            entered.countDown();
            try { if (!proceed.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            OutputReplayBuffer ring = new OutputReplayBuffer(8);
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return ring; }
                public void close() { closes.incrementAndGet(); ring.close(); }
            };
        });
        java.util.concurrent.ConcurrentLinkedQueue<Runnable> cleanups = new java.util.concurrent.ConcurrentLinkedQueue<>();
        ServiceThreadDispatch queued = new ServiceThreadDispatch(cleanups::add, () -> false, 20);
        OutputClientLease lease = new OutputClientLease(registry, queued, EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        Thread worker = new Thread(() -> {
            try { lease.get().subscribe("s"); }
            catch (ControlCore.ControlException expected) { }
        });
        worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
        lease.close(); proceed.countDown(); worker.join(2000);
        assertFalse(worker.isAlive());
        Runnable cleanup = cleanups.poll(); assertNotNull(cleanup); cleanup.run();
        assertEquals(1, closes.get());
    }
}
