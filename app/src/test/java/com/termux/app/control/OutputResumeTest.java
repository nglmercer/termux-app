package com.termux.app.control;

import com.termux.terminal.OutputReplayBuffer;
import junit.framework.TestCase;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class OutputResumeTest extends TestCase {
    private static final String TOKEN = repeat('a'), OTHER = repeat('b');
    private static String repeat(char value) { char[] chars = new char[64]; java.util.Arrays.fill(chars, value); return new String(chars); }
    private static final class FakeSource implements OutputSubscriptions.Source {
        final OutputReplayBuffer ring = new OutputReplayBuffer(8); int closes;
        public OutputReplayBuffer buffer() { return ring; }
        public void close() { closes++; ring.close(); }
    }
    private static final class ClockScheduler implements OutputSubscriptions.Clock, OutputSubscriptions.Scheduler {
        long now; boolean fail;
        final List<Entry> entries = new ArrayList<>();
        static final class Entry { Runnable task; long at; boolean cancelled; }
        public long nowMillis() { return now; }
        public OutputSubscriptions.Cancel schedule(Runnable task, long delay) {
            if (fail) throw new IllegalStateException("scheduler unavailable");
            Entry entry = new Entry(); entry.task = task; entry.at = now + delay; entries.add(entry);
            return () -> entry.cancelled = true;
        }
        void advance(long millis) {
            now += millis;
            for (;;) {
                Entry found = null;
                for (Entry entry : entries) if (!entry.cancelled && entry.at <= now) { found = entry; break; }
                if (found == null) return;
                entries.remove(found); found.task.run();
            }
        }
        int pending() { int count = 0; for (Entry entry : entries) if (!entry.cancelled) count++; return count; }
    }
    private static EnumSet<ControlCore.Scope> permitted() {
        return EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
    }
    public void testResumePreservesOffsetsAndRequiresScopeAndToken() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client original = registry.newClient(permitted());
        original.subscribeResumable("s", TOKEN);
        source.ring.append(new byte[]{1,2,3}, 0, 3);
        original.close(); assertEquals(0, source.closes); assertEquals(1, timer.pending());
        source.ring.append(new byte[]{4,5}, 0, 2);
        OutputSubscriptions.Client denied = registry.newClient(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT));
        try { denied.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        OutputSubscriptions.Client resumed = registry.newClient(permitted());
        try { resumed.resume("s", OTHER); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        try { resumed.resume("other", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        resumed.resume("s", TOKEN); assertEquals(0, timer.pending());
        assertTrue(java.util.Arrays.equals(new byte[]{4,5}, resumed.read("s", 3, 8).data));
        timer.advance(15_000); assertEquals(0, source.closes);
        resumed.unsubscribe("s"); assertEquals(1, source.closes); denied.close(); resumed.close(); registry.close();
    }
    public void testExpiryAndStopRevokeRetainedAndActiveTickets() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client first = registry.newClient(permitted()); first.subscribeResumable("s", TOKEN); first.close();
        timer.advance(14_999); assertEquals(0, source.closes);
        OutputSubscriptions.Client second = registry.newClient(permitted()); second.resume("s", TOKEN); second.close();
        timer.advance(1); assertEquals(0, source.closes);
        timer.advance(14_999); assertEquals(1, source.closes);
        OutputSubscriptions.Client third = registry.newClient(permitted());
        try { third.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        registry.close();
    }
    public void testOverdueTicketIsRejectedEvenIfTimerHasNotRun() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client first = registry.newClient(permitted()); first.subscribeResumable("s", TOKEN); first.close();
        timer.now = OutputSubscriptions.RESUME_WINDOW_MILLIS;
        OutputSubscriptions.Client second = registry.newClient(permitted());
        try { second.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        assertEquals(1, source.closes); assertEquals(0, timer.pending());
        second.close(); registry.close();
    }
    public void testRevocationImmediatelyClosesDetachedAndPreventsFutureRetention() throws Exception {
        ClockScheduler timer = new ClockScheduler(); List<FakeSource> sources = new ArrayList<>();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> { FakeSource s = new FakeSource(); sources.add(s); return s; }, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client a = registry.newClient(permitted()); a.subscribeResumable("a", TOKEN); a.close();
        OutputSubscriptions.Client b = registry.newClient(permitted()); b.subscribeResumable("b", OTHER);
        registry.setRetentionEnabled(false);
        assertEquals(1, sources.get(0).closes); assertEquals(0, sources.get(1).closes); assertEquals(0, timer.pending());
        b.close(); assertEquals(1, sources.get(1).closes);
        registry.close();
    }
    public void testActiveTicketCannotBeStolenAndRetentionNeedsConsent() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        OutputSubscriptions.Client first = registry.newClient(permitted());
        try { first.subscribeResumable("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
        registry.setRetentionEnabled(true); first.subscribeResumable("s", TOKEN);
        OutputSubscriptions.Client second = registry.newClient(permitted());
        try { second.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.CONFLICT, e.code); }
        try { second.resume("s", "invalid"); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        first.unsubscribe("s"); assertEquals(1, source.closes);
        try { second.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_FOUND, e.code); }
        first.close(); second.close(); registry.close();
    }
    public void testRetainedTicketsCountAgainstGlobalQuota() throws Exception {
        ClockScheduler timer = new ClockScheduler();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> new FakeSource(), timer, timer);
        registry.setRetentionEnabled(true);
        for (int clientNo = 0; clientNo < 8; clientNo++) {
            OutputSubscriptions.Client client = registry.newClient(permitted());
            for (int j = 0; j < 4; j++) {
                int index = clientNo * 4 + j;
                client.subscribeResumable("s" + index, String.format(java.util.Locale.ROOT, "%064x", index));
            }
            client.close();
        }
        assertEquals(32, timer.pending());
        OutputSubscriptions.Client extra = registry.newClient(permitted());
        try { extra.subscribe("extra"); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.LIMIT_REACHED, e.code); }
        registry.setRetentionEnabled(false); assertEquals(0, timer.pending());
        extra.subscribe("extra"); extra.close(); registry.close();
    }
    public void testResumeReportsOverwrittenBytesAsGap() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client first = registry.newClient(permitted()); first.subscribeResumable("s", TOKEN); first.close();
        byte[] bytes = {0,1,2,3,4,5,6,7,8,9,10,11}; source.ring.append(bytes, 0, bytes.length);
        OutputSubscriptions.Client next = registry.newClient(permitted()); next.resume("s", TOKEN);
        OutputReplayBuffer.ReadResult result = next.read("s", 0, 8);
        assertTrue(result.gap); assertEquals(4, result.offset); assertEquals(12, result.nextOffset);
        assertTrue(java.util.Arrays.equals(new byte[]{4,5,6,7,8,9,10,11}, result.data));
        next.unsubscribe("s"); next.close(); registry.close();
    }
    public void testExitedSessionCannotResumeClosedBuffer() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client first = registry.newClient(permitted()); first.subscribeResumable("s", TOKEN); first.close();
        source.ring.close();
        OutputSubscriptions.Client next = registry.newClient(permitted());
        try { next.resume("s", TOKEN); fail(); } catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.NOT_RUNNING, e.code); }
        assertEquals(1, source.closes); assertEquals(0, timer.pending());
        next.close(); registry.close();
    }
    public void testSchedulerFailureDoesNotRetainOutput() throws Exception {
        ClockScheduler timer = new ClockScheduler(); FakeSource source = new FakeSource();
        OutputSubscriptions registry = new OutputSubscriptions(() -> true, id -> source, timer, timer);
        registry.setRetentionEnabled(true);
        OutputSubscriptions.Client client = registry.newClient(permitted()); client.subscribeResumable("s", TOKEN);
        timer.fail = true; client.close(); assertEquals(1, source.closes);
        registry.close();
    }
}
