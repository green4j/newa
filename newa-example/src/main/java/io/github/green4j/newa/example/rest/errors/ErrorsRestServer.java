/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


package io.github.green4j.newa.example.rest.errors;

import io.github.green4j.newa.example.rest.StdOutRestApiObserver;
import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.DefaultFullHttpResponseContent;
import io.github.green4j.newa.rest.HttpErrorHandler;
import io.github.green4j.newa.rest.FullHttpResponseContent;
import io.github.green4j.newa.rest.HttpException;
import io.github.green4j.newa.rest.InternalServerErrorException;
import io.github.green4j.newa.rest.PathNotFoundException;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.handles.JsonHelp;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AsciiString;

import java.nio.charset.StandardCharsets;

/**
 * What happens when a request ends badly, which is two separate things: something is rendered for the client
 * and something is reported for a log. This example does both.
 * <p>
 * The client gets {@link HtmlErrorPages} - one {@link HttpErrorHandler}, one method, a page for every error there
 * is, including one this library never named. The log gets {@link StdOutRestApiObserver}, whose
 * {@code onResponseFailed} is where the exception which caused a {@code 500} is to be found: the page says
 * only the status, which is the point. Ask for {@code /v1/boom} and watch the two halves land in different
 * places.
 * <p>
 * The same {@link HttpErrorHandler} would serve files put in front of the api with {@code withFiles(...)}: a 404
 * from the file server is rendered by whatever renders a 404 from the routing.
 */
public class ErrorsRestServer {
    public static final String API_NAME = "Errors API";
    public static final String API_DESCRIPTION = "An error page of my own, and a log which gets the rest";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9009;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    /**
     * An exception of this application's own, carrying a status this library never named. It reaches the
     * {@link HttpErrorHandler} as it was thrown, so the response is a {@code 409} and the message is rendered:
     * it was written here, by hand, to be read by whoever asked.
     */
    public static final class OutOfStockException extends HttpException {
        private static final long serialVersionUID = 1L;

        public OutOfStockException(final String sku) {
            super(HttpResponseStatus.CONFLICT, "Out of stock: " + sku);
        }
    }

    /**
     * A page for every error there is - one {@link HttpErrorHandler}, one method, and it covers a status this
     * library never named as readily as a {@code 404}.
     * <p>
     * It keeps the rule the default handlers keep, because a custom one which forgets it re-opens the hole:
     * <b>an {@link io.github.green4j.newa.rest.InternalServerErrorException} says only its status</b>. Its
     * message is the failure's {@code toString()} - a type of this process and a path of its file system -
     * and the client has no business with either. Everything else carries a message written by hand, and
     * that is what the page is for.
     */
    private static final class HtmlErrorPages implements HttpErrorHandler {
        private static final AsciiString CONTENT_TYPE = Charset.UTF8.toContentType(
                HttpHeaderValues.TEXT_HTML);

        private static final HttpResponseStatus[] EXPECTED = {
            HttpResponseStatus.BAD_REQUEST,
            HttpResponseStatus.NOT_FOUND,
            HttpResponseStatus.METHOD_NOT_ALLOWED,
            HttpResponseStatus.CONFLICT,
            HttpResponseStatus.INTERNAL_SERVER_ERROR,
            HttpResponseStatus.SERVICE_UNAVAILABLE
        };

        /**
         * The bare page of a status, indexed by it and rendered when the server is assembled: the answer to
         * a failure is the same every time, and that is the one an overloaded server renders most.
         */
        private final byte[][] bare = new byte[600][];

        private HtmlErrorPages() {
            for (int i = 0; i < EXPECTED.length; i++) {
                bare[EXPECTED[i].code()] = page(EXPECTED[i], null);
            }
        }

