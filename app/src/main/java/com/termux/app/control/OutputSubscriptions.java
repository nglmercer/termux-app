package com.termux.app.control;

import com.termux.terminal.OutputReplayBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Service-thread subscription manager; transports must authenticate and close their clients. */
public final class OutputSubscriptions implements AutoCloseable {
    public interface ThreadCheck { boolean isServiceThread(); }
    public interface Clock { long nowMillis(); }
    public interface Cancel { void cancel(); }
    public interface Scheduler { Cancel schedule(Runnable task, long delayMillis); }
    public interface Source extends AutoCloseable {
        OutputReplayBuffer buffer();
        @Override void close();
    }
    public interface Backend { Source open(String sessionId) throws ControlCore.ControlException; }
    private static final int MAX_CLIENTS = 20, MAX_PER_CLIENT = 4, MAX_SUBSCRIPTIONS = 32;
    public static final long RESUME_WINDOW_MILLIS = 15_000;
    private final ThreadCheck mThreadCheck;
    private final Backend mBackend;
    private final Clock mClock;
    private final Scheduler mScheduler;
    private final Map<String, Shared> mSources = new HashMap<>();
    private final Map<String, Ticket> mTickets = new HashMap<>();
    private final Set<Client> mClients = new HashSet<>();
    private int mSubscriptionCount;
    private boolean mClosed, mRetentionEnabled;

    private static final class Shared {
        final Source source;
        int refs;
        Shared(Source source) { this.source = source; }
    }
    private static final class Binding {
        final Shared shared;
        Ticket ticket;
        Binding(Shared shared) { this.shared = shared; }
    }
    private final class Ticket {
        final String token, sessionId;
        final Shared shared;
        Client owner;
        long deadline, generation;
        Cancel timer;
        Ticket(String token, String sessionId, Shared shared, Client owner) {
            this.token = token; this.sessionId = sessionId; this.shared = shared; this.owner = owner;
        }
    }

    /** Retention is unavailable unless a service-thread scheduler and monotonic clock are provided. */
    public OutputSubscriptions(ThreadCheck check, Backend backend) { this(check, backend, null, null); }
    public OutputSubscriptions(ThreadCheck check, Backend backend, Clock clock, Scheduler scheduler) {
        if (check == null || backend == null || (clock == null) != (scheduler == null)) throw new IllegalArgumentException("Missing configuration");
        mThreadCheck = check; mBackend = backend; mClock = clock; mScheduler = scheduler;
    }

    /** Only trusted local policy can enable retention; disabling immediately revokes all tickets. */
    public void setRetentionEnabled(boolean enabled) {
        checkThread();
        if (enabled && (mClosed || mClock == null || mScheduler == null)) throw new IllegalStateException("Retention unavailable");
        mRetentionEnabled = enabled;
        if (!enabled) for (Ticket ticket : new HashSet<>(mTickets.values())) revoke(ticket);
    }

    /** Scope assertions are valid only when supplied by a trusted, authenticated transport. */
    public Client newClient(Set<ControlCore.Scope> scopes) throws ControlCore.ControlException {
        checkThread();
        if (mClosed) throw error(ControlCore.Error.UNAVAILABLE);
        if (mClients.size() >= MAX_CLIENTS) throw error(ControlCore.Error.LIMIT_REACHED);
        Set<ControlCore.Scope> copy = scopes == null || scopes.isEmpty() ? Collections.emptySet() :
            Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        Client client = new Client(copy); mClients.add(client); return client;
    }

    public final class Client implements AutoCloseable {
        private final Set<ControlCore.Scope> scopes;
        private final Map<String, Binding> subscriptions = new HashMap<>();
        private boolean closed;
        private Client(Set<ControlCore.Scope> scopes) { this.scopes = scopes; }

        public void subscribe(String sessionId) throws ControlCore.ControlException {
            checkAuthorized(); validateSession(sessionId);
            if (subscriptions.containsKey(sessionId)) return;
            subscriptions.put(sessionId, acquire(sessionId));
        }

        /** The transport supplies a fresh, unpredictable token generated off the service thread. */
        public void subscribeResumable(String sessionId, String token) throws ControlCore.ControlException {
            checkResumeAuthorized(); validateSession(sessionId); validateToken(token);
            if (subscriptions.containsKey(sessionId) || mTickets.containsKey(token)) throw error(ControlCore.Error.CONFLICT);
            Binding binding = acquire(sessionId);
            Ticket ticket = new Ticket(token, sessionId, binding.shared, this);
            binding.ticket = ticket; subscriptions.put(sessionId, binding); mTickets.put(token, ticket);
        }

        public void resume(String sessionId, String token) throws ControlCore.ControlException {
            checkResumeAuthorized(); validateSession(sessionId); validateToken(token);
            if (subscriptions.containsKey(sessionId)) throw error(ControlCore.Error.CONFLICT);
            if (subscriptions.size() >= MAX_PER_CLIENT) throw error(ControlCore.Error.LIMIT_REACHED);
            Ticket ticket = mTickets.get(token);
            if (ticket == null || !ticket.sessionId.equals(sessionId)) throw error(ControlCore.Error.NOT_FOUND);
            if (ticket.owner != null) throw error(ControlCore.Error.CONFLICT);
            long now = mClock.nowMillis();
            if (now < 0) { revoke(ticket); throw error(ControlCore.Error.UNAVAILABLE); }
            if (now >= ticket.deadline) { revoke(ticket); throw error(ControlCore.Error.NOT_FOUND); }
            try { ticket.shared.source.buffer().read(0, 1); }
            catch (IllegalStateException e) { revoke(ticket); throw error(ControlCore.Error.NOT_RUNNING); }
            ticket.owner = this; ticket.deadline = 0; ticket.generation++; cancelTimer(ticket);
            Binding binding = new Binding(ticket.shared); binding.ticket = ticket;
            subscriptions.put(sessionId, binding);
        }

