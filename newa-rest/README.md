# newa-rest

HTTP REST API routing and handlers built on Netty. The library supplies routing and response types only: your
application owns `ServerBootstrap`, the event loop groups and the pipeline.

```
Client --> HttpServerCodec --> HttpObjectAggregator --> [HttpContentCompressor] --> RestApiHandler
```

## Getting started

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");

builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");

RestApi api = builder.build();                       // or buildWithHelp(Json_Help.factory())

pipeline.addLast(new RestApiHandler(api, new JsonErrorHandler(), channelErrorHandler));
```

`getJson` / `getTxt` / `postJson` / ... register handles which render a response and return. `get` / `post` /
... register a full `RestHandle`, which is given the `Result` and may finish it later.

## Routing

`RestApi` resolves each `FullHttpRequest` with `PathMatcher`; routes are grouped by method inside
`RestApiBuilder`.

- **Version prefix**: routes registered on a builder of version `n` live under `/v<n>/...`.
  `RestApiBuilder.root()` registers without it - a top-level `/version`, say.
- **Path templates**: `{name}` in a path expression requires a matching `.withPathParameterDescriptions(...)`
  on the returned `Endpoint`; the count is enforced when the API is built.
- **Typed access**: `NamedValues` and `NamedMultiValues` offer `valueRequiredAsInt(name)`,
  `valueAsInt(name, default)` and the same for `byte`, `short`, `long`, `float`, `double`, `BigDecimal` and
  `boolean` (`true/yes/y/1`, case-insensitive). An unparseable value throws `BadRequestException` - HTTP 400.

## RestContext

Every handler is given one: `channel()`, `executor()`, `request()`, `pathExpression()`, `method()`, `uri()`,
`pathParameters()`, `queryParameters()`, `formParameters()`, `headers()`, `responseHeaders()`. Query
parameters, form parameters and headers are parsed on first access.

Two of these do not outlive the handler. The request's body goes back to the pool when `handle` returns, and
`pathParameters()` is a matcher flyweight the next request on that thread overwrites - it throws
`IllegalStateException` rather than answering with somebody else's values. Everything else, including
`pathExpression()`, stays valid.

## Response headers

One way, the same in every handler - including the pre-built ones, which never hand out the result they are
building:

```java
context.responseHeaders().set(CONTENT_DISPOSITION, ContentDisposition.attachment("rows.json.gz"));
```

It is Netty's `HttpHeaders`, so `add()` works and a response can carry two `Set-Cookie` lines.
`ContentDisposition.attachment(name)` builds the value once and refuses a name which would not survive being
quoted.

`Content-Length`, `Transfer-Encoding` and `Connection` are dropped before the response goes out: how a
response is framed is not something a handler decides by accident. An error response carries none of these
headers either - they belonged to the response the handler never sent.

## Async responses

`RestHandle.handle` runs on the channel's `EventLoop`, and so must everything that finishes the response.
Handles registered through `getJson` / `getTxt` finish inside the call; a full `RestHandle` may keep the
`RestContext` and `Result` and finish later, but always back on the loop:

```java
builder.get("/async", (context, result) ->
        remote.load().whenComplete((value, error) ->
                context.executor().execute(() -> {          // hop back before touching result
                    if (error != null) {
                        result.error(error);
                    } else {
                        result.ok(/* ... */);
                    }
                })));
