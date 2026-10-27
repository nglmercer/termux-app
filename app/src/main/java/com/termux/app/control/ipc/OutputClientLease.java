package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import com.termux.app.control.OutputSubscriptions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinates disconnect with a subscription creation that may already be executing. */
final class OutputClientLease implements AutoCloseable {
    private final OutputSubscriptions registry;
    private final ServiceThreadDispatch dispatch;
    private final Set<ControlCore.Scope> scopes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<OutputSubscriptions.Client> client = new AtomicReference<>();

    OutputClientLease(OutputSubscriptions registry, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes) {
        this.registry = registry; this.dispatch = dispatch; this.scopes = scopes;
    }

    /** Called only on the service thread. */
    OutputSubscriptions.Client get() throws ControlCore.ControlException {
        if (scopes == null || !scopes.contains(ControlCore.Scope.SESSIONS_OUTPUT))
            throw new ControlCore.ControlException(ControlCore.Error.DENIED);
        if (closed.get()) throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        OutputSubscriptions.Client result = client.get();
        if (result == null) {
            result = registry.newClient(scopes);
            client.set(result);
        }
        if (closed.get()) {
            result.close();
            throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        }
        return result;
    }

    /** May run on a socket thread; cleanup is posted to the service thread. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        OutputSubscriptions.Client value = client.get();
        if (value != null) dispatch.postCleanup(value::close);
    }
}
