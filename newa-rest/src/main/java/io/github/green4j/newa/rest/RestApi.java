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

    public static final class Builder implements RestEndpointer {
        private final Method get = new Method("GET");
        private final Method post = new Method("POST");
        private final Method put = new Method("PUT");
        private final Method delete = new Method("DELETE");

        private final Method[] methods = new Method[] { get, post, put, delete };

        private final RestEndpointer root;

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
                return withRootEndpoint(
                        joinPaths(rootPath, pathExpression),
                        handler
                );
            }

            private Endpoint withRootEndpoint(final String pathExpression,
                                              final RestHandle handler) {
                final Endpoint result = new Endpoint(
                        pathExpression,
                        handler
                );
                endpoints.add(result);
                return result;
            }

            List<Endpoint> endpoints() {
                return endpoints;
            }

            private PathMatcher<RestHandle> prepareMatcher() {
                final PathMatcher.Builder<RestHandle> builder = PathMatcher.builder();
                for (final Endpoint ep : endpoints) {
                    final String[] parameters = builder.withPath(
                            ep.pathExpressionWithoutQuery(),
                            ep.handle()
                    );
                    final String[] parameterDescriptions = ep.pathParameterDescriptions();
                    if (parameters.length > 0
                            && (parameterDescriptions != null
                                && parameterDescriptions.length != parameters.length)) {
                        throw new IllegalArgumentException("Parameter descriptions misconfiguration for the path: "
                                + ep.pathExpression()
                                + ". Parsed parameters: "
                                + String.join(", ", parameters)
                                + " (" + parameters.length + "). Number of descriptions: "
                                + parameterDescriptions.length);
                    }
                }
                return builder.build();
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

            root = new RestEndpointer() {
                @Override
                public Endpoint get(final String pathExpression,
                                    final RestHandle handle) {
                    return get.withRootEndpoint(pathExpression, handle);
                }

                @Override
                public Endpoint getJson(final String pathExpression,
                                        final JsonRestHandle handle) {
                    return get(pathExpression, new JsonRestHandler(handle));
                }

                @Override
                public Endpoint getTxt(final String pathExpression,
                                       final TxtRestHandle handle) {
                    return get(pathExpression, new TxtRestHandler(handle));
                }

                @Override
                public Endpoint post(final String pathExpression,
                                     final RestHandle handle) {
                    return post.withRootEndpoint(pathExpression, handle);
                }

                @Override
                public Endpoint postJson(final String pathExpression,
                                         final JsonRestHandle handle) {
                    return post(pathExpression, new JsonRestHandler(handle));
                }

                @Override
                public Endpoint postTxt(final String pathExpression,
                                        final TxtRestHandle handle) {
                    return post(pathExpression, new TxtRestHandler(handle));
                }

                @Override
                public Endpoint put(final String pathExpression,
                                    final RestHandle handle) {
                    return put.withRootEndpoint(pathExpression, handle);
                }

                @Override
                public Endpoint putJson(final String pathExpression,
                                        final JsonRestHandle handle) {
                    return put(pathExpression, new JsonRestHandler(handle));
                }

                @Override
                public Endpoint putTxt(final String pathExpression,
                                       final TxtRestHandle handle) {
                    return put(pathExpression, new TxtRestHandler(handle));
                }

                @Override
                public Endpoint delete(final String pathExpression,
                                       final RestHandle handle) {
                    return delete.withRootEndpoint(pathExpression, handle);
                }

                @Override
                public Endpoint deleteJson(final String pathExpression,
                                           final JsonRestHandle handle) {
                    return delete(pathExpression, new JsonRestHandler(handle));
                }

                @Override
                public Endpoint deleteTxt(final String pathExpression,
                                          final TxtRestHandle handle) {
                    return delete(pathExpression, new TxtRestHandler(handle));
                }
            };
        }

        public RestEndpointer root() {
            return root;
        }

        @Override
        public Endpoint get(final String pathExpression,
                            final RestHandle handle) {
            return get.withEndpoint(pathExpression, handle);
        }

        @Override
        public Endpoint getJson(final String pathExpression,
                                final JsonRestHandle handle) {
            return get(pathExpression, new JsonRestHandler(handle));
        }

        @Override
        public Endpoint getTxt(final String pathExpression,
                               final TxtRestHandle handle) {
            return get(pathExpression, new TxtRestHandler(handle));
        }

        @Override
        public Endpoint post(final String pathExpression,
                             final RestHandle handle) {
            return post.withEndpoint(pathExpression, handle);
        }

        @Override
        public Endpoint postJson(final String pathExpression,
                                 final JsonRestHandle handle) {
            return post(pathExpression, new JsonRestHandler(handle));
        }

        @Override
        public Endpoint postTxt(final String pathExpression,
                                final TxtRestHandle handle) {
            return post(pathExpression, new TxtRestHandler(handle));
        }

        @Override
        public Endpoint put(final String pathExpression,
                            final RestHandle handle) {
            return put.withEndpoint(pathExpression, handle);
        }

        @Override
        public Endpoint putJson(final String pathExpression,
                                final JsonRestHandle handle) {
            return put(pathExpression, new JsonRestHandler(handle));
        }

        @Override
        public Endpoint putTxt(final String pathExpression,
                               final TxtRestHandle handle) {
            return put(pathExpression, new TxtRestHandler(handle));
        }

        @Override
        public Endpoint delete(final String pathExpression,
                               final RestHandle handle) {
            return delete.withEndpoint(pathExpression, handle);
        }

        @Override
        public Endpoint deleteJson(final String pathExpression,
                                   final JsonRestHandle handle) {
            return delete(pathExpression, new JsonRestHandler(handle));
        }

        @Override
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
                    .map(Method::endpoints)
                    .flatMap(List::stream).collect(Collectors.toList());
        }

        Method[] methods() {
            return methods;
        }
    }

    private static final ThreadLocal<PathMatcher<RestHandle>> GET_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> POST_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> PUT_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> DELETE_MATCHER_THREAD_LOCAL = new ThreadLocal<>();

    private final Builder builder;

    private final PathMatcher<RestHandle> getMatcherTemplate;
    private final PathMatcher<RestHandle> postMatcherTemplate;
    private final PathMatcher<RestHandle> putMatcherTemplate;
    private final PathMatcher<RestHandle> deleteMatcherTemplate;

    private static PathMatcher<RestHandle> retrieveThreadLocal(final ThreadLocal<PathMatcher<RestHandle>> threadLocal,
                                                               final PathMatcher<RestHandle> template) {
        if (template == null) {
            return null;
        }
        PathMatcher<RestHandle> result = threadLocal.get();
        if (result == null) {
            result = new PathMatcher<>(template);
            threadLocal.set(result);
        }
        return result;
    }

    private RestApi(final Builder builder) {
        this.builder = builder;
        // prepare method matcher templates
        getMatcherTemplate = !builder.get.endpoints.isEmpty()
                ? builder.get.prepareMatcher() : null;
        postMatcherTemplate = !builder.post.endpoints.isEmpty()
                ? builder.post.prepareMatcher() : null;
        putMatcherTemplate = !builder.put.endpoints.isEmpty()
                ? builder.put.prepareMatcher() : null;
        deleteMatcherTemplate = !builder.delete.endpoints.isEmpty()
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

    public RestHandling resolve(final FullHttpRequest request)
            throws MethodNotAllowedException, PathNotFoundException {
        final String method = request.method().name();
        final PathMatcher<RestHandle> pathMatcher = getThreadLocalMethodPathMatcher(method);
        if (pathMatcher == null) {
            throw new MethodNotAllowedException(method);
        }

        final QueryStringDecoder qsd = new QueryStringDecoder(request.uri());
        final PathMatcher<RestHandle>.Result match = pathMatcher.match(qsd.path());
        if (match == null) {
            throw new PathNotFoundException(qsd.path());
        }

        return new RestHandling(match.handler(), match);
    }

    private PathMatcher<RestHandle> getThreadLocalMethodPathMatcher(final String method) {
        switch (method) {
            case "GET":
                return retrieveThreadLocal(
                        GET_MATCHER_THREAD_LOCAL,
                        getMatcherTemplate
                );
            case "POST":
                return retrieveThreadLocal(
                        POST_MATCHER_THREAD_LOCAL,
                        postMatcherTemplate
                );
            case "PUT":
                return retrieveThreadLocal(
                        PUT_MATCHER_THREAD_LOCAL,
                        putMatcherTemplate
                );
            case "DELETE":
                return retrieveThreadLocal(
                        DELETE_MATCHER_THREAD_LOCAL,
                        deleteMatcherTemplate
                );
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
                && to.charAt(to.length() - 1) == SLASH_CHAR;
    }
}
