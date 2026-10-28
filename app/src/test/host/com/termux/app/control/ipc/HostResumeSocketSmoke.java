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
import java.util.concurrent.atomic.AtomicReference;

/** Host protocol test with simulated credentials and scheduler, not Android LocalSocket. */
public final class HostResumeSocketSmoke {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]); AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger();
        AtomicReference<OutputReplayBuffer> output = new AtomicReference<>();
        long started = System.nanoTime();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> {
            if (!"s".equals(id)) throw new ControlCore.ControlException(ControlCore.Error.NOT_FOUND);
            opens.incrementAndGet(); OutputReplayBuffer ring = new OutputReplayBuffer(32); output.set(ring);
            byte[] initial = "FIRST".getBytes(java.nio.charset.StandardCharsets.US_ASCII); ring.append(initial, 0, initial.length);
            return new OutputSubscriptions.Source() {
                public OutputReplayBuffer buffer() { return ring; }
                public void close() { closes.incrementAndGet(); ring.close(); }
            };
        }, () -> (System.nanoTime() - started) / 1_000_000L, (task, delay) -> () -> {});
        registry.setRetentionEnabled(true);
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
            server.bind(UnixDomainSocketAddress.of(path)); System.out.println("READY"); System.out.flush();
            for (int i = 0; i < 2; i++) {
                try (SocketChannel peer = server.accept()) {
                    RpcConnection connection = new RpcConnection(new ControlCore(backend),
                        new ServiceThreadDispatch(Runnable::run, () -> false, 4),
                        EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME), () -> true, registry);
                    connection.serve(1000, 1000, Channels.newInputStream(peer), Channels.newOutputStream(peer));
                }
                if (i == 0) {
                    byte[] later = "SECOND".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if (output.get() == null || !output.get().append(later, 0, later.length)) throw new AssertionError("ring not retained");
                }
            }
            if (opens.get() != 1 || closes.get() != 1) throw new AssertionError("incorrect ring lifecycle");
            registry.close(); System.out.println("RESUME OK");
        } finally { Files.deleteIfExists(path); }
    }
}
