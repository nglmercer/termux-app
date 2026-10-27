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
    public interface Source extends AutoCloseable {
        OutputReplayBuffer buffer();
        @Override void close();
    }
    public interface Backend { Source open(String sessionId) throws ControlCore.ControlException; }
    private static final int MAX_CLIENTS = 20, MAX_PER_CLIENT = 4, MAX_SUBSCRIPTIONS = 32;
    private final ThreadCheck mThreadCheck;
    private final Backend mBackend;
    private final Map<String, Shared> mSources = new HashMap<>();
    private final Set<Client> mClients = new HashSet<>();
    private int mSubscriptionCount;
    private boolean mClosed;

    private static final class Shared {
        final Source source;
        int refs;
        Shared(Source source) { this.source = source; }
    }

    public OutputSubscriptions(ThreadCheck check, Backend backend) {
        if (check == null || backend == null) throw new IllegalArgumentException("Missing configuration");
        mThreadCheck = check; mBackend = backend;
    }

    /** Scope assertions are valid only when supplied by a trusted, authenticated transport. */
    public Client newClient(Set<ControlCore.Scope> scopes) throws ControlCore.ControlException {
        checkThread();
        if (mClosed) throw error(ControlCore.Error.UNAVAILABLE);
        if (mClients.size() >= MAX_CLIENTS) throw error(ControlCore.Error.LIMIT_REACHED);
        Set<ControlCore.Scope> copy = scopes == null || scopes.isEmpty() ? Collections.emptySet() :
            Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        Client client = new Client(copy);
        mClients.add(client);
        return client;
    }

    public final class Client implements AutoCloseable {
        private final Set<ControlCore.Scope> scopes;
        private final Map<String, Shared> subscriptions = new HashMap<>();
        private boolean closed;
        private Client(Set<ControlCore.Scope> scopes) { this.scopes = scopes; }

        public void subscribe(String sessionId) throws ControlCore.ControlException {
            checkAuthorized();
            if (sessionId == null || sessionId.isEmpty() || sessionId.length() > 64)
                throw error(ControlCore.Error.INVALID_REQUEST);
            if (subscriptions.containsKey(sessionId)) return;
            if (subscriptions.size() >= MAX_PER_CLIENT || mSubscriptionCount >= MAX_SUBSCRIPTIONS)
                throw error(ControlCore.Error.LIMIT_REACHED);
            Shared shared = mSources.get(sessionId);
            if (shared == null) {
                Source source = mBackend.open(sessionId);
                if (source == null) throw error(ControlCore.Error.UNAVAILABLE);
                if (source.buffer() == null) {
                    source.close();
                    throw error(ControlCore.Error.UNAVAILABLE);
                }
                shared = new Shared(source);
                mSources.put(sessionId, shared);
            }
            shared.refs++;
            subscriptions.put(sessionId, shared);
            mSubscriptionCount++;
        }

        public OutputReplayBuffer.ReadResult read(String sessionId, long offset, int limit) throws ControlCore.ControlException {
            checkAuthorized();
            Shared shared = subscriptions.get(sessionId);
            if (shared == null) throw error(ControlCore.Error.NOT_FOUND);
            try { return shared.source.buffer().read(offset, limit); }
            catch (IllegalArgumentException e) { throw error(ControlCore.Error.INVALID_REQUEST); }
            catch (IllegalStateException e) { throw error(ControlCore.Error.NOT_RUNNING); }
        }

        public void unsubscribe(String sessionId) throws ControlCore.ControlException {
            checkAuthorized();
            Shared shared = subscriptions.remove(sessionId);
            if (shared == null) throw error(ControlCore.Error.NOT_FOUND);
            release(sessionId, shared);
        }

        private void checkAuthorized() throws ControlCore.ControlException {
            checkThread();
            if (closed || mClosed) throw error(ControlCore.Error.UNAVAILABLE);
            if (!scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT)) throw error(ControlCore.Error.DENIED);
        }

        /** Called on disconnect, even if scopes have been revoked. */
        @Override public void close() {
            checkThread();
            if (closed) return;
            closed = true;
            for (Map.Entry<String, Shared> entry : subscriptions.entrySet()) release(entry.getKey(), entry.getValue());
            subscriptions.clear();
            mClients.remove(this);
        }
    }

    private void release(String sessionId, Shared shared) {
        mSubscriptionCount--;
        if (--shared.refs == 0) {
            mSources.remove(sessionId);
            shared.source.close();
        }
    }

    @Override public void close() {
        checkThread();
        if (mClosed) return;
        for (Client client : new HashSet<>(mClients)) client.close();
        mClosed = true;
    }

    private void checkThread() {
        if (!mThreadCheck.isServiceThread()) throw new IllegalStateException("Must run on service thread");
    }
    private static ControlCore.ControlException error(ControlCore.Error code) { return new ControlCore.ControlException(code); }
}
