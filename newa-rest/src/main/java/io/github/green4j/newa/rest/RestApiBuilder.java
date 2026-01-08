package io.github.green4j.newa.rest;

import java.util.Arrays;

import static io.github.green4j.newa.rest.RestApi.SLASH;

public final class RestApiBuilder implements RestApiParameters, RestEndpointer {
    private final Method get = new Method("GET");
    private final Method put = new Method("PUT");
    private final Method post = new Method("POST");
    private final Method delete = new Method("DELETE");
    private final Method patch = new Method("PATCH");

    private final Method[] methods = new Method[]{get, put, post, delete, patch};

    private final RestEndpointer root;

    private final String name;
    private final int version;
    private final String description;
    private final String buildVersion;

    private final String fullVersion;
    private final String rootPath;

    private Endpoint helpEndpoint;

    public RestApiBuilder(final String name,
                          final String description,
                          final int version,
                          final String buildVersion) {
        this.name = name;
        this.version = version;
        this.buildVersion = buildVersion;
        this.description = description;

        this.fullVersion = "v" + version;
        this.rootPath = SLASH + fullVersion + SLASH;

        root = new RestEndpointer() {
            @Override
            public Endpoint get(final String pathExpression,
                                final RestHandle handle) {
                return get.withRootEndpoint(
                        pathExpression,
                        handle
                );
            }

            @Override
            public Endpoint getJson(final String pathExpression,
                                    final JsonRestHandle handle) {
                return get(
                        pathExpression,
                        new JsonRestHandler(handle)
                );
            }

            @Override
            public Endpoint getTxt(final String pathExpression,
                                   final TxtRestHandle handle) {
                return get(
                        pathExpression,
                        new TxtRestHandler(handle)
                );
            }

            @Override
            public Endpoint put(final String pathExpression,
                                final RestHandle handle) {
                return put.withRootEndpoint(
                        pathExpression,
                        handle
                );
            }

            @Override
            public Endpoint putJson(final String pathExpression,
                                    final JsonRestHandle handle) {
                return put(
                        pathExpression,
                        new JsonRestHandler(handle)
                );
            }

            @Override
            public Endpoint putTxt(final String pathExpression,
                                   final TxtRestHandle handle) {
                return put(
                        pathExpression,
                        new TxtRestHandler(handle)
                );
            }

            @Override
            public Endpoint post(final String pathExpression,
                                 final RestHandle handle) {
                return post.withRootEndpoint(
                        pathExpression,
                        handle
                );
            }

            @Override
            public Endpoint postJson(final String pathExpression,
                                     final JsonRestHandle handle) {
                return post(
                        pathExpression,
                        new JsonRestHandler(handle)
                );
            }

            @Override
            public Endpoint postTxt(final String pathExpression,
                                    final TxtRestHandle handle) {
                return post(
                        pathExpression,
                        new TxtRestHandler(handle)
                );
            }

            @Override
            public Endpoint delete(final String pathExpression,
                                   final RestHandle handle) {
                return delete.withRootEndpoint(
                        pathExpression,
                        handle
                );
            }

            @Override
            public Endpoint deleteJson(final String pathExpression,
                                       final JsonRestHandle handle) {
                return delete(
                        pathExpression,
                        new JsonRestHandler(handle)
                );
            }

            @Override
            public Endpoint deleteTxt(final String pathExpression,
                                      final TxtRestHandle handle) {
                return delete(
                        pathExpression,
                        new TxtRestHandler(handle)
                );
            }

            @Override
            public Endpoint patch(final String pathExpression,
                                  final RestHandle handle) {
                return patch.withRootEndpoint(
                        pathExpression,
                        handle
                );
            }

            @Override
            public Endpoint patchJson(final String pathExpression,
                                      final JsonRestHandle handle) {
                return patch(pathExpression,
                        new JsonRestHandler(handle)
                );
            }

            @Override
            public Endpoint patchTxt(final String pathExpression,
                                     final TxtRestHandle handle) {
                return patch(
                        pathExpression,
                        new TxtRestHandler(handle)
                );
            }
        };
    }