        @Override
        public FullHttpResponseContent handle(final HttpException error) {
            final HttpResponseStatus status = error.status();

            final String message;
            if (error instanceof InternalServerErrorException) {
                message = null;                                        // a failure says only its status
            } else if (error instanceof PathNotFoundException) {
                message = ((PathNotFoundException) error).path();       // what was asked for and is not here
            } else {
                message = error.getMessage();
            }

            final byte[] page = message == null ? bareOf(status) : page(status, message);

            return new DefaultFullHttpResponseContent(CONTENT_TYPE, page, 0, page.length);
        }

        private byte[] bareOf(final HttpResponseStatus status) {
            final int code = status.code();
            final byte[] ready = code > -1 && code < bare.length ? bare[code] : null;
            return ready != null ? ready : page(status, null);
        }

        /**
         * @param status of the response
         * @param message to render into it, or null for the bare page
         * @return the page, rendered
         */
        private static byte[] page(final HttpResponseStatus status,
                                   final String message) {
            final StringBuilder html = new StringBuilder(160);
            html.append("<!doctype html><html><head><title>").append(status.code())
                    .append("</title></head><body><h1>").append(status.code())
                    .append("</h1><p>").append(status.reasonPhrase()).append("</p>");
            if (message != null) {
                html.append("<p>");
                escaped(message, html);
                html.append("</p>");
            }
            html.append("</body></html>");
            return html.toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * A {@code 404} carries the path the request asked for, so a message can be the client\'s own text
         * coming back. Into markup it goes escaped, or the client has written the page.
         *
         * @param text to escape
         * @param to append it to
         */
        private static void escaped(final String text,
                                    final StringBuilder to) {
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                switch (c) {
                    case '&': to.append("&amp;"); break;
                    case '<': to.append("&lt;"); break;
                    case '>': to.append("&gt;"); break;
                    case '"': to.append("&quot;"); break;
                    case '\'': to.append("&#39;"); break;
                    default: to.append(c); break;
                }
            }
        }
    }

    public static void main(final String[] args) throws Exception {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        // 409, from an exception of this application's own
        apiBuilder.getJson("/stock/{sku}",
                (context, output) -> {
                    final String sku = context.pathParameters().valueRequired("sku");
                    if ("A-17".equals(sku)) {
                        throw new OutOfStockException(sku);
                    }
                    output.startObject();
                    output.objectMember("sku");
                    output.stringValue(sku, true);
                    output.objectMember("inStock");
                    output.trueValue();
                    output.endObject();
                }
        ).withPathParameterDescriptions("sku - Ask for A-17 to be refused");

        // 400, from the framework: a required parameter which is not there is a malformed request, and the
        // message says which one
        apiBuilder.getJson("/order",
                (context, output) ->
                        output.stringValue("Ordered " + context.queryParameters().valueRequired("sku"))
        );

        // 500. Nothing of this reaches the client: not the message, which names a file of the server, not the
        // class, not the stack trace. It reaches the observer instead, whole
        apiBuilder.getJson("/boom",
                (context, output) -> {
                    throw new IllegalStateException("Failed to read /etc/secret/db.conf");
                }
        );

        final RestApi api = apiBuilder.buildWithHelp(JsonHelp.factory());

        new Life().run(() -> {
            final NettyServer server = RestServer.of(api)
                    .withErrorHandler(new HtmlErrorPages())
                    // for a development machine, and only there: it answers a failure with its class, its
                    // message, its stack trace and every cause
                    // .withErrorHandler(JsonErrorHandler.disclosingInternals())
                    .withObservers(StdOutRestApiObserver.factory())
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started and listening on %s. Try:%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i %s/v1/stock/B-2      -> 200%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i %s/v1/stock/A-17     -> 409, the message of your own exception%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i %s/v1/order          -> 400, and it names the parameter%n",
                    LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i %s/v1/nowhere        -> 404%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i -X POST %s/v1/boom   -> 405%n", LOCAL_SERVER_ADDRESS);
            System.out.printf("  curl -i %s/v1/boom           -> 500, and the cause is printed here%n",
                    LOCAL_SERVER_ADDRESS);

            return server;
        });

        System.out.println("Server stopped");
    }
}
