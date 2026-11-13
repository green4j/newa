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
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RestContext#reserveMemory(long, long)}, {@link RestContext#reserveHeap(long)} and
 * {@link RestContext#reserveDirectMemory(long)}: a request which does not fit its server's budget is answered
 * {@code 503}, one which fits holds its bytes while it runs, and a server without a budget reserves nothing.
 */
class ReserveMemoryTest {
    @ParameterizedTest(name = "budgeted={0}, {1}({2}) -> {3}")
    @CsvSource({
            "false, memory, 9223372036854775807, 200",
            "true,  memory, 1,                   200",
            "true,  memory, 9223372036854775807, 503",
            "false, heap,   9223372036854775807, 200",
            "true,  heap,   1,                   200",
            "true,  heap,   9223372036854775807, 503",
            "false, direct, 9223372036854775807, 200",
            "true,  direct, 1,                   200",
            "true,  direct, 9223372036854775807, 503"
    })
    void aRequestIsAnsweredByWhatItsBudgetHasRoomFor(final boolean budgeted,
                                                     final String method,
                                                     final long bytes,
                                                     final int status) {
        final long heapBytes = method.equals("direct") ? 0 : bytes;
        final long directMemoryBytes = method.equals("heap") ? 0 : bytes;
        final ServerMemoryBudget budget = budgeted ? ServerMemoryBudget.builder().build() : null;
        final AtomicLong heapHeldWhileRunning = new AtomicLong(-1);
        final AtomicLong directHeldWhileRunning = new AtomicLong(-1);
        final AtomicReference<ServerMemoryBudget.Lease> given = new AtomicReference<>();

        final RestApiBuilder builder = new RestApiBuilder("reserve-test", "reserveMemory", 1, "test-build");
        builder.get("/work", (context, result) -> {
            try (ServerMemoryBudget.Lease work = reserve(context, method, bytes)) {
                given.set(work);
                if (budget != null) {
                    heapHeldWhileRunning.set(budget.snapshot().reservedHeapBytes());
                    directHeldWhileRunning.set(budget.snapshot().reservedDirectMemoryBytes());
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
            Assertions.assertEquals(status == 200 ? heapBytes : -1, heapHeldWhileRunning.get());
            Assertions.assertEquals(status == 200 ? directMemoryBytes : -1, directHeldWhileRunning.get());
            Assertions.assertEquals(0, budget.snapshot().reservedHeapBytes());
            Assertions.assertEquals(0, budget.snapshot().reservedDirectMemoryBytes());
        } else {
            Assertions.assertSame(ServerMemoryBudget.Lease.NO_BUDGET, given.get());
        }
    }

    private static ServerMemoryBudget.Lease reserve(final RestContext context,
                                                    final String method,
                                                    final long bytes) throws HttpException {
        switch (method) {
            case "memory":
                return context.reserveMemory(bytes, bytes);
            case "heap":
                return context.reserveHeap(bytes);
            case "direct":
                return context.reserveDirectMemory(bytes);
            default:
                throw new IllegalArgumentException(method);
        }
    }
}
