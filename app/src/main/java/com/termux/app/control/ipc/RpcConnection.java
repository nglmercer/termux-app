package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
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

    RpcConnection(ControlCore core, ServiceThreadDispatch dispatch, Set<ControlCore.Scope> scopes, Running running) {
        mCore = core; mDispatch = dispatch; mScopes = scopes; mRunning = running;
    }

    /** Peer UID is supplied by the operating system, never by the incoming frame. */
    void serve(int ownerUid, int peerUid, InputStream input, OutputStream output) throws IOException {
        if (ownerUid < 0 || peerUid != ownerUid) return;
        FrameCodec frames = new FrameCodec(RpcRequestParser.MAX_REQUEST_BYTES);
        RpcRequestParser parser = new RpcRequestParser();
        RpcDispatcher dispatcher = new RpcDispatcher(mCore);
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
            byte[] response;
            try {
                response = mDispatch.call(() -> !mRunning.get() ? null : payload == null
                    ? dispatcher.dispatch(request, mScopes) : dispatcher.dispatchBinary(request, mScopes, payload), 3000);
            } catch (ServiceThreadDispatch.DispatchException e) {
                response = RpcDispatcher.transportFailure(request, e.reason);
                if (response != null) frames.write(output, response);
                return;
            }
            if (response != null) frames.write(output, response);
            if (!dispatcher.isNegotiated()) return;
        }
    }
}
