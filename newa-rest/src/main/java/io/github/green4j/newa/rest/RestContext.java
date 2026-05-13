package io.github.green4j.newa.rest;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.EventExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RestContext {
    private final ChannelHandlerContext handlerContext;
    private final FullHttpRequest request;
    private final NamedValues pathParameters;

    private QueryStringDecoder queryStringDecoder;
    private MappedNamedMultiValues queryParameters;
    private MappedNamedMultiValues formParameters;
    private NamedValues headers;

    RestContext(final ChannelHandlerContext handlerContext,
                final FullHttpRequest request,
                final NamedValues pathParameters) {
        this.handlerContext = handlerContext;
        this.request = request;
        this.pathParameters = pathParameters;
    }

    public Channel channel() {
        return handlerContext.channel();
    }

    public EventExecutor executor() {
        return handlerContext.executor();
    }

    public FullHttpRequest request() {
        return request;
    }

    public NamedValues pathParameters() {
        return pathParameters;
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
