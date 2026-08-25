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
- **Response memory**: JSON and plain-text handlers render into a thread-local buffer, which is what keeps
  responses allocation-free. Such a buffer grows to the largest response ever rendered on the thread, so one
  rare multi-megabyte response would otherwise leave every event loop thread that rendered one holding a
  buffer of that size for the lifetime of the process. The buffer is therefore sized to the load: it is never
  dropped and never falls below `ResponseBuffers.baseSize()` (64 KB by default, and where it starts, so
  ordinary responses never grow it at all), and above that it follows the largest response seen in the last
  `ResponseBuffers.observationWindowMillis()` (5 s by default) plus half of it again. Nothing counts requests
  - under load a thread serves hundreds a second, and a per-request counter would have the buffer released
  and re-grown constantly, turning a memory problem into a GC one. Both are overridable with the
  `newa.rest.baseBufferSize` and `newa.rest.bufferObservationWindowMillis` system properties. The
  rendered content is then copied into a buffer taken from the channel's allocator - direct, so the transport
  writes it as is rather than copying it into a direct buffer of its own right before the socket write. See
  **Large responses** below for what this does and does not solve.
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

## Large responses

Every response this API can produce is a `FullHttpResponse`: rendered in full, then written in one go. That
holds for `Result.ok(...)` and equally for the incremental `Result.Content` returned by
`ok(contentType, contentLength)` - `append(...)` fills the response buffer, it does not send anything. A
response of tens or hundreds of megabytes therefore costs at least that much memory per request in flight,
and a slow client keeps it there until the socket drains.

What the library does to keep that cost at its floor rather than a multiple of it:

- rendering buffers grown by a large response are not retained by the thread once the large responses stop
  (see **Response memory** above);
- content is copied once, into a buffer from the channel's allocator, instead of into a heap buffer the
  transport would then copy again;
- `ok(contentType, contentLength)` allocates the declared length up front rather than growing a buffer by
  repeated doubling and copying.

What it does not do: chunk the response, or apply backpressure when the peer stops reading. Until it does,
serve large payloads within a budget you have actually measured:

- **Page**. A response worth hundreds of megabytes is usually a missing `limit`/`cursor` on the endpoint. The
  client has to hold it too.
- **Cap** the response size in the handler and answer `413`/`507` rather than degrading quietly.
- **Configure the watermarks**, so a channel whose peer stopped reading reports itself unwritable:

  ```java
  bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
          new WriteBufferWaterMark(32 * 1024, 64 * 1024));
  ```

- **Size the container for direct memory, not just the heap.** Response buffers come from Netty's allocator
  and live off-heap, where `-Xmx` does not bound them and the cgroup limit does. A container sized from the
  heap alone is killed for its RSS instead of failing with `OutOfMemoryError`:

  ```
  -XX:MaxRAMPercentage=50
  -XX:MaxDirectMemorySize=512m
  -Dio.netty.maxDirectMemory=536870912
  -XX:+ExitOnOutOfMemoryError
  ```

  The container limit has to cover heap plus direct memory plus metaspace, code cache and thread stacks.