        public OutputReplayBuffer.ReadResult read(String sessionId, long offset, int limit) throws ControlCore.ControlException {
            checkAuthorized();
            Binding binding = subscriptions.get(sessionId);
            if (binding == null) throw error(ControlCore.Error.NOT_FOUND);
            try { return binding.shared.source.buffer().read(offset, limit); }
            catch (IllegalArgumentException e) { throw error(ControlCore.Error.INVALID_REQUEST); }
            catch (IllegalStateException e) { throw error(ControlCore.Error.NOT_RUNNING); }
        }

        public void unsubscribe(String sessionId) throws ControlCore.ControlException {
            checkAuthorized();
            Binding binding = subscriptions.remove(sessionId);
            if (binding == null) throw error(ControlCore.Error.NOT_FOUND);
            if (binding.ticket != null) { mTickets.remove(binding.ticket.token); binding.ticket.owner = null; }
            release(sessionId, binding.shared);
        }
        private Binding acquire(String sessionId) throws ControlCore.ControlException {
            if (subscriptions.size() >= MAX_PER_CLIENT || mSubscriptionCount >= MAX_SUBSCRIPTIONS)
                throw error(ControlCore.Error.LIMIT_REACHED);
            Shared shared = mSources.get(sessionId);
            if (shared == null) {
                Source source = mBackend.open(sessionId);
                if (source == null) throw error(ControlCore.Error.UNAVAILABLE);
                if (source.buffer() == null) { source.close(); throw error(ControlCore.Error.UNAVAILABLE); }
                shared = new Shared(source); mSources.put(sessionId, shared);
            }
            shared.refs++; mSubscriptionCount++;
            return new Binding(shared);
        }
        private void checkAuthorized() throws ControlCore.ControlException {
            checkThread();
            if (closed || mClosed) throw error(ControlCore.Error.UNAVAILABLE);
            if (!scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT)) throw error(ControlCore.Error.DENIED);
        }
        private void checkResumeAuthorized() throws ControlCore.ControlException {
            checkAuthorized();
            if (!scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME)) throw error(ControlCore.Error.DENIED);
            if (!mRetentionEnabled) throw error(ControlCore.Error.UNAVAILABLE);
        }
        @Override public void close() {
            checkThread();
            if (closed) return;
            closed = true;
            for (Map.Entry<String, Binding> entry : subscriptions.entrySet()) {
                Binding binding = entry.getValue(); Ticket ticket = binding.ticket;
                if (ticket != null && mRetentionEnabled && retain(ticket)) continue;
                if (ticket != null) { mTickets.remove(ticket.token); ticket.owner = null; }
                release(entry.getKey(), binding.shared);
            }
            subscriptions.clear(); mClients.remove(this);
        }
    }

    private boolean retain(Ticket ticket) {
        long now = mClock.nowMillis();
        if (now < 0 || now > Long.MAX_VALUE - RESUME_WINDOW_MILLIS) return false;
        ticket.owner = null; ticket.deadline = now + RESUME_WINDOW_MILLIS;
        long generation = ++ticket.generation;
        try { ticket.timer = mScheduler.schedule(() -> expire(ticket, generation), RESUME_WINDOW_MILLIS); return ticket.timer != null; }
        catch (RuntimeException e) { return false; }
    }
    private void expire(Ticket ticket, long generation) {
        checkThread();
        if (mTickets.get(ticket.token) != ticket || ticket.owner != null || ticket.generation != generation) return;
        ticket.timer = null;
        long now = mClock.nowMillis();
        if (now < 0 || now >= ticket.deadline) revoke(ticket);
        else try {
            ticket.timer = mScheduler.schedule(() -> expire(ticket, generation), ticket.deadline - now);
            if (ticket.timer == null) revoke(ticket);
        } catch (RuntimeException e) { revoke(ticket); }
    }
    private void cancelTimer(Ticket ticket) {
        Cancel timer = ticket.timer; ticket.timer = null;
        if (timer != null) try { timer.cancel(); } catch (RuntimeException ignored) { }
    }
    private void revoke(Ticket ticket) {
        if (mTickets.remove(ticket.token) != ticket) return;
        cancelTimer(ticket);
        if (ticket.owner != null) {
            Binding binding = ticket.owner.subscriptions.get(ticket.sessionId);
            if (binding != null && binding.ticket == ticket) binding.ticket = null;
            ticket.owner = null;
        } else release(ticket.sessionId, ticket.shared);
    }
    private void release(String sessionId, Shared shared) {
        mSubscriptionCount--;
        if (--shared.refs == 0) { mSources.remove(sessionId); shared.source.close(); }
    }
    private static void validateSession(String id) throws ControlCore.ControlException {
        if (id == null || id.isEmpty() || id.length() > 64) throw error(ControlCore.Error.INVALID_REQUEST);
    }
    private static void validateToken(String token) throws ControlCore.ControlException {
        if (token == null || token.length() != 64) throw error(ControlCore.Error.INVALID_REQUEST);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) throw error(ControlCore.Error.INVALID_REQUEST);
        }
    }
    @Override public void close() {
        checkThread();
        if (mClosed) return;
        setRetentionEnabled(false);
        for (Client client : new HashSet<>(mClients)) client.close();
        mClosed = true;
    }
    private void checkThread() {
        if (!mThreadCheck.isServiceThread()) throw new IllegalStateException("Must run on service thread");
    }
    private static ControlCore.ControlException error(ControlCore.Error code) { return new ControlCore.ControlException(code); }
}
