/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonParserListenerAdapter;
import io.github.green4j.jelly.simple.JsonWriterGenerator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link RestContext#jsonBody()}, {@link RestContext#jsonBodyAs(JsonBodyReader)} and
 * {@link RestContext#jsonBody(io.github.green4j.jelly.JsonParserListener)}: the body is read as UTF-8 right
 * from its buffer, however the aggregator has put it together, a body which is not JSON or has not got a
 * value the reader requires is a {@code 400} saying why, and a body read after the handler has returned is
 * refused.
 */
class JsonBodyTest {
    private EmbeddedChannel channel;
    private final AtomicReference<String> late = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        final RestApiBuilder builder = new RestApiBuilder("json-body-test", "jsonBody", 1, "test-build");
        builder.postJson("/echo", (context, output) ->
                context.jsonBody().toJson(new JsonWriterGenerator(output)));
        builder.postJson("/count", (context, output) -> {
            final int[] values = new int[1];
            context.jsonBody(new JsonParserListenerAdapter() {
                @Override
                public boolean onStringValue(final CharSequence data) {
                    values[0]++;
                    return true;
                }

                @Override
                public boolean onTrueValue() {
                    values[0]++;
                    return true;
                }

                @Override
                public boolean onFalseValue() {
                    throw new IllegalArgumentException("Refused by the listener");
                }
            });
            output.numberValue(values[0]);
        });
        builder.postJson("/name", (context, output) ->
                output.stringValue(context.jsonBodyAs(body -> body.asObjectRequired().getStringRequired("name"))));
        builder.post("/third", (context, result) -> {
            final byte[] third = Long.toString(context.jsonBodyAs(body -> body.asArrayRequired().getLongRequired(2)))
                    .getBytes(StandardCharsets.US_ASCII);
            result.ok(HttpHeaderValues.TEXT_PLAIN, third, 0, third.length);
        });
        builder.postJson("/tree", (context, output) ->
                output.stringValue(context.jsonBody().asObjectRequired().getStringRequired("name")));
        builder.post("/late", (context, result) -> context.executor().execute(() -> {
            try {
                context.jsonBody();
                late.set("read");
            } catch (final IllegalStateException refused) {
                late.set(refused.getMessage());
            } catch (final HttpException unexpected) {
                late.set(unexpected.toString());
            }
            result.ok(HttpHeaderValues.TEXT_PLAIN, new byte[0], 0, 0);
        }));

        channel = new EmbeddedChannel(new RestApiHandler(
                builder.build(),
                new JsonErrorHandler(),
                (ch, cause) -> {
                    throw new AssertionError(cause);
                }
        ));
    }

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
    }

    /**
     * What goes in comes out byte for byte, a character of four bytes included: the body is decoded right from
     * its bytes, and the generator encodes a surrogate pair as the one character it is rather than as two.
     *
     * @param inSingleBytes whether the body is a composite of single bytes rather than one array.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBodyIsReadAsUtf8WhateverBufferItIsIn(final boolean inSingleBytes) {
        final String document = "{\"name\":\"\u00e9\u20ac\ud83d\ude00\",\"list\":[1,true,null,\"\u0436\"],"
                + "\"nested\":{\"a\":[]}}";
        final byte[] bytes = document.getBytes(StandardCharsets.UTF_8);

        final FullHttpResponse response = post("/v1/echo", inSingleBytes ? singleBytes(bytes) : heap(bytes));
        try {
            Assertions.assertEquals(200, response.status().code());
            final byte[] answered = new byte[response.content().readableBytes()];
            response.content().getBytes(response.content().readerIndex(), answered);
            Assertions.assertArrayEquals(bytes, answered, new String(answered, StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    @ParameterizedTest(name = "''{0}''")
    @CsvSource(delimiter = '|', value = {
        "''          | No JSON in the request body",
        "'  '        | No JSON in the request body",
        "'{\"a\":'   | Malformed JSON in the request body",
        "'[1 2]'     | Malformed JSON in the request body: An error 'Unexpected number' at position: 3",
        "'{\"a\":x}' | Malformed JSON in the request body: An error 'Unexpected char' at position: 5"
    })
    void aBodyWhichIsNotJsonIsABadRequest(final String body,
                                          final String message) {
        assertBadRequest(post("/v1/echo", heap(body.getBytes(StandardCharsets.UTF_8))), message);
    }

    @Test
    void aBodyWhichIsNotUtf8IsABadRequest() {
        assertBadRequest(post("/v1/echo", heap(new byte[] {'"', (byte) 0xc0, (byte) 0x80, '"'})),
                "Malformed JSON in the request body");
    }

    /**
     * A value the reader requires and the document has not got is the client's mistake rather than the
     * server's, whether the handler renders JSON or answers by itself: a {@code 400} naming what is missed, and
     * what is there instead.
     *
     * @param uri of the handler
     * @param body of the request
     * @param status answered
     * @param answer what the response says, or contains for a {@code 400}
     */
    @ParameterizedTest(name = "{0} ''{1}'' -> {2}")
    @CsvSource(delimiter = '|', value = {
        "/v1/name  | '{\"name\":\"x\"}' | 200 | '\"x\"'",
        "/v1/name  | '{\"name\":1}'     | 400 | Required string member 'name' is a number",
        "/v1/name  | '{}'               | 400 | Required string member 'name' missed",
        "/v1/name  | '[]'               | 400 | Required object is an array",
        "/v1/third | '[1,2,3]'          | 200 | 3",
        "/v1/third | '[1,2]'            | 400 | Required long item 2 missed",
        "/v1/third | '[1,2,\"3\"]'      | 400 | Required long item 2 is a string",
        "/v1/third | '[1,2,3.5]'        | 400 | Required long item 2 is a fraction"
    })
    void aValueWhichIsRequiredAndMissedIsABadRequest(final String uri,
                                                     final String body,
                                                     final int status,
                                                     final String answer) {
        final FullHttpResponse response = post(uri, heap(body.getBytes(StandardCharsets.UTF_8)));
        try {
            Assertions.assertEquals(status, response.status().code());
            final String answered = response.content().toString(StandardCharsets.UTF_8);
            if (status == 200) {
                Assertions.assertEquals(answer, answered);
            } else {
                Assertions.assertTrue(answered.contains(answer), answered);
            }
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    /**
     * Outside a reader a value which is missed says nothing about who missed it, so it is a failure like any
     * other, and nothing of it is said.
     */
    @Test
    void aValueWhichIsRequiredAndMissedOutsideAReaderIsAFailure() {
        final FullHttpResponse response = post("/v1/tree", heap("{}".getBytes(StandardCharsets.UTF_8)));
        try {
            Assertions.assertEquals(500, response.status().code());
            final String answered = response.content().toString(StandardCharsets.UTF_8);
            Assertions.assertFalse(answered.contains("name"), answered);
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    @ParameterizedTest(name = "''{0}'' -> {1}")
    @CsvSource(delimiter = '|', value = {
        "'[\"a\",true,{\"b\":\"c\"}]' | 200 | 3",
        "''                           | 200 | 0", // a document with no value is the listener's to judge
        "'[\"a\",'                    | 400 | ",
        "'[false]'                    | 500 | " // what the listener throws is not the client's mistake
    })
    void aBodyIsReadIntoAListener(final String body,
                                  final int status,
                                  final String values) {
        final FullHttpResponse response = post("/v1/count", heap(body.getBytes(StandardCharsets.UTF_8)));
        try {
            Assertions.assertEquals(status, response.status().code());
            if (values != null) {
                Assertions.assertEquals(values, response.content().toString(StandardCharsets.UTF_8));
            }
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    @Test
    void aBodyCanNotBeReadAfterTheHandlerHasReturned() {
        ReferenceCountUtil.release(post("/v1/late", heap("{}".getBytes(StandardCharsets.UTF_8))));
        Assertions.assertTrue(late.get().startsWith("The handler has returned"), late.get());
    }

    private FullHttpResponse post(final String uri,
                                  final ByteBuf body) {
        channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri, body));
        channel.runPendingTasks();
        final FullHttpResponse response = channel.readOutbound();
        Assertions.assertNotNull(response);
        return response;
    }

    private static void assertBadRequest(final FullHttpResponse response,
                                         final String message) {
        try {
            Assertions.assertEquals(400, response.status().code());
            final String answered = response.content().toString(StandardCharsets.UTF_8);
            Assertions.assertTrue(answered.contains(message), answered);
        } finally {
            ReferenceCountUtil.release(response);
        }
    }

    private static ByteBuf heap(final byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes);
    }

    // what the aggregator makes of a body which came in many reads: a composite, here one of single bytes
    private static ByteBuf singleBytes(final byte[] bytes) {
        final CompositeByteBuf composite = Unpooled.compositeBuffer(Integer.MAX_VALUE);
        for (int i = 0; i < bytes.length; i++) {
            composite.addComponent(true, Unpooled.directBuffer(1).writeByte(bytes[i]));
        }
        return composite;
    }
}
