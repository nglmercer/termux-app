package com.termux.app.control;

import com.termux.terminal.OutputReplayBuffer;
import junit.framework.TestCase;
import java.util.Collections;
import java.util.EnumSet;

public class OutputSubscriptionsTest extends TestCase {
    private static final class FakeSource implements OutputSubscriptions.Source {
        final OutputReplayBuffer ring = new OutputReplayBuffer(8);
        int closes;
        public OutputReplayBuffer buffer() { return ring; }
        public void close() { closes++; ring.close(); }
    }
    public void testSeparateScopeAndPerClientSubscriptions() throws Exception {
        FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source);
        OutputSubscriptions.Client denied = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_READ));
        try { denied.subscribe("s"); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        OutputSubscriptions.Client a = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        OutputSubscriptions.Client b = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        a.subscribe("s"); a.subscribe("s"); b.subscribe("s");
        source.ring.append(new byte[]{1, 2, 3}, 0, 3);
        assertEquals(3, a.read("s", 0, 8).data.length);
        denied.close(); a.close(); assertEquals(0, source.closes);
        assertEquals(3, b.read("s", 0, 8).data.length);
        b.close(); assertEquals(1, source.closes);
        try { b.read("s", 0, 8); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
    }
    public void testCloseRevokesAndPerClientLimit() throws Exception {
        java.util.List<FakeSource> sources = new java.util.ArrayList<>();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> { FakeSource source = new FakeSource(); sources.add(source); return source; });
        OutputSubscriptions.Client a = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        for (int i = 0; i < 4; i++) a.subscribe("s" + i);
        try { a.subscribe("s4"); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.LIMIT_REACHED, e.code); }
        registry.close();
        for (FakeSource source : sources) assertEquals(1, source.closes);
        try { a.subscribe("s"); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
        try { registry.newClient(Collections.emptySet()); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
    }
    public void testClientLimitAndDisconnectReleaseCapacity() throws Exception {
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> new FakeSource());
        java.util.List<OutputSubscriptions.Client> clients = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) clients.add(registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT)));
        try { registry.newClient(Collections.emptySet()); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.LIMIT_REACHED, e.code); }
        clients.get(0).close();
        OutputSubscriptions.Client replacement = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        replacement.subscribe("s"); replacement.unsubscribe("s");
        try { replacement.read("s", 0, 1); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        registry.close();
    }

    public void testUnsubscribedClientCannotReadAndWrongThreadIsRejected() throws Exception {
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> new FakeSource());
        OutputSubscriptions.Client client = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        try { client.read("missing", 0, 8); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        OutputSubscriptions wrong = new OutputSubscriptions(() -> false, id -> new FakeSource());
        try { wrong.newClient(Collections.emptySet()); fail(); } catch (IllegalStateException expected) { }
    }
}
