/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.JsonParserListener;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueException;
import io.github.green4j.newa.json.MalformedJsonException;
import io.github.green4j.newa.json.Utf8JsonReader;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.EventExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything one routed request is: the channel it arrived on, the request itself, the parameters it matched
 * and the headers its response will carry. One per request, handed to the handle which answers it.
 * <p>
 * It is read on the event loop of the channel, and so is everything which finishes the response -
 * {@link #executor()} is the hop back onto that loop for a handle which answers later.
 * <p>
 * Query parameters, form parameters and headers are parsed the first time they are asked for. Two things do
 * not outlive the handler: the body of {@link #request()}, whose buffer goes back to the pool, and
 * {@link #pathParameters()}, a flyweight the next request on this thread writes over. Everything else,
 * {@link #pathExpression()} included, stays valid.
 */
public class RestContext {
    private final ChannelHandlerContext handlerContext;
    private final FullHttpRequest request;
    private final RestHandling handling;
    private final HttpHeaders responseHeaders;
    private final ResponseChunks responseChunks;
    private final RestApiObserver observer;
    private final ServerMemoryBudget memoryBudget;

    private boolean handled;

    private QueryStringDecoder queryStringDecoder;
    private MappedNamedMultiValues queryParameters;
    private MappedNamedMultiValues formParameters;
    private NamedValues headers;

    RestContext(final ChannelHandlerContext handlerContext,
                final FullHttpRequest request,
                final RestHandling handling,
                final HttpHeaders responseHeaders,
                final ResponseChunks responseChunks,
                final RestApiObserver observer,
                final ServerMemoryBudget memoryBudget) {
        this.handlerContext = handlerContext;
        this.request = request;
        this.handling = handling;
        this.responseHeaders = responseHeaders;
        this.responseChunks = responseChunks;
        this.observer = observer;
        this.memoryBudget = memoryBudget;
    }

    /**
     * The handler has returned, so the matcher flyweight the path parameters come from is free to be reused
     * by the next request on this thread.
     */
    void handled() {
        handled = true;
    }

    /**
     * Where a handler puts the headers its response should carry - the one way to do it, whether the
     * response is built here or by one of the pre-built handlers, which never hand out the result they are
     * building:
     * <pre>{@code
     * context.responseHeaders().set(CONTENT_DISPOSITION, ContentDisposition.attachment("rows.json.gz"));
     * }</pre>
     * The framework writes its own headers after these, so nothing set here can break the framing. Nothing
     * set here reaches an error response either: these belong to the response the handler was building, and
     * a handler which failed never sent it.
     *
     * @return the headers of the response being built
     */
    public HttpHeaders responseHeaders() {
        return responseHeaders;
    }

    /**
     * @return the expression the matched endpoint was declared with, {@code /v1/rows/{count}} rather than
     *         {@code /v1/rows/17} - the label a metric wants, and safe to read at any time
     */
    public String pathExpression() {
        return handling.pathExpression();
    }

    /**
     * @return the method the request came in with. Outlives the request
     */
    public HttpMethod method() {
        return request.method();
    }

    /**
     * @return the URI the request came in on. Outlives the request
     */
    public String uri() {
        return request.uri();
    }

    /**
     * @return the chunked response policy this server was built with
     */
    public ResponseChunks responseChunks() {
        return responseChunks;
    }

    /**
     * Reserves memory for the work of this request from the budget its server is admitted from, so that a
     * request which does not fit is answered {@code 503} before the work starts:
     * <pre>{@code
     * try (ServerMemoryBudget.Lease rows = context.reserveMemory(limit * rowBytes, 0)) {
     *     // render the rows
     * }
     * }</pre>
     * The sizes are the handle's forecast, and nothing limits what the work then allocates. The lease is
     * closed when the work has ended, which for a handle answering later is not when it returns. A lease
     * which is never closed keeps its bytes for the life of the budget.
     *
     * @param heapBytes estimated heap the work holds
     * @param directMemoryBytes estimated direct memory the work holds
     * @return the lease to close, {@link ServerMemoryBudget.Lease#NO_BUDGET} when the server has no budget
     * @throws HttpException {@code 503} when the budget has no room for it
     */
    public ServerMemoryBudget.Lease reserveMemory(final long heapBytes,
                                                  final long directMemoryBytes) throws HttpException {
        if (memoryBudget == null) {
            return ServerMemoryBudget.Lease.NO_BUDGET;
        }
        final ServerMemoryBudget.Lease lease = memoryBudget.tryReserve(heapBytes, directMemoryBytes);
        if (lease == null) {
            throw new HttpException(
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "No memory left in the budget for this request"
            );
        }
        return lease;
    }

    /**
     * {@link #reserveMemory(long, long)} for work which holds heap only; the direct-memory capacity is not
     * consulted.
     *
     * @param heapBytes estimated heap the work holds
     * @return the lease to close, {@link ServerMemoryBudget.Lease#NO_BUDGET} when the server has no budget
     * @throws HttpException {@code 503} when the heap capacity has no room for it
     */
    public ServerMemoryBudget.Lease reserveHeap(final long heapBytes) throws HttpException {
        return reserveMemory(heapBytes, 0);
    }

    /**
     * {@link #reserveMemory(long, long)} for work which holds direct memory only; the heap capacity is not
     * consulted.
     *
     * @param directMemoryBytes estimated direct memory the work holds
     * @return the lease to close, {@link ServerMemoryBudget.Lease#NO_BUDGET} when the server has no budget
     * @throws HttpException {@code 503} when the direct-memory capacity has no room for it
     */
    public ServerMemoryBudget.Lease reserveDirectMemory(final long directMemoryBytes) throws HttpException {
        return reserveMemory(0, directMemoryBytes);
    }

    /**
     * @return what this request is reported to, or null if it is not observed, or observed only as far as
     *         {@link HttpObserver} goes
     */
    public RestApiObserver observer() {
        return observer;
    }

    public Channel channel() {
        return handlerContext.channel();
    }

    public EventExecutor executor() {
        return handlerContext.executor();
    }

    /**
     * @return the request. Its body is only there until the handler returns - after that the buffer has gone
     *         back to the pool, and reading it throws
     *         {@link io.netty.util.IllegalReferenceCountException}
     */
    public FullHttpRequest request() {
        return request;
    }

    /**
     * @return the path parameters of this request
     * @throws IllegalStateException if the handler has already returned - the flyweight these live in
     *                               belongs to the next request by then, and answering with somebody else's
     *                               values is what this refusal prevents
     */
    public NamedValues pathParameters() {
        if (handled) {
            throw new IllegalStateException("The handler has returned, so these path parameters now belong "
                    + "to whatever request comes next on this thread. Read pathExpression() instead");
        }
        return handling.pathParameters();
    }

    public NamedMultiValues queryParameters() {
        if (queryParameters != null) {
            return queryParameters;
        }
        updateQueryStringDecoder();
        queryParameters = new MappedNamedMultiValues(
                queryStringDecoder.parameters());
        return queryParameters;
    }

    /**
     * Reads the body of the request as a JSON document in UTF-8, right from the bytes it came in, with no
     * {@link String} of it made first - see {@link Utf8JsonReader}. Nothing is kept: every call reads the body
     * again.
     * <p>
     * A {@code ...Required} accessor of the value which does not find what it asks for throws a
     * {@code JsonValueException}, which is a failure like any other here and is answered {@code 500}: it is
     * {@link #jsonBodyAs(JsonBodyReader)} which answers it {@code 400}.
     *
     * @return the value of the body
     * @throws BadRequestException {@code 400} if the body is not a JSON document, or has no value in it: no
     *                             bytes, or whitespace only
     * @throws IllegalStateException if the handler has already returned - the body has gone back to the pool
     *                               by then
     */
    public JsonValue jsonBody() throws BadRequestException {
        final JsonValue value;
        try {
            value = Utf8JsonReader.value(jsonBodyBytes());
        } catch (final MalformedJsonException malformed) {
            throw new BadRequestException("Malformed JSON in the request body: " + malformed.getMessage());
        }
        if (value == null) {
            throw new BadRequestException("No JSON in the request body");
        }
        return value;
    }

    /**
     * Reads the body of the request the way {@link #jsonBody()} does, and makes of its value what the reader
     * says. A {@code JsonValueException} the reader throws - a {@code ...Required} accessor which does not find
     * what it asks for - is the client's mistake, and is answered {@code 400} with its message, which names the
     * member or the item and says what is there instead.
     *
     * @param reader what makes of the value what the handler needs
     * @param <T> what the body is read as
     * @return what the reader has made of the value
     * @throws BadRequestException {@code 400} as {@link #jsonBody()} throws it, or for a value the body has not
     *                             got
     * @throws HttpException what the reader throws
     * @throws IllegalStateException if the handler has already returned - the body has gone back to the pool
     *                               by then
     */
    public <T> T jsonBodyAs(final JsonBodyReader<T> reader) throws HttpException {
        final JsonValue body = jsonBody();
        try {
            return reader.read(body);
        } catch (final JsonValueException missed) {
            throw new BadRequestException(missed.getMessage());
        }
    }

    /**
     * Reads the body of the request as a JSON document in UTF-8 into the listener given, the way
     * {@link #jsonBody()} reads it into a tree, and with no tree allocated at all. An empty body is not
     * refused here: the listener is told a document has started and ended with no value in it, and what that
     * means is the listener's to say. What the listener throws is thrown from here as it is - except a
     * {@link MalformedJsonException}, which is taken for one of the body: a document the listener reads out of
     * a value of the body is the client's as well.
     *
     * @param listener the listener
     * @param <L> the type of the listener
     * @return the listener
     * @throws BadRequestException {@code 400} if the body is not a JSON document. The listener has been told
     *                             about the error and about the end of the document by then
     * @throws IllegalStateException if the handler has already returned - the body has gone back to the pool
     *                               by then
     */
    public <L extends JsonParserListener> L jsonBody(final L listener) throws BadRequestException {
        try {
            return Utf8JsonReader.parse(jsonBodyBytes(), listener);
        } catch (final MalformedJsonException malformed) {
            throw new BadRequestException("Malformed JSON in the request body: " + malformed.getMessage());
        }
    }

    private ByteBuf jsonBodyBytes() {
        if (handled) {
            throw new IllegalStateException("The handler has returned, so the body of the request has gone "
                    + "back to the pool. Read it before the handler returns");
        }
        return request.content();
    }

    public NamedMultiValues formParameters() {
        if (formParameters != null) {
            return formParameters;
        }
        final String body = request.content()
                .toString(StandardCharsets.UTF_8);
        final QueryStringDecoder decoder =
                new QueryStringDecoder(body, false);
        formParameters = new MappedNamedMultiValues(
                decoder.parameters());
        return formParameters;
    }

    public NamedValues headers() {
        if (headers != null) {
            return headers;
        }
        headers = new HttpHeadersNamedValues(request.headers());
        return headers;
    }

    private void updateQueryStringDecoder() {
        if (queryStringDecoder != null) {
            return;
        }
        queryStringDecoder = new QueryStringDecoder(request.uri());
    }

    private static final class MappedNamedMultiValues implements NamedMultiValues {
        private final List<String> names;
        private final List<List<String>> valueLists;

        private MappedNamedMultiValues(final Map<String, List<String>> parameters) {
            names = new ArrayList<>(parameters.size());
            valueLists = new ArrayList<>(parameters.size());
            for (final Map.Entry<String, List<String>> entry : parameters.entrySet()) {
                names.add(entry.getKey());
                valueLists.add(entry.getValue());
            }
        }

        @Override
        public int numberOfNames() {
            return names.size();
        }

        @Override
        public int nameToIndex(final String name) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public String indexToName(final int nameIndex) {
            if (nameIndex < 0 || nameIndex >= names.size()) {
                return null;
            }
            return names.get(nameIndex);
        }

        @Override
        public String value(final int nameIndex) {
            if (nameIndex < 0 || nameIndex >= valueLists.size()) {
                return null;
            }
            return firstValue(valueLists.get(nameIndex));
        }

        @Override
        public String value(final String name) {
            final int idx = nameToIndex(name);
            if (idx == -1) {
                return null;
            }
            return value(idx);
        }

        @Override
        public int numberOfValues(final int nameIndex) {
            if (nameIndex < 0 || nameIndex >= valueLists.size()) {
                return 0;
            }
            final List<String> values = valueLists.get(nameIndex);
            return values != null ? values.size() : 0;
        }

        @Override
        public int numberOfValues(final String name) {
            final int idx = nameToIndex(name);
            if (idx == -1) {
                return 0;
            }
            return numberOfValues(idx);
        }

        @Override
        public String value(final int nameIndex,
                            final int valueIndex) {
            if (nameIndex < 0 || nameIndex >= valueLists.size()) {
                return null;
            }
            final List<String> values = valueLists.get(nameIndex);
            if (values == null
                    || valueIndex < 0
                    || valueIndex >= values.size()) {
                return null;
            }
            return values.get(valueIndex);
        }

        @Override
        public String value(final String name,
                            final int valueIndex) {
            final int idx = nameToIndex(name);
            if (idx == -1) {
                return null;
            }
            return value(idx, valueIndex);
        }

        private static String firstValue(final List<String> values) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            return values.get(0);
        }
    }

    private static final class HttpHeadersNamedValues
            implements NamedValues {
        private final HttpHeaders httpHeaders;
        private List<String> namesList;

        private HttpHeadersNamedValues(
                final HttpHeaders httpHeaders) {
            this.httpHeaders = httpHeaders;
        }

        @Override
        public int numberOfNames() {
            return names().size();
        }

        @Override
        public int nameToIndex(final String name) {
            int idx = 0;
            for (final String n : names()) {
                if (n.equalsIgnoreCase(name)) {
                    return idx;
                }
                idx++;
            }
            return -1;
        }

        @Override
        public String indexToName(final int nameIndex) {
            final List<String> n = names();
            if (nameIndex < 0 || nameIndex >= n.size()) {
                return null;
            }
            return n.get(nameIndex);
        }

        @Override
        public String value(final int nameIndex) {
            final String name = indexToName(nameIndex);
            if (name == null) {
                return null;
            }
            return httpHeaders.get(name);
        }

        @Override
        public String value(final String name) {
            return httpHeaders.get(name);
        }

        private List<String> names() {
            if (namesList == null) {
                namesList = new ArrayList<>(httpHeaders.names());
            }
            return namesList;
        }
    }
}
