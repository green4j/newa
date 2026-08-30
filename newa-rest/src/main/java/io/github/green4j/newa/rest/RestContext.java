package io.github.green4j.newa.rest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.EventExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestContext {
    private final ChannelHandlerContext handlerContext;
    private final FullHttpRequest request;
    private final RestHandling handling;
    private final HttpHeaders responseHeaders;
    private final ResponseChunks responseChunks;
    private final RestApiObserver observer;

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
                final RestApiObserver observer) {
        this.handlerContext = handlerContext;
        this.request = request;
        this.handling = handling;
        this.responseHeaders = responseHeaders;
        this.responseChunks = responseChunks;
        this.observer = observer;
    }

    /**
     * The handler has returned, so the matcher flyweight the path parameters come from is free to be reused
     * by the next request on this thread.
     */
    void handled() {
        handled = true;
    }

    /**
     * Where a handler puts the headers its response should carry - the one way to do it, and the same one
     * whether the response is built here or by one of the pre-built handlers, which never hand out the
     * result they are building:
     * <pre>{@code
     * context.responseHeaders().set(CONTENT_DISPOSITION, ContentDisposition.attachment("rows.json.gz"));
     * }</pre>
     * The framework writes its own headers after these, so nothing set here can break the framing of the
     * response. Nothing here reaches an error response either: these belong to the response the handler was
     * building, and a handler which failed never sent it.
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
     * @return what this request is reported to, or null if it is not observed, or observed only as far as
     *         {@link HttpApiObserver} goes
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
     * The one thing here which would go wrong quietly: these are a matcher flyweight, and the next request on
     * this thread writes its own parameters into it. So reading them after the handler has returned is
     * refused rather than answered with somebody else's values.
     *
     * @return the path parameters of this request
     * @throws IllegalStateException if the handler has already returned
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
