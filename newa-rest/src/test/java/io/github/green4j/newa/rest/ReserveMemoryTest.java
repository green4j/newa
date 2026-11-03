/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.ServerMemoryBudget;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link RestContext#reserveMemory(long, long)}: a request which does not fit its server's budget is answered
 * {@code 503}, one which fits holds its bytes while it runs, and a server without a budget reserves nothing.
 */
class ReserveMemoryTest {
    @ParameterizedTest(name = "budgeted={0}, heap={1} -> {2}")
    @CsvSource({
            "false, 9223372036854775807, 200",
            "true,  1,                   200",
            "true,  9223372036854775807, 503"
    })
    void aRequestIsAnsweredByWhatItsBudgetHasRoomFor(final boolean budgeted,
                                                     final long heapBytes,
                                                     final int status) {
        final ServerMemoryBudget budget = budgeted ? ServerMemoryBudget.builder().build() : null;
        final AtomicLong heldWhileRunning = new AtomicLong(-1);

        final RestApiBuilder builder = new RestApiBuilder("reserve-test", "reserveMemory", 1, "test-build");
        builder.get("/work", (context, result) -> {
            try (ServerMemoryBudget.Lease work = context.reserveMemory(heapBytes, 0)) {
                if (budget != null) {
                    heldWhileRunning.set(budget.snapshot().reservedHeapBytes());
                }
                result.ok();
            }
        });

        final EmbeddedChannel channel = new EmbeddedChannel(new RestApiHandler(
                builder.build(),
                new JsonErrorHandler(),
                (ch, cause) -> {
                    throw new AssertionError(cause);
                },
                ResponseChunks.defaults(),
                null,
                budget
        ));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1/work"));
            final FullHttpResponse response = channel.readOutbound();
            try {
                Assertions.assertEquals(status, response.status().code());
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            channel.finishAndReleaseAll();
        }

        if (budget != null) {
            Assertions.assertEquals(status == 200 ? heapBytes : -1, heldWhileRunning.get());
            Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
        }
    }
}
