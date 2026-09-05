/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WsApiBuilderTest {

    @Test
    public void testKeepAliveIsOnByDefault() {
        final WsApi api = new WsApiBuilder(1).build();

        Assertions.assertEquals(AbstractWsApiBuilder.DEFAULT_PING_INTERVAL_MS, api.pingIntervalMs());
        Assertions.assertEquals(AbstractWsApiBuilder.DEFAULT_READ_TIMEOUT_MS, api.readTimeoutMs());
    }

    @Test
    public void testKeepAliveCanBeTurnedOff() {
        final WsApi api = new WsApiBuilder(1)
                .withPingIntervalMs(0)
                .withReadTimeoutMs(0)
                .build();

        Assertions.assertEquals(0, api.pingIntervalMs());
        Assertions.assertEquals(0, api.readTimeoutMs());
    }

    /**
     * @param version  of the api, which is the last segment whatever the prefix is.
     * @param prefix    asked for, where NONE is none asked for at all and DEFAULT is never asking.
     * @param expected  the path the api answers on.
     */
    @ParameterizedTest(name = "version {0}, prefix [{1}] -> {2}")
    @CsvSource(nullValues = "NONE", value = {
        "1, DEFAULT, /websocket/v1", // nothing asked for is the documented default
        "1, ws,      /ws/v1",
        "1, /ws,     /ws/v1",        // a leading slash is not doubled
        "1, NONE,    /v1",           // and no prefix at all leaves the version alone
        "1, '',      /v1",
        "3, ws,      /ws/v3"
    })
    public void websocketPath(final int version,
                              final String prefix,
                              final String expected) {
        final WsApiBuilder builder = new WsApiBuilder(version);
        if (!"DEFAULT".equals(prefix)) {
            builder.withPathPrefix(prefix);
        }

        Assertions.assertEquals(expected, builder.build().websocketPath(), String.valueOf(prefix));
    }
}