```

`context.executor().schedule(...)` delays without blocking. Never block the `EventLoop` waiting for anything.

## Response memory

JSON and text handlers render into a thread-local buffer, which is what keeps responses allocation-free. Such
a buffer grows to the largest response the thread ever rendered, so one rare multi-megabyte response would
otherwise leave every event loop thread holding a buffer that size for the life of the process.

So the buffer is sized to the load: never dropped, never below `ResponseBuffers.baseSize()` (64 KB, and where
it starts, so ordinary responses never grow it), and above that it follows the largest response of the last
`ResponseBuffers.observationWindowMillis()` (5 s) plus half again. Nothing counts requests - a thread serves
hundreds a second, and a per-request counter would release and re-grow the buffer constantly, turning a memory
problem into a GC one. Both are overridable: `newa.rest.baseBufferSize`,
`newa.rest.bufferObservationWindowMillis`.

The rendered content is then copied once into a buffer from the channel's allocator - direct, so the transport
writes it as is.

## Large responses

Everything but a chunked response is a `FullHttpResponse`: rendered in full, then written in one go. That
holds for the incremental `Result.Content` too - `append(...)` fills the response buffer, it sends nothing. A
response of hundreds of megabytes costs that much memory per request in flight, and a slow client keeps it
there until the socket drains.

The library keeps that at its floor rather than a multiple of it - the buffer policy above, one copy instead
of two, and `ok(contentType, contentLength)` allocating the declared length up front instead of doubling and
copying. Keeping it *low* is yours:

- **Page.** A response worth hundreds of megabytes is usually a missing `limit`/`cursor` on the endpoint. The
  client has to hold it too.
- **Cap** the size in the handler and answer `413`/`507` rather than degrading quietly.
- **Size the container for direct memory, not just the heap.** Response buffers live off-heap, where `-Xmx`
  does not bound them and the cgroup limit does; a container sized from the heap alone is killed for its RSS
  instead of failing with `OutOfMemoryError`. The limit has to cover heap plus direct memory plus metaspace,
  code cache and thread stacks:

  ```
  -XX:MaxRAMPercentage=50
  -XX:MaxDirectMemorySize=512m
  -Dio.netty.maxDirectMemory=536870912
  -XX:+ExitOnOutOfMemoryError
  ```

When none of that is enough, do not build the response at all.

## Chunked responses

A response sent a piece at a time instead of being rendered in full. Everything runs on the event loop, so a
peer which stops reading costs one suspended source and no thread - and nothing bounds how many such responses
are in flight.

Two shapes, differing in who decides that the next piece exists:

|                             | **Pull**                                                               | **Push**                                  |
|-----------------------------|------------------------------------------------------------------------|-------------------------------------------|
| The source                  | always has more                                                        | has nothing until something happens       |
| Next piece                  | the framework asks, the cursor answers                                 | the source produces it, then flushes      |
| Ends when                   | the cursor says so                                                     | the peer goes away                        |
| Paced by                    | the channel's write watermark                                          | whatever produces the content             |
| Counted by `maxOpenCursors` | yes                                                                    | no                                        |
| Written as                  | `ChunkedJsonRestHandler`, `ChunkedTxtRestHandler`, `ChunkedRestHandler` | `PushedResponseBody` given to `Result.ok` |
| Typically                   | rows from a database, a report                                         | a clock, a queue, a feed                  |

`ChunkedWriteHandler` is put in front of `RestApiHandler` by the first chunked response on a channel: nothing
to add to the pipeline, nothing to forget.

### Pull

The framework steps the cursor until a chunk is full, hands the chunk to the channel and steps it again - and
stops while the channel is over its write watermark.

```java
builder.get("/rows/{count}", new ChunkedJsonRestHandler(context -> new RowCursor(
        context.pathParameters().valueRequiredAsInt("count"))
)).withPathParameterDescriptions("count - How many rows to send");

final class RowCursor implements ChunkedJsonRestHandle.Cursor {
    @Override
    public boolean writeNext(JsonGenerator output) {
        if (!started) { started = true; output.startArray(); }   // left open: the framework ends the document
        // ... write a batch of rows ...
        return more;
    }

    @Override
    public void close() { /* release the underlying cursor */ }
}
```

`ChunkedJsonRestHandler` and `ChunkedTxtRestHandler` take cursors writing to a `JsonGenerator` or a
`LineAppendable`; `ChunkedRestHandler` takes a content type and a cursor writing raw bytes into the chunk's
`ByteBuf`, which reaches the channel without a copy. Content which is already a file or a stream needs no
cursor at all: `Result.ok(contentType, ChunkedInput<ByteBuf>)` takes Netty's `ChunkedFile`, `ChunkedNioFile`
or `ChunkedStream` directly.

`open(RestContext)` is where the request is validated: nothing has been sent yet, so anything thrown there
still becomes an ordinary error response. Once the first chunk is out the status is on the wire and a failure
can only truncate the body. `Cursor.close()` runs exactly once whatever the outcome - finished, threw,
disconnected, or given up on by the watchdog.

### Push

`next` answers `null` when there is nothing yet, which suspends the transfer rather than ending it; the next
`flush()` resumes it.

```java
final class ClockBody extends PushedResponseBody {
    private boolean due = true;

    private void onTick() {          // scheduled on context.executor(): runs on this channel's loop
        due = true;
        channel.flush();             // wakes the transfer, which asks next() again
    }

    @Override
    protected ByteBuf next(ByteBufAllocator allocator) {
        if (!due) {
            return null;
        }
        due = false;
        return ...;
    }

    @Override
    public void close() { tick.cancel(false); }   // called however the response ends
}

