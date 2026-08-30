package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

public final class RestApi implements RestRouter {
    public static final char SLASH_CHAR = '/';
    public static final String SLASH = "" + SLASH_CHAR;

    /**
     * Per-thread copy of one method's matcher. The copies belong to the {@link RestApi} that owns the template:
     * keyed by the class instead, two APIs served by the same thread would share whichever one got there first,
     * and the other would answer 404 on its own paths.
     */
    private static final class ThreadLocalMatchers {
        private final PathMatcher<RestHandle> template;
        private final ThreadLocal<PathMatcher<RestHandle>> threadLocal;

        private ThreadLocalMatchers(final PathMatcher<RestHandle> template) {
            this.template = template;
            this.threadLocal = template == null
                    ? null
                    : ThreadLocal.withInitial(() -> new PathMatcher<>(template));
        }

        private PathMatcher<RestHandle> get() {
            return template == null ? null : threadLocal.get();
        }
    }

    private final RestApiBuilder builder;

    private final ThreadLocalMatchers getMatchers;
    private final ThreadLocalMatchers postMatchers;
    private final ThreadLocalMatchers putMatchers;
    private final ThreadLocalMatchers deleteMatchers;
    private final ThreadLocalMatchers patchMatchers;

    RestApi(final RestApiBuilder builder) {
        this.builder = builder;

        getMatchers = new ThreadLocalMatchers(builder.get().prepareMatcher());
        postMatchers = new ThreadLocalMatchers(builder.post().prepareMatcher());
        putMatchers = new ThreadLocalMatchers(builder.put().prepareMatcher());
        deleteMatchers = new ThreadLocalMatchers(builder.delete().prepareMatcher());
        patchMatchers = new ThreadLocalMatchers(builder.patch().prepareMatcher());
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

        return new RestHandling(match.handler(), match, match.pathExpression());
    }

    private PathMatcher<RestHandle> getThreadLocalMethodPathMatcher(final String method) {
        switch (method) {
            case "GET":
                return getMatchers.get();
            case "POST":
                return postMatchers.get();
            case "PUT":
                return putMatchers.get();
            case "DELETE":
                return deleteMatchers.get();
            case "PATCH":
                return patchMatchers.get();
            default:
                break;
        }
        return null;
    }
}
