package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import java.math.BigDecimal;
import java.util.Map;

/** Per-connection handshake state. A transport must still authenticate and authorize the peer. */
public final class RpcNegotiation {
    private boolean mReady;

    public void accept(RpcRequestParser.Request request) throws RpcRequestParser.InvalidRequest {
        if (!mReady) {
            if (!request.hasId || request.id == null || !"system.hello".equals(request.method)
                || !(request.params instanceof Map)) throw new RpcRequestParser.InvalidRequest();
            Map<?, ?> parameters = (Map<?, ?>) request.params;
            if (parameters.size() != 1) throw new RpcRequestParser.InvalidRequest();
            Object version = parameters.get("version");
            if (!(version instanceof BigDecimal)
                || ((BigDecimal) version).compareTo(BigDecimal.valueOf(ControlCore.PROTOCOL_VERSION)) != 0)
                throw new RpcRequestParser.InvalidRequest();
            mReady = true;
        } else if ("system.hello".equals(request.method)) {
            throw new RpcRequestParser.InvalidRequest();
        }
    }

    public boolean isReady() { return mReady; }
}
