package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

public final class RestApi implements RestRouter {
    public static final char SLASH_CHAR = '/';
    public static final String SLASH = "" + SLASH_CHAR;

    private static final ThreadLocal<PathMatcher<RestHandle>> GET_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> POST_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> PUT_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> DELETE_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
    private static final ThreadLocal<PathMatcher<RestHandle>> PATCH_MATCHER_THREAD_LOCAL = new ThreadLocal<>();
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

    private final RestApiBuilder builder;

    private final PathMatcher<RestHandle> getMatcherTemplate;
    private final PathMatcher<RestHandle> postMatcherTemplate;
    private final PathMatcher<RestHandle> putMatcherTemplate;
    private final PathMatcher<RestHandle> deleteMatcherTemplate;
    private final PathMatcher<RestHandle> patchMatcherTemplate;

    RestApi(final RestApiBuilder builder) {
        this.builder = builder;

        getMatcherTemplate = builder.get().prepareMatcher();
        postMatcherTemplate = builder.post().prepareMatcher();
        putMatcherTemplate = builder.put().prepareMatcher();
        deleteMatcherTemplate = builder.delete().prepareMatcher();
        patchMatcherTemplate = builder.patch().prepareMatcher();
    }

    public boolean hasHelp() {
        return helpPath() != null;
    }

    public String helpPath() {
        final Endpoint helpEndpoint = builder.helpEndpoint();

        if (helpEndpoint == null) {
            return null;
        }
        return helpEndpoint.pathExpression();
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
            case "PATCH":
                return retrieveThreadLocal(
                        PATCH_MATCHER_THREAD_LOCAL,
                        patchMatcherTemplate
                );
            default:
                break;
        }
        return null;
    }
}
