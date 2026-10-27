package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
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

/** Host-only interoperability harness; it does not emulate Android peer credential checks. */
public final class HostRpcSocketSmoke {
    private static final class Backend implements ControlCore.Backend {
        int calls;
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String name, int c, int r, int w, int h) { throw new UnsupportedOperationException(); }
        @Override public void write(String id, byte[] data) {
            if (!"session-1".equals(id) || !Arrays.equals(data, new byte[]{0, (byte)255, 27, 10}))
                throw new AssertionError("Binary input mismatch");
            calls++;
        }
        @Override public void rename(String id, String name) { throw new UnsupportedOperationException(); }
        @Override public void close(String id) { throw new UnsupportedOperationException(); }
        @Override public void resize(String id, int c, int r, int w, int h) { throw new UnsupportedOperationException(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("socket path required");
        Path path = Path.of(args[0]);
        Backend backend = new Backend();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            System.out.println("READY");
            System.out.flush();
            try (SocketChannel client = server.accept()) {
                RpcConnection connection = new RpcConnection(new ControlCore(backend),
                    new ServiceThreadDispatch(Runnable::run, () -> false, 4),
                    EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), () -> true);
                connection.serve(1000, 1000, Channels.newInputStream(client), Channels.newOutputStream(client));
            }
            if (backend.calls != 1) throw new AssertionError("Expected exactly one binary write");
            System.out.println("OK");
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
