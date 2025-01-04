package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class RestApi implements RestRouter {
    public static final char SLASH_CHAR = '/';
    public static final String SLASH = "" + SLASH_CHAR;

    public static Builder builder() {
        return new Builder(null, -1, null, null);
    }

    public static Builder builder(final String apiName,
                                  final int apiVersion,
                                  final String componentName,
                                  final String componentBuildVersion) {
        return new Builder(apiName, apiVersion, componentName, componentBuildVersion);
    }

    public interface HelpFactory {
        RestHandle buildHelp(Builder forBuilder);
    }

    public static final class Endpoint {
        public static final String[] EMPTY = new String[0];
        private final String pathExpression;
        private final RestHandle handler;

        private String description;
        private String[] pathParameterDescriptions;
        private String[] queryParameterDescriptions;

        private Endpoint(final String pathExpression,
                         final RestHandle handler) {
            this.pathExpression = pathExpression;
            this.handler = handler;
        }

        public Endpoint withDescription(final String description) {
            this.description = description;
            return this;
        }

        public Endpoint withPathParameterDescriptions(final String... parameterDescriptions) {
            this.pathParameterDescriptions = parameterDescriptions;
            return this;
        }

        public Endpoint withQueryParameterDescriptions(final String... queryParameterDescriptions) {
            this.queryParameterDescriptions = queryParameterDescriptions;
            return this;
        }

        String pathExpression() {
            return pathExpression;
        }

        String pathExpressionWithoutQuery() {
            if (pathExpression == null) {
                return null;
            }
            final int qIdx = pathExpression.indexOf('?');
            return qIdx == -1 ? pathExpression : pathExpression.substring(0, qIdx);
        }

        String description() {
            return description;
        }

        String[] pathParameterDescriptions() {
            return pathParameterDescriptions != null ? pathParameterDescriptions : EMPTY;
        }

        String[] queryParameterDescriptions() {
            return queryParameterDescriptions != null ? queryParameterDescriptions : EMPTY;
        }
    }

    public static final class Builder {
        private final Method get = new Method("GET");
        private final Method post = new Method("POST");
        private final Method put = new Method("PUT");
        private final Method delete = new Method("DELETE");

        private final Method[] methods = new Method[] { get, post, put, delete };

        private final String name;
        private final String version;
        private final String componentName;
        private final String componentBuildVersion;
        private final String rootPath;

        private Endpoint helpEndpoint;

        final class Method {
            private final String name;
            private final List<Endpoint> endpoints = new ArrayList<>();

            private Method(final String name) {
                this.name = name;
            }

            String name() {
                return name;
            }

            private Endpoint withEndpoint(final String pathExpression,
                                          final RestHandle handler) {
                final Endpoint result = new Endpoint(
                        joinPaths(rootPath, pathExpression),
                        handler
                );
                endpoints.add(result);
                return result;
            }

            List<Endpoint> endpoints() {
                return endpoints;
            }

            private PathMatcher<RestHandle> prepareMatcher() {
                final PathMatcher.Builder<RestHandle> pmBuilder = PathMatcher.builder();
                for (final Endpoint e : endpoints) {
                    final String[] parameters = pmBuilder.withPath(
                            e.pathExpressionWithoutQuery(),
                            e.handler
                    );
                    final String[] parameterDescriptions = e.pathParameterDescriptions;
                    if (parameters.length > 0
                            && (parameterDescriptions != null
                                && parameterDescriptions.length != parameters.length)) {
                        throw new IllegalArgumentException("Parameter descriptions misconfiguration for the path: "
                                + e.pathExpression
                                + ". Parsed parameters: "
                                + (parameters.length > 0
                                    ? Arrays.stream(parameters)
                                        .collect(Collectors.joining(", ")) : "none")
                                + " (" + parameters.length + "). Number of descriptions: "
                                + parameterDescriptions.length);
                    }
                }
                return pmBuilder.build();
            }
        }

        private Builder(final String apiName,
                        final int apiVersion,
                        final String componentName,
                        final String componentBuildVersion) {
            this.name = apiName;
            this.version = "v" + apiVersion;
            this.componentName = componentName;
            this.componentBuildVersion = componentBuildVersion;
            this.rootPath = SLASH + this.version + SLASH;
        }

        public Endpoint get(final String pathExpression,
                            final RestHandle handle) {
            return get.withEndpoint(pathExpression, handle);
        }

        public Endpoint getJson(final String pathExpression,
                                final JsonRestHandle handle) {
            return get(pathExpression, new JsonRestHandler(handle));
        }

        public Endpoint getTxt(final String pathExpression,
                               final TxtRestHandle handle) {
            return get(pathExpression, new TxtRestHandler(handle));
        }

        public Endpoint post(final String pathExpression,
                             final RestHandle handle) {
            return post.withEndpoint(pathExpression, handle);
        }

        public Endpoint postJson(final String pathExpression,
                                 final JsonRestHandle handle) {
            return post(pathExpression, new JsonRestHandler(handle));
        }

        public Endpoint postTxt(final String pathExpression,
                                final TxtRestHandle handle) {
            return post(pathExpression, new TxtRestHandler(handle));
        }

        public Endpoint put(final String pathExpression,
                            final RestHandle handle) {
            return put.withEndpoint(pathExpression, handle);
        }

        public Endpoint putJson(final String pathExpression,
                                final JsonRestHandle handle) {
            return put(pathExpression, new JsonRestHandler(handle));
        }

        public Endpoint putTxt(final String pathExpression,
                               final TxtRestHandle handle) {
            return put(pathExpression, new TxtRestHandler(handle));
        }


        public Endpoint delete(final String pathExpression,
                               final RestHandle handle) {
            return delete.withEndpoint(pathExpression, handle);
        }

        public Endpoint deleteJson(final String pathExpression,
                                   final JsonRestHandle handle) {
            return delete(pathExpression, new JsonRestHandler(handle));
        }

        public Endpoint deleteTxt(final String pathExpression,
                                  final TxtRestHandle handle) {
            return delete(pathExpression, new TxtRestHandler(handle));
        }

        public RestApi build() {
            return buildWithHelp(null);
        }

        public RestApi buildWithHelp(final HelpFactory helpFactory) {
            if (helpFactory != null) {
                helpEndpoint = get("help", helpFactory.buildHelp(this))
                        .withDescription("This help.");
            }
            return new RestApi(this);
        }

        public String componentName() {
            return componentName;
        }

        public String componentBuildVersion() {
            return componentBuildVersion;
        }

        public String apiName() {
            return name;
        }

        public String apiVersion() {
            return version;
        }

        List<Endpoint> endpoints() {
            return Arrays.stream(methods)
                    .map(m -> m.endpoints())
                    .flatMap(List::stream).collect(Collectors.toList());
        }

        Method[] methods() {
            return methods;
        }
    }

    private final Builder builder;
    private final PathMatcher<RestHandle> get;
    private final PathMatcher<RestHandle> post;
    private final PathMatcher<RestHandle> put;
    private final PathMatcher<RestHandle> delete;

    private RestApi(final Builder builder) {
        this.builder = builder;
        // prepare method matchers
        get = !builder.get.endpoints.isEmpty()
                ? builder.get.prepareMatcher() : null;
        post = !builder.post.endpoints.isEmpty()
                ? builder.post.prepareMatcher() : null;
        put = !builder.put.endpoints.isEmpty()
                ? builder.put.prepareMatcher() : null;
        delete = !builder.delete.endpoints.isEmpty()
                ? builder.delete.prepareMatcher() : null;
    }

    public String description() {
        return builder.name;
    }

    public boolean hasHelp() {
        return helpPath() != null;
    }

    public String helpPath() {
        if (builder.helpEndpoint == null) {
            return null;
        }
        return builder.helpEndpoint.pathExpression();
    }

    public RestHandling resolve(final FullHttpRequest request) throws
            MethodNotAllowedException,
            PathNotFoundException,
            InternalServerErrorException {
        try {
            final String method = request.method().name();
            final PathMatcher<RestHandle> pathMatcher = getMethodPathMatcher(method);
            if (pathMatcher == null) {
                throw new MethodNotAllowedException(method);
            }

            final QueryStringDecoder qsd = new QueryStringDecoder(request.uri());
            final PathMatcher<RestHandle>.Result match = pathMatcher.match(qsd.path());
            if (match == null) {
                throw new PathNotFoundException(qsd.path());
            }

            return new RestHandling(match.handler(), match);
        } catch (final RestException e) {
            throw e;
        } catch (final Exception e) {
            throw new InternalServerErrorException(e);
        }
    }

    private PathMatcher<RestHandle> getMethodPathMatcher(final String method)
            throws MethodNotAllowedException {
        switch (method) {
            case "GET":
                return get;
            case "POST":
                return post;
            case "PUT":
                return put;
            case "DELETE":
                return delete;
            default:
                break;
        }
        return null;
    }

    private static String joinPaths(final String p1, final String p2) {
        final StringBuilder result = new StringBuilder();
        appendTrimmedPath(result, p2,
                appendTrimmedPath(result, p1, false));
        return result.toString();
    }

    private static boolean appendTrimmedPath(final StringBuilder to,
                                      final CharSequence path,
                                      final boolean skipFirstSlash) {
        int increment = 0;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);
            if (!Character.isWhitespace(c)
                    && !(skipFirstSlash && c == SLASH_CHAR)) {
                if (to.length() > 0) {
                    if (to.charAt(to.length() - 1) != SLASH_CHAR) {
                        to.append(SLASH_CHAR);
                    }
                }
                to.append(path, i, path.length());
                increment = path.length() - i;
                break;
            }
        }
        while (increment-- > 0) {
            final int lastCharIdx = to.length() - 1;
            final char c = to.charAt(lastCharIdx);
            if (!Character.isWhitespace(c)) {
                break;
            }
            to.setLength(lastCharIdx);
        }
        return to.length() > 0
                ? to.charAt(to.length() - 1) == SLASH_CHAR : false;
    }
}
