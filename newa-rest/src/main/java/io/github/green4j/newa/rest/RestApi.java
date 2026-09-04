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

package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
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
    private final ThreadLocalMatchers headMatchers;
    private final ThreadLocalMatchers optionsMatchers;

    RestApi(final RestApiBuilder builder) {
        this.builder = builder;

        getMatchers = new ThreadLocalMatchers(builder.get().prepareMatcher());
        postMatchers = new ThreadLocalMatchers(builder.post().prepareMatcher());
        putMatchers = new ThreadLocalMatchers(builder.put().prepareMatcher());
        deleteMatchers = new ThreadLocalMatchers(builder.delete().prepareMatcher());
        patchMatchers = new ThreadLocalMatchers(builder.patch().prepareMatcher());
        headMatchers = new ThreadLocalMatchers(builder.head().prepareMatcher());
        optionsMatchers = new ThreadLocalMatchers(builder.options().prepareMatcher());
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

    /**
     * Routes a request to the endpoint which answers it. Every method is looked up among the endpoints
     * registered for it alone, except {@code HEAD}: a {@code HEAD} which finds no endpoint of its own falls
     * back to the {@code GET} one for that path, which is what makes every path served on {@code GET}
     * answer {@code HEAD} as well. The handler renders the whole response there and the codec drops the
     * body, so the length the peer is told is the length it would have been sent.
     *
     * @param request to route.
     * @return the endpoint which answers it, and the path parameters it was matched with.
     * @throws MethodNotAllowedException if the API serves nothing on that method at all.
     * @throws PathNotFoundException if it serves nothing on that path.
     */
    public RestHandling resolve(final FullHttpRequest request)
            throws MethodNotAllowedException, PathNotFoundException {
        final String method = request.method().name();
        final boolean head = HttpMethod.HEAD.equals(request.method());

        final PathMatcher<RestHandle> pathMatcher = getThreadLocalMethodPathMatcher(method);
        if (pathMatcher == null && !head) {
            throw new MethodNotAllowedException(method);
        }

        final QueryStringDecoder qsd = new QueryStringDecoder(request.uri());
        final String path = qsd.path();

        PathMatcher<RestHandle>.Result match = pathMatcher == null ? null : pathMatcher.match(path);

        if (match == null && head) {
            final PathMatcher<RestHandle> getMatcher = getMatchers.get();
            if (getMatcher == null) {
                // nothing is served on GET either, so this API has no answer to a HEAD at all
                throw new MethodNotAllowedException(method);
            }
            match = getMatcher.match(path);
        }

        if (match == null) {
            throw new PathNotFoundException(path);
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
            case "HEAD":
                return headMatchers.get();
            case "OPTIONS":
                return optionsMatchers.get();
            default:
                break;
        }
        return null;
    }
}
