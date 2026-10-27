package com.termux.app.control.ipc;

import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RpcRequestParserTest extends TestCase {
    private final RpcRequestParser parser = new RpcRequestParser();

    private RpcRequestParser.Request parse(String value) throws Exception {
        return parser.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(String value) throws Exception {
        try { parse(value); fail("Expected invalid request"); }
        catch (RpcRequestParser.InvalidRequest expected) { }
    }

    public void testRequestAndNotification() throws Exception {
        RpcRequestParser.Request request = parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"session.list\",\"params\":{}}");
        assertTrue(request.hasId);
        assertEquals("7", request.id.toString());
        assertEquals("session.list", request.method);
        assertTrue(request.params instanceof Map);
        RpcRequestParser.Request notification = parse("{\"jsonrpc\":\"2.0\",\"method\":\"events.unsubscribe\"}");
        assertFalse(notification.hasId);
    }

    public void testRejectsMalformedAndAmbiguousRequests() throws Exception {
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"session.list\",\"method\":\"session.close\"}");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":{\"a\":1,\"a\":2}}");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"x\"} trailing");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"id\":true}");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":null}");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"rpc.internal\"}");
        reject("{\"jsonrpc\":\"2.0\",\"method\":\"\\ud800\"}");
        reject("[{\"jsonrpc\":\"2.0\",\"method\":\"x\"}]");
        try { parser.parse(new byte[]{(byte) 0xc3, 0x28}); fail(); }
        catch (RpcRequestParser.InvalidRequest expected) { }
    }

    public void testDistinguishesParseAndInvalidRequestErrors() throws Exception {
        try { parse("{not-json}"); fail(); }
        catch (RpcRequestParser.InvalidRequest e) { assertTrue(e.parseError); }
        try { parse("{\"jsonrpc\":\"2.0\"}"); fail(); }
        catch (RpcRequestParser.InvalidRequest e) { assertFalse(e.parseError); }
    }

    public void testDepthAndByteLimits() throws Exception {
        String nested = "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"params\":" + "[".repeat(10) + "0" + "]".repeat(10) + "}";
        reject(nested);
        try { parser.parse(new byte[RpcRequestParser.MAX_REQUEST_BYTES + 1]); fail(); }
        catch (RpcRequestParser.InvalidRequest expected) { }
    }

    public void testNegotiationIsPerConnectionAndFirstRequestMustBeHello() throws Exception {
        RpcNegotiation first = new RpcNegotiation();
        RpcNegotiation second = new RpcNegotiation();
        try { first.accept(parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"session.list\"}")); fail(); }
        catch (RpcRequestParser.InvalidRequest expected) { }
        assertFalse(first.isReady());
        first.accept(parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}"));
        assertTrue(first.isReady());
        assertFalse(second.isReady());
        first.accept(parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.list\"}"));
        try { second.accept(parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":2}}")); fail(); }
        catch (RpcRequestParser.InvalidRequest expected) { }
        assertFalse(second.isReady());
    }
}