    public RestEndpointer root() {
        return root;
    }

    @Override
    public Endpoint get(final String pathExpression,
                        final RestHandle handle) {
        return get.withEndpoint(
                rootPath,
                pathExpression,
                handle
        );
    }

    @Override
    public Endpoint getJson(final String pathExpression,
                            final JsonRestHandle handle) {
        return get(
                pathExpression,
                new JsonRestHandler(handle)
        );
    }

    @Override
    public Endpoint getTxt(final String pathExpression,
                           final TxtRestHandle handle) {
        return get(
                pathExpression,
                new TxtRestHandler(handle)
        );
    }

    @Override
    public Endpoint put(final String pathExpression,
                        final RestHandle handle) {
        return put.withEndpoint(
                rootPath,
                pathExpression,
                handle
        );
    }

    @Override
    public Endpoint putJson(final String pathExpression,
                            final JsonRestHandle handle) {
        return put(
                pathExpression,
                new JsonRestHandler(handle)
        );
    }

    @Override
    public Endpoint putTxt(final String pathExpression,
                           final TxtRestHandle handle) {
        return put(
                pathExpression,
                new TxtRestHandler(handle)
        );
    }

    @Override
    public Endpoint post(final String pathExpression,
                         final RestHandle handle) {
        return post.withEndpoint(
                rootPath,
                pathExpression,
                handle
        );
    }

    @Override
    public Endpoint postJson(final String pathExpression,
                             final JsonRestHandle handle) {
        return post(
                pathExpression,
                new JsonRestHandler(handle)
        );
    }

    @Override
    public Endpoint postTxt(final String pathExpression,
                            final TxtRestHandle handle) {
        return post(
                pathExpression,
                new TxtRestHandler(handle)
        );
    }

    @Override
    public Endpoint delete(final String pathExpression,
                           final RestHandle handle) {
        return delete.withEndpoint(
                rootPath,
                pathExpression,
                handle
        );
    }

    @Override
    public Endpoint deleteJson(final String pathExpression,
                               final JsonRestHandle handle) {
        return delete(
                pathExpression,
                new JsonRestHandler(handle)
        );
    }

    @Override
    public Endpoint deleteTxt(final String pathExpression,
                              final TxtRestHandle handle) {
        return delete(
                pathExpression,
                new TxtRestHandler(handle)
        );
    }

    @Override
    public Endpoint patch(final String pathExpression,
                          final RestHandle handle) {
        return patch.withEndpoint(
                rootPath,
                pathExpression,
                handle
        );
    }

    @Override
    public Endpoint patchJson(final String pathExpression,
                              final JsonRestHandle handle) {
        return patch(
                pathExpression,
                new JsonRestHandler(handle)
        );
    }

    @Override
    public Endpoint patchTxt(final String pathExpression,
                             final TxtRestHandle handle) {
        return patch(
                pathExpression,
                new TxtRestHandler(handle)
        );
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public String fullVersion() {
        return fullVersion;
    }

    @Override
    public String buildVersion() {
        return buildVersion;
    }

    @Override
    public Endpoint[] endpoints() {
        return Arrays.stream(methods)
                .map(Method::endpoints)
                .flatMap(Arrays::stream).toArray(Endpoint[]::new);
    }

    @Override
    public Method[] methods() {
        return methods;
    }

    public RestApi build() {
        return buildWithHelp(null);
    }

    public RestApi buildWithHelp(final RestApiHelpFactory helpFactory) {
        if (helpFactory != null) {
            helpEndpoint = get(
                    "help",
                    helpFactory.buildHelp(this)
            ).withDescription("This help.");
        }
        return new RestApi(this);
    }

    Method get() {
        return get;
    }

    Method put() {
        return put;
    }

    Method post() {
        return post;
    }

    Method delete() {
        return delete;
    }

    Method patch() {
        return patch;
    }

    Endpoint helpEndpoint() {
        return helpEndpoint;
    }
}
