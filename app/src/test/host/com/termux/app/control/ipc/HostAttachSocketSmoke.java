package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import com.termux.app.control.OutputSubscriptions;
import com.termux.terminal.OutputReplayBuffer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only protocol loop with fake session, not Android credentials or a real Termux PTY. */
public final class HostAttachSocketSmoke {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]); int pid = Integer.parseInt(args[1]);
        AtomicInteger released = new AtomicInteger(), resized = new AtomicInteger();
        AtomicReference<byte[]> input = new AtomicReference<>();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> {
            if (!"s".equals(id)) throw new ControlCore.ControlException(ControlCore.Error.NOT_FOUND);
            OutputReplayBuffer ring = new OutputReplayBuffer(64);
            byte[] bytes = "HOST-ATTACH-READY".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            ring.append(bytes, 0, bytes.length);
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return ring; }
                public void close() { ring.close(); released.incrementAndGet(); }
            };
        });
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { return Collections.singletonList(new ControlCore.SessionInfo("s", ControlCore.State.RUNNING, pid, "test")); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { return null; }
            public void write(String id, byte[] data) { if (!"s".equals(id)) throw new AssertionError(); input.set(data.clone()); }
            public void rename(String id, String name) { }
            public void close(String id) { }
            public void resize(String id, int c, int r, int w, int h) {
                if (!"s".equals(id) || c != 80 || r != 24) throw new AssertionError("wrong geometry");
                resized.incrementAndGet();
            }
        };
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            System.out.println("READY"); System.out.flush();
            try (SocketChannel peer = server.accept()) {
                RpcConnection connection = new RpcConnection(new ControlCore(backend),
                    new ServiceThreadDispatch(Runnable::run, () -> false, 4),
                    EnumSet.of(ControlCore.Scope.SESSIONS_READ, ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_OUTPUT),
                    () -> true, registry);
                try { connection.serve(1000, 1000, Channels.newInputStream(peer), Channels.newOutputStream(peer)); }
                catch (java.io.IOException ignored) { /* Client can disconnect while a reply is pending. */ }
            }
            if (released.get() != 1 || resized.get() < 1 || !Arrays.equals(input.get(), new byte[]{3, 26}))
                throw new AssertionError("missing input, resize or subscription cleanup");
            System.out.println("ATTACH OK");
        } finally { Files.deleteIfExists(path); }
    }
}
