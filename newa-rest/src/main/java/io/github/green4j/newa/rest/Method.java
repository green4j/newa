package io.github.green4j.newa.rest;

import java.util.ArrayList;
import java.util.List;

import static io.github.green4j.newa.rest.RestApi.SLASH_CHAR;

public final class Method {
    private final String name;
    private final List<Endpoint> endpoints = new ArrayList<>();

    Method(final String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Endpoint[] endpoints() {
        return endpoints.toArray(Endpoint[]::new);
    }

    public boolean hasEndpoints() {
        return !endpoints.isEmpty();
    }

    Endpoint withEndpoint(final String rootPath,
                          final String pathExpression,
                          final RestHandle handler) {
        return withRootEndpoint(
                joinPaths(rootPath, pathExpression),
                handler
        );
    }

    Endpoint withRootEndpoint(final String pathExpression,
                              final RestHandle handler) {
        final Endpoint result = new Endpoint(
                pathExpression,
                handler
        );
        endpoints.add(result);
        return result;
    }

    /**
     * Prepares PathMatcher for the specified endpoints.
     * @return matcher if any endpoint has been specified for the method, otherwise null
     */
    PathMatcher<RestHandle> prepareMatcher() {
        if (!hasEndpoints()) {
            return null;
        }

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

    private static String joinPaths(final String p1,
                                    final String p2) {
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
