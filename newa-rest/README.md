# newa-rest

HTTP REST API routing and handlers built on Netty.

## Goals

- Provide a small API for declaring versioned REST endpoints (`RestApiBuilder`, `RestApi`, `RestApiHandler`).
- Support JSON and plain-text responses through typed handles (`JsonRestHandle`, `TxtRestHandle`) and structured
  errors (`JsonErrorHandler`, `ErrorHandler`).
- Support path templates with `{parameters}` and fluent registration on the builder.

## Architecture principles

- **Routing**: `RestApi` resolves each `FullHttpRequest` using `PathMatcher`; routes are grouped by HTTP method inside
  `RestApiBuilder`.
- **Default path prefix**: For API version `n`, routes registered on the builder live under `/v<n>/...`. Use
  `RestApiBuilder.root()` to register endpoints without that prefix (for example a top-level `/version` route).
- **Embedding**: This library supplies routing types only; your application owns `ServerBootstrap`, event loop groups,
  and the channel pipeline. A typical child pipeline order is: `HttpServerCodec`, then `HttpObjectAggregator`,
  optionally `HttpContentCompressor`, then `RestApiHandler`.
- **RestContext**: Every handler receives a `RestContext` that bundles the Netty channel, request, and parameter
  accessors. Available accessors: `context.channel()`, `context.executor()`, `context.request()`,
  `context.pathParameters()`, `context.queryParameters()`, `context.formParameters()`, `context.headers()`. Query
  parameters, form parameters, and headers are parsed lazily on first access.
- **Async responses**: `RestApiHandler` invokes `RestHandle.handle(RestContext, Result)` on the channel `EventLoop`.
  Handlers registered via `getJson` / `getTxt` / etc. are expected to finish synchronously inside `doHandle`. When work
  completes later (`CompletableFuture`, remote callbacks, or time-delayed work), keep `RestContext` and
  `RestHandle.Result`, then finish the HTTP response on the context executor: use
  `context.executor().execute(() -> { ... result.ok(...) / result.error(...) / streaming ... done(); })` so Netty writes
  on the correct thread. For delays without blocking threads, use
  `context.executor().schedule(() -> { ... }, delay, TimeUnit)` so the runnable still runs on the same `EventLoop` after
  the timeout (same rules apply inside the runnable for nested async hops). If you use an unrelated `ExecutorService` or
  client library callback thread, always hop back with `context.executor().execute(...)` before touching `result`. Never
  block the `EventLoop` waiting on external completion.
- **Path parameters**: If a path expression includes `{name}`, you must chain `.withPathParameterDescriptions(...)` on
  the returned `Endpoint` so the number of descriptions matches the parsed parameters (`Method.prepareMatcher` enforces
  this).
- **Type-safe parameter access**: `NamedValues` and `NamedMultiValues` provide default methods for typed value access:
  `valueRequiredAsInt(name)`, `valueAsInt(name, defaultValue)`, and similar for `byte`, `short`, `long`, `float`,
  `double`, `BigDecimal`, and `boolean`. Boolean parsing accepts `true/yes/y/1` (case-insensitive). Unparseable values
  throw `BadRequestException` (HTTP 400).

Request flow:

```
Client --> HttpServerCodec --> HttpObjectAggregator --> RestApiHandler --> RestHandle / ErrorHandler
```

## API usage patterns

1. Create a builder: `new RestApiBuilder(name, description, version, buildVersion)`.
2. Register handlers with `getJson`, `getTxt`, `postJson`, `putJson`, and related methods. For paths with `{id}`, add
   `.withPathParameterDescriptions("id - ...")` after registration.
3. Optionally call `buildWithHelp(helpFactory)` (for example `Json_Help.factory()` in examples) to expose a help
   endpoint.
4. Build the router: `RestApi api = apiBuilder.build()` or `buildWithHelp(...)`.
5. Install `new RestApiHandler(api, new JsonErrorHandler(), channelErrorHandler)` on each accepted socket channel.
6. **Deferred completion**: Register a full `RestHandle` via `get(...)`, `post(...)`, etc. Use `context.executor()` to
   schedule asynchronous work, then finish the HTTP response as described under **Async responses** above.

Minimal illustration:

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");
builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");
RestApi api = builder.build();
// pipeline: ... addLast(new RestApiHandler(api, new JsonErrorHandler(), errorHandler));
```

Asynchronous downstream call (sketch): complete off-thread, then hop back:

```java
builder.get("/async", (context, result) ->
        remote.load().whenComplete((value, error) ->
                context.executor().execute(() -> {
                    if (error != null) {
                        result.error(error);
                    } else {
                        result.ok(/* ... */);
                    }
                })));
```
