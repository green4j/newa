/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link RestServer#withHandlingDeadlineMs(int)}: a handle which kept its result and never answered is
 * answered {@code 503} at the deadline, what it says afterwards is dropped without being reported, and a
 * handle which answers in the call is never put on a timer at all.
 */
class HandlingDeadlineTest {
    private static final int DEADLINE_MS = 1_000;

    private final List<Throwable> reported = new ArrayList<>();

    private RestHandle.Result kept;

    private EmbeddedChannel channel;

    private EmbeddedChannel channel(final int handlingDeadlineMs) {
        return channel(handlingDeadlineMs, new JsonErrorHandler());
    }

    private EmbeddedChannel channel(final int handlingDeadlineMs,
                                    final HttpErrorHandler errorHandler) {
        final RestApiBuilder builder = new RestApiBuilder(
                "deadline-test",
                "handling deadline tests",
                1,
                "test-build"
        );
        builder.get("/later", (context, result) -> kept = result);
        builder.getJson("/now", (context, output) -> output.stringValue("now"));

        channel = new EmbeddedChannel(new RestApiHandler(
                builder.build(),
                errorHandler,
                (ch, cause) -> reported.add(cause),
                ResponseChunks.defaults(),
                null,
                null,
                handlingDeadlineMs
        ));
        channel.freezeTime();
        return channel;
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    private void request(final String path) {
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/v1" + path));
    }

    private int answeredStatus() {
        final FullHttpResponse response = channel.readOutbound();
        Assertions.assertNotNull(response, "Nothing was answered");
        try {
            return response.status().code();
        } finally {
            response.release();
        }
    }

    private void advance(final long ms) {
        channel.advanceTimeBy(ms, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    @Test
    void aHandleWhichNeverAnswersIsAnswered503AtTheDeadline() {
        channel(DEADLINE_MS);
        request("/later");

        advance(DEADLINE_MS - 1);
        Assertions.assertNull(channel.readOutbound(), "The request was answered before its deadline");

        advance(1);
        Assertions.assertEquals(503, answeredStatus());
        Assertions.assertTrue(channel.isOpen(), "An expired request closed a keep-alive connection");
    }

    @Test
    void whatAHandleSaysAfterTheDeadlineIsDroppedAndNotReported() {
        channel(DEADLINE_MS);
        request("/later");
        advance(DEADLINE_MS);
        Assertions.assertEquals(503, answeredStatus());

        kept.ok();
        kept.error(new IllegalStateException("Not today"));

        Assertions.assertNull(channel.readOutbound(), "A late answer was written");
        Assertions.assertEquals(List.of(), reported, "A late answer was reported as a mistake");
    }

    @Test
    void anAnswerInTimeStopsTheTimer() {
        channel(DEADLINE_MS);
        request("/later");

        advance(DEADLINE_MS / 2);
        kept.ok();
        Assertions.assertEquals(200, answeredStatus());

        advance(DEADLINE_MS);
        Assertions.assertNull(channel.readOutbound(), "The deadline answered a request already answered");
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(), "The timer outlived the answer");
    }

    @Test
    void aSecondAnswerInTimeIsStillReported() {
        channel(DEADLINE_MS);
        request("/later");
        kept.ok();
        answeredStatus();

        kept.ok();

        Assertions.assertEquals(1, reported.size(), "A handle answering twice was not reported");
    }

    @Test
    void aChannelWhichClosesFirstTakesTheTimerWithIt() {
        channel(DEADLINE_MS);
        request("/later");

        channel.close();
        advance(DEADLINE_MS);

        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(), "The timer outlived the channel");
        kept.ok();
        Assertions.assertNull(channel.readOutbound(), "An answer was written to a closed channel");
        Assertions.assertEquals(List.of(), reported, "A late answer to a closed channel was reported");
    }

    @Test
    void aResponseBegunAndNeverSentGivesWayToThe503() {
        channel(DEADLINE_MS);
        request("/later");
        final byte[] half = {'a', 'b'};
        final RestHandle.Result.Content content = kept.ok(null, 4);
        content.append(half, 0, half.length);

        advance(DEADLINE_MS);
        Assertions.assertEquals(503, answeredStatus());

        content.append(half, 0, half.length).done(); // what it held went back with the 503
        Assertions.assertNull(channel.readOutbound(), "The response begun before the deadline was written");
        Assertions.assertEquals(List.of(), reported, "A late answer was reported as a mistake");
    }

    @Test
    void anErrorHandlerWhichThrowsAtTheDeadlineClosesTheConnection() {
        channel(DEADLINE_MS, error -> {
            throw new IllegalStateException("Not today");
        });
        request("/later");

        advance(DEADLINE_MS);

        Assertions.assertFalse(channel.isOpen(), "A deadline which could not be answered left the connection open");
        Assertions.assertEquals(1, reported.size(), "The failure to answer the deadline was not reported");
        Assertions.assertEquals("Not today", reported.get(0).getMessage());

        kept.ok();
        Assertions.assertEquals(1, reported.size(), "A late answer after a failed deadline was reported");
    }

    @Test
    void aHandleWhichAnswersInTheCallIsNotTimed() {
        channel(DEADLINE_MS);
        request("/now");

        Assertions.assertEquals(200, answeredStatus());
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks(), "A handle which answered was timed");
    }

    @Test
    void withoutADeadlineNothingIsTimed() {
        channel(0);
        request("/later");

        advance(TimeUnit.HOURS.toMillis(1));
        Assertions.assertNull(channel.readOutbound(), "A request was answered with no deadline set");
        Assertions.assertEquals(-1, channel.runScheduledPendingTasks());
    }

    @Test
    void aNegativeDeadlineIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> RestServer.of(new RestApiBuilder("a", "b", 1, "c").build()).withHandlingDeadlineMs(-1));
    }
}
