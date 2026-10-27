package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import com.termux.app.control.OutputSubscriptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/** Protocol loop for one already accepted connection. The transport supplies verified peer credentials. */
final class RpcConnection {
    interface Running { boolean get(); }
    private final ControlCore mCore;
    private final ServiceThreadDispatch mDispatch;
    private final Set<ControlCore.Scope> mScopes;
    private final Running mRunning;
    private final OutputSubscriptions mOutputRegistry;

    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running) {
        this(core, dispatch, scopes, running, null);
    }

    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running,
                  OutputSubscriptions outputRegistry) {
        mCore = core; mDispatch = dispatch; mScopes = scopes; mRunning = running; mOutputRegistry = outputRegistry;
    }

    /** Peer UID is supplied by the operating system, never by the incoming frame. */
    void serve(int ownerUid, int peerUid, InputStream input, OutputStream output) throws IOException {
        if (ownerUid < 0 || peerUid != ownerUid) return;
        FrameCodec frames = new FrameCodec(RpcRequestParser.MAX_REQUEST_BYTES);
        RpcRequestParser parser = new RpcRequestParser();
        OutputClientLease lease = mOutputRegistry == null ? null : new OutputClientLease(mOutputRegistry, mDispatch, mScopes);
        RpcDispatcher dispatcher = new RpcDispatcher(mCore, lease);
        try {
        for (int i = 0; mRunning.get() && i < 1000; i++) {
            byte[] body = frames.read(input);
            if (body == null) return;
            RpcRequestParser.Request request;
            try { request = parser.parse(body); }
            catch (RpcRequestParser.InvalidRequest e) {
                frames.write(output, RpcDispatcher.invalidRequest(e.parseError));
                return;
            }
            byte[] binary = null;
            if ("session.write".equals(request.method)) {
                RpcDispatcher.BinaryPreparation preparation = dispatcher.prepareBinary(request, mScopes);
                if (preparation.length == 0) {
                    if (preparation.error != null) frames.write(output, preparation.error);
                    return;
                }
                binary = frames.read(input);
                if (binary == null || binary.length != preparation.length) {
                    byte[] error = RpcDispatcher.invalidBinary(request);
                    if (error != null) frames.write(output, error);
                    return;
                }
            }
            final byte[] payload = binary;
            RpcDispatcher.Result response;
            try {
                response = mDispatch.call(() -> !mRunning.get() ? null : dispatcher.dispatchResult(request, mScopes, payload), 3000);
            } catch (ServiceThreadDispatch.DispatchException e) {
                byte[] failure = RpcDispatcher.transportFailure(request, e.reason);
                if (failure != null) frames.write(output, failure);
                return;
            }
            if (response != null && mRunning.get()) {
                if (response.json != null) frames.write(output, response.json);
                if (response.binary != null && mRunning.get()) frames.write(output, response.binary);
            }
            if (!dispatcher.isNegotiated()) return;
        }
        } finally {
            if (lease != null) lease.close();
        }
    }
}
