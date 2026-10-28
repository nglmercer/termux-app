package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
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

/** Host-only stream and rate-limit test; peer UID and Android LocalSocket are simulated. */
public final class HostLifetimeSocketSmoke {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]); AtomicInteger calls = new AtomicInteger();
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { calls.incrementAndGet(); return Collections.emptyList(); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { return null; }
            public void write(String id, byte[] data) { }
            public void rename(String id, String name) { }
            public void close(String id) { }
            public void resize(String id, int c, int r, int w, int h) { }
        };
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path)); System.out.println("READY"); System.out.flush();
            try (SocketChannel peer = server.accept()) {
                RpcConnection connection = new RpcConnection(new ControlCore(backend),
                    new ServiceThreadDispatch(Runnable::run, () -> false, 4),
                    EnumSet.of(ControlCore.Scope.SESSIONS_READ), () -> true);
                connection.serve(1000, 1000, Channels.newInputStream(peer), Channels.newOutputStream(peer));
            }
            if (calls.get() != 1005) throw new AssertionError("Lost requests: " + calls.get());
            System.out.println("LIFETIME OK");
        } finally { Files.deleteIfExists(path); }
    }
}
