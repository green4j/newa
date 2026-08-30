package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The one way a handler sets a header on its response, and the two rules that make it safe to hand out: the
 * framework writes its own headers last, and a response the handler never sent carries none of them.
 */
class ResponseHeadersTest {
    private static final AsciiString OCTET_STREAM = AsciiString.cached("application/octet-stream");
    private static final AsciiString DOWNLOAD = ContentDisposition.attachment("rows.json.gz");

    /** Ends after one step: these tests are about the head, not the body. */
    private static final class OneStepCursor implements ChunkedRestHandle.Cursor {
        @Override
        public boolean writeNext(final ByteBuf output) {
            output.writeBytes("payload".getBytes(StandardCharsets.US_ASCII));
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static RestApi apiWith(final RestHandle handler) {
        final RestApiBuilder builder = new RestApiBuilder(
                "headers-test",
                "response header tests",
                1,
                "test-build"
        );
        builder.get("/thing", handler);
        return builder.build();
    }

    /**
     * @param handler to serve {@code /v1/thing} with
     * @return the head of the response it produced
     */
    private static HttpResponse headOf(final RestHandle handler) {
        final EmbeddedChannel channel = new EmbeddedChannel(
                new RestApiHandler(
                        apiWith(handler),
                        new JsonErrorHandler(),
                        (ch, cause) -> { }
                )
        );
        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/v1/thing"
            ));

            HttpResponse head = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (head == null && outbound instanceof HttpResponse) {
                    head = (HttpResponse) outbound;
                    // kept past the release below: only its headers are read, and those are plain objects
                }
                ReferenceCountUtil.release(outbound);
            }
            return head;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static String dispositionOf(final RestHandle handler) {
        return headOf(handler).headers().get(HttpHeaderNames.CONTENT_DISPOSITION);
    }

    @Test
    public void testAResponseBuiltByTheHandlerCarriesThem() {
        Assertions.assertEquals(
                DOWNLOAD.toString(),
                dispositionOf((context, result) -> {
                    context.responseHeaders().set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
                    result.ok(OCTET_STREAM, "payload".getBytes(StandardCharsets.US_ASCII), 0, 7);
                }));
    }

    @Test
    public void testAResponseWithNoContentCarriesThemToo() {
        Assertions.assertEquals(
                DOWNLOAD.toString(),
                dispositionOf((context, result) -> {
                    context.responseHeaders().set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
                    result.ok();
                }),
                "an empty response is still the handler's response");
    }

    @Test
    public void testAResponsePulledFromACursorCarriesThemAsWell() {
        Assertions.assertEquals(
                DOWNLOAD.toString(),
                dispositionOf(new ChunkedRestHandler(OCTET_STREAM, context -> {
                    context.responseHeaders().set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
                    return new OneStepCursor();
                })),
                "the pre-built handlers never hand out their result, which is the point of the context");
    }

    @Test
    public void testAHandlerCannotSayHowItsResponseIsFramed() {
        final HttpResponse head = headOf((context, result) -> {
            context.responseHeaders()
                    .set(HttpHeaderNames.CONTENT_LENGTH, 9999)
                    .set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                    .set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
            result.ok(OCTET_STREAM, "payload".getBytes(StandardCharsets.US_ASCII), 0, 7);
        });

        Assertions.assertEquals("7", head.headers().get(HttpHeaderNames.CONTENT_LENGTH),
                "a handler must not be able to lie about how long its own response is");
        Assertions.assertNull(head.headers().get(HttpHeaderNames.CONNECTION),
                "nor claim the connection closes while the server keeps it open");
        Assertions.assertNull(head.headers().get(HttpHeaderNames.TRANSFER_ENCODING),
                "nor claim a framing the response does not use");
        Assertions.assertEquals(DOWNLOAD.toString(),
                head.headers().get(HttpHeaderNames.CONTENT_DISPOSITION),
                "everything which is not framing still goes through");
    }

    @Test
    public void testAChunkedResponseCannotBeGivenALengthEither() {
        final HttpResponse head = headOf(new ChunkedRestHandler(OCTET_STREAM, context -> {
            context.responseHeaders()
                    .set(HttpHeaderNames.CONTENT_LENGTH, 9999)
                    .set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
            return new OneStepCursor();
        }));

        Assertions.assertNull(head.headers().get(HttpHeaderNames.CONTENT_LENGTH),
                "a length next to chunked transfer encoding is not merely wrong, it is ambiguous framing");
        Assertions.assertEquals(HttpHeaderValues.CHUNKED.toString(),
                head.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        Assertions.assertEquals(DOWNLOAD.toString(),
                head.headers().get(HttpHeaderNames.CONTENT_DISPOSITION));
    }

    @Test
    public void testAHeaderWhichIsAllowedMoreThanOneValueKeepsThemAll() {
        final HttpResponse head = headOf((context, result) -> {
            context.responseHeaders()
                    .add(HttpHeaderNames.SET_COOKIE, "a=1")
                    .add(HttpHeaderNames.SET_COOKIE, "b=2");
            result.ok();
        });

        Assertions.assertEquals(
                List.of("a=1", "b=2"),
                head.headers().getAll(HttpHeaderNames.SET_COOKIE),
                "set() alone could never have sent both");
    }

    @Test
    public void testAResponseTheHandlerNeverSentCarriesNone() {
        final HttpResponse head = headOf((context, result) -> {
            context.responseHeaders().set(HttpHeaderNames.CONTENT_DISPOSITION, DOWNLOAD);
            throw new BadRequestException("not today");
        });

        Assertions.assertEquals(400, head.status().code());
        Assertions.assertNull(head.headers().get(HttpHeaderNames.CONTENT_DISPOSITION),
                "these belonged to a response which was never written");
    }

    @Test
    public void testAFileNameWhichWouldNotSurviveTheHeaderIsRefusedWhereItIsBuilt() {
        for (final String name : new String[] {"", "отчёт.gz", "rows\r\nX-Injected: 1.gz"}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> ContentDisposition.attachment(name),
                    "a file name of \"" + name + "\" must not reach a response head");
        }
    }

    @Test
    public void testAQuoteInTheFileNameCannotEndTheQuotedString() {
        Assertions.assertEquals(
                "attachment; filename=\"a\\\"b\\\\c.bin\"",
                ContentDisposition.attachment("a\"b\\c.bin").toString());
    }
}
