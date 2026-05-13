package io.github.green4j.newa.websocket;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WsApiBuilderTest {

    @Test
    public void testDefaultPathPrefix() {
        final WsApi api = new SimpleWsApiBuilder(1)
                .build();
        Assertions.assertEquals("/websocket/v1",
                api.websocketPath());
    }

    @Test
    public void testCustomPathPrefix() {
        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix("ws")
                .build();
        Assertions.assertEquals("/ws/v1",
                api.websocketPath());
    }

    @Test
    public void testPathPrefixWithLeadingSlash() {
        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix("/ws")
                .build();
        Assertions.assertEquals("/ws/v1",
                api.websocketPath());
    }

    @Test
    public void testDifferentVersion() {
        final WsApi api = new SimpleWsApiBuilder(3)
                .withPathPrefix("ws")
                .build();
        Assertions.assertEquals("/ws/v3",
                api.websocketPath());
    }

    @Test
    public void testNullPathPrefix() {
        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix(null)
                .build();
        Assertions.assertEquals("/v1",
                api.websocketPath());
    }

    @Test
    public void testEmptyPathPrefix() {
        final WsApi api = new SimpleWsApiBuilder(1)
                .withPathPrefix("")
                .build();
        Assertions.assertEquals("/v1",
                api.websocketPath());
    }
}