builder.get("/clock", (context, result) -> result.ok(TEXT_EVENT_STREAM, new ClockBody(context)));
```

`PushedResponseBody` answers the rest of `ChunkedInput`: the length nobody knows, the end which never comes by
itself, and the progress the stall watchdog reads.

Two differences from pull. `maxOpenCursors` does not count these - it counts what `ChunkedRestHandler` and its
siblings open, not what you hand to `Result.ok`. And `stallTimeoutMillis` measures time without a chunk, so it
has to exceed the gap between two of them.

### Settings

Built once, when the server is assembled, and handed to every `RestApiHandler`:

```java
ResponseChunks chunks = ResponseChunks.builder()
        .size(64 * 1024)          // bytes a chunk is filled to
        .stallTimeoutMillis(30_000)
        .maxOpenCursors(256)      // unlimited by default
        .build();

pipeline.addLast(new RestApiHandler(api, errorHandler, channelErrorHandler, chunks, observers));

bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,   // where the backpressure is read from
        new WriteBufferWaterMark(32 * 1024, 64 * 1024));
```

- **`size()`** is a floor, not a cap: the framework asks for more until the buffer has *crossed* it, so a
  chunk overshoots by up to one step. Memory is bounded by `size()` plus one step, and the step is the real
  knob - one that writes a hundred rows costs a hundred rows, whatever the collection behind it holds.
- **`stallTimeoutMillis()`** (30 s, zero to disable) - a response which has not got a single chunk out within
  it is abandoned and its connection closed. Counting chunks rather than bytes catches both the peer which
  stopped dead and the one reading at a trickle, and never punishes a response which is merely large. Without
  it a cursor - a snapshot, a file handle, a lock - is held for as long as a half-open connection lingers,
  which can be hours.
- **`maxOpenCursors()`** (unlimited by default) - a request which would open one cursor too many is answered
  `503` *before* its cursor is opened, so the resource is never taken. Unlimited because what a cursor holds
  is yours to know.

`chunks.openCursors()` reports how many are open right now, across every channel.

## Observing

The library keeps no metrics. It reports, and what that turns into is yours. One observer per request, made by
a factory:

```java
public class AccessLog implements HttpApiObserver {
    private HttpMethod method;
    private String uri;

    @Override
    public void onRequestReceived(ChannelHandlerContext ctx, HttpRequest request) {
        method = request.method();   // the only stage given these: copy what the later ones need
        uri = request.uri();
    }

    @Override
    public void onRequestNotRouted(RestException cause) { }

    @Override
    public void onRequestCompleted(HttpResponseStatus status, long bytes, long durationNanos) {
        log.info("{} {} {} {}b {}ns", method, uri, status.code(), bytes, durationNanos);
    }
}

new RestApiHandler(api, errorHandler, channelErrorHandler, chunks, AccessLog::new);
```

`onRequestCompleted` fires **exactly once per request**, in every form the response can take, so counting
requests never means adding two events up. `bytes` is the body as the source produced it, without the framing
the transfer encoding adds.

`RestApiObserver extends HttpApiObserver` adds the stages after routing - pass a `RestApiObserverFactory` to
get them:

```
not routed:     onRequestReceived -> onRequestNotRouted -> onRequestCompleted (404 | 405)
in one piece:   onRequestReceived -> onHandlingStarted  -> onRequestCompleted
handler failed: onRequestReceived -> onHandlingStarted  -> onResponseFailed -> onRequestCompleted
chunked:        onRequestReceived -> onHandlingStarted  -> onCursorOpened
                                  -> onChunkWritten*    -> onCursorClosed -> onRequestCompleted
refused (503):  onRequestReceived -> onHandlingStarted  -> onCursorRefused
                                  -> onResponseFailed   -> onRequestCompleted
```

Every method has a no-op default. Calls come from event loop threads, so do not block in them.

Nothing is repeated after the opening stages, and that is the point: the channel and the request come with
`onRequestReceived`, the `RestContext` with `onHandlingStarted`, and neither survives what follows. Copy what
a later stage needs - `context.pathExpression()` is a plain string and the label a metric wants, where the URI
is one of unboundedly many.

`newObserver()` may return a shared instance instead, and then telling the requests apart is yours. Return
null and the request is not observed at all - not even the clock is read for it.

## Runnable examples

In `newa-example`, package `io.github.green4j.newa.example.rest`:

- **`hello.HelloRestServer`** - the smallest thing that serves a route.
- **`chunked.ChunkedRestServer`** - pull: JSON and text rows, a gzipped download, the cursor limit, the
  observer.
- **`chunked.ScheduledChunkedRestServer`** - push: a clock sending the time once a second over server-sent
  events.
