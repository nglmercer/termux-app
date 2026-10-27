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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Host loopback test; supplied UIDs and service-thread dispatch are simulated. */
public final class HostOutputSocketSmoke {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        AtomicInteger closed = new AtomicInteger();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> {
            if (!"s".equals(id)) throw new ControlCore.ControlException(ControlCore.Error.NOT_FOUND);
            OutputReplayBuffer ring = new OutputReplayBuffer(16);
            ring.append(new byte[]{0, (byte)255, 27, 10}, 0, 4);
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return ring; }
                public void close() { ring.close(); closed.incrementAndGet(); }
            };
        });
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { return null; }
            public void write(String id, byte[] data) { }
            public void rename(String id, String name) { }
            public void close(String id) { }
            public void resize(String id, int c, int r, int w, int h) { }
        };
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            System.out.println("READY"); System.out.flush();
            try (SocketChannel peer = server.accept()) {
                RpcConnection connection = new RpcConnection(new ControlCore(backend),
                    new ServiceThreadDispatch(Runnable::run, () -> false, 4),
                    EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT), () -> true, registry);
                connection.serve(1000, 1000, Channels.newInputStream(peer), Channels.newOutputStream(peer));
            }
            if (closed.get() != 1) throw new AssertionError("Subscription not released");
        } finally { Files.deleteIfExists(path); }
    }
}
