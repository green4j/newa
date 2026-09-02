# newa-rest

HTTP REST API routing and handlers built on Netty.

```
Client --> HttpServerCodec --> HttpObjectAggregator --> [FileServerHandler] --> [HttpContentCompressor]
           --> RestApiHandler
```

`RestServer` assembles that pipeline and `NettyServerBuilder` the bootstrap under it, so a working server is
one line. Neither hides anything: they are made of the same public handlers, and the pipeline and the
bootstrap are still yours to take over the moment either needs changing - see [Starting a
server](#starting-a-server).

## Getting started

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");

builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");

RestApi api = builder.build();                       // or buildWithHelp(Json_Help.factory())

new Life().run(() -> RestServer.start(9009, api));   // and it is serving
```

`getJson` / `getTxt` / `postJson` / ... register handles which render a response and return. `get` / `post` /
... register a full `RestHandle`, which is given the `Result` and may finish it later.

## Starting a server

`RestServer.start(port, api)` is the whole server. Anything it needs told is a `with...` on the builder form,
and everything below the pipeline stays on `NettyServerBuilder`:

```java
NettyServer server = RestServer.of(api)
        .withCompression()                       // off by default
        .withFiles(fileSet)                      // served in front of the api
        .withResponseChunks(chunks)              // see Chunked responses
        .withObservers(observers)                // see Observing
        .withErrorHandler(new JsonErrorHandler())
        .withChannelErrorHandler(ChannelErrorHandler.printingToStdErr())
        .withMaxContentLength(65536)
        .withHandler(() -> new AuthFilter())     // in front of the api handler, one per channel
        .start(new NettyServerBuilder()
                .port(9009)
                .host("127.0.0.1")               // every interface by default
                .workerThreads(8)                // a worker per core by default
                .writeBufferWaterMark(32 * 1024, 64 * 1024)
                .transport(Transport.auto()));   // kqueue, epoll, or NIO
```

`withHandler` puts a handler *in front of* `RestApiHandler`, which is the only place one can still act:
the api handler answers every request it sees, with a 404 when nothing routed, so nothing behind it would
ever run.

`NettyServer` is what you get back, and it is an `AutoCloseable` and nothing more: `port()` (the one thing
worth having after binding to port 0), `channel()`, `workerGroup()` for periodic work on the loops the
connections already live on, and `close()`.

What runs it is `Life`. It opens the server, parks the calling thread until the end is asked for, closes it,
and registers a JVM shutdown hook for the length of the run:

```java
new Life().run(() -> RestServer.start(9009, api));
```

Note that the server is **opened by** `run`, not handed to it. That is what leaves no window: there is no
instant at which the server is accepting requests and nothing yet owns it.

### Ending it from a request

A `Life` is an `Ender` from the moment it is constructed, and that is the whole point: a `/shutdown` endpoint
has to be registered before the api is built, and the server does not exist until after that, so there is
nothing else for the handle to hold.

```java
final Life life = new Life();

apiBuilder.postJson("/shutdown", new Json_Execute(() -> life.end("Called by REST API")));

life.run(() -> RestServer.of(apiBuilder.build()).start(9009));
```

`end(...)` does no I/O: it releases `run` and returns, so the request handler is free immediately and the
closing happens on the thread which called `run`. That is not a detail. Closing a server from one of its own
event loops - which is where a request handler runs - makes that loop wait for a shutdown it is itself
holding up, and costs the full timeout every time.

It is also safe before the server is open, and idempotent: asked for first, nothing is started at all; asked
for while the server is being opened, it is honoured the instant opening returns.

`Transport.auto()` uses kqueue on macOS and epoll on Linux when the matching Netty artifact is on the
classpath, and falls back to NIO in silence when it is not - nothing reports that you meant to run on epoll
and did not, so print `Transport.auto().name()` at startup if it matters. `Transport.nio()` asks for the
portable one on purpose, which is also what a GraalVM native image wants.

To keep this pipeline but bootstrap it yourself, hand `RestServer.pipeline()` to a `ServerBootstrap` of your
own. To change the pipeline, write it out - `rest.pipeline.PipelineRestServer` in `newa-example` is that,
and says which four things made it worth doing.

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

## Memory budget

CPU divides itself: give a container fewer cores and everything simply gets slower. Memory does not - past the
limit the process is dead, by `OutOfMemoryError` or by the cgroup killer. So bound it explicitly, from the
largest exchange you are willing to serve, and refuse what does not fit:

```
peak  ~  N x (largest request + largest response)     in flight, direct
       + workers x render buffer                      per event loop, max(64 KB, largest response x 1.5)
N     =  (budget - workers x render buffer) / (largest request + largest response)
```

At 2 MB of request, 8 MB of response, 4 workers and a 512 MB direct budget: the render buffers are 4 x 12 MB =
48 MB, leaving 464 MB, so `N` is about 46 exchanges in flight. What enforces it:

- **The request** - `HttpObjectAggregator` caps it and nothing else does:

  ```java
  RestServer.of(api).withMaxContentLength(2 * 1024 * 1024);            // largest request
  pipeline.addLast(new HttpObjectAggregator(2 * 1024 * 1024, true));   // the same, by hand
  ```

- **`N` is the connection count** - one request in flight per keep-alive connection - and capping it is
  yours, in the initializer (which is one reason to write the pipeline out rather than take
  `RestServer.pipeline()`):

  ```java
  if (open.incrementAndGet() > 46) { ch.close(); return; }            // AtomicInteger open
  ch.closeFuture().addListener(f -> open.decrementAndGet());
  ```

- **Chunked responses** are not covered by `N`, so cap the cursors - a request past the cap is answered `503`
  before its cursor is opened:

  ```java
  ResponseChunks.builder().size(64 * 1024).maxOpenCursors(256).build();
  ```

- **Then size the process for it**:

  ```
  -XX:MaxDirectMemorySize=512m -Dio.netty.maxDirectMemory=536870912
  -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError
  ```

Refusing a connection is the cheap failure; being killed with every in-flight response is the expensive one.

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
- **Size the container for direct memory, not just the heap.** Response buffers live off-heap, where `-Xmx` does not bound them and the cgroup limit does; a container sized from the heap alone is killed for its RSS
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

RestServer.of(api)
        .withResponseChunks(chunks)
        .start(new NettyServerBuilder()
                .port(9009)
                .writeBufferWaterMark(32 * 1024, 64 * 1024));   // where the backpressure is read from
```

The same by hand, if the pipeline is yours:

```java
pipeline.addLast(new RestApiHandler(api, errorHandler, channelErrorHandler, chunks, observers));

bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
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
a factory - `RestServer.withObservers(factory)`, or the `RestApiHandler` constructor which takes one. The
same factory reaches the file handler too, so a request is observed once wherever it is answered:

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

## Serving files

Reading a file into a buffer to write it back out is the one thing `## Large responses` above says not to do.
`FileServerHandler` sends it from the page cache to the socket instead - `sendfile(2)`, which Netty offers as
`FileRegion` - so the bytes never enter the process. It is a handler of your pipeline, not a route of your
API, and a request whose path it does not own is passed on untouched:

```
Client --> HttpServerCodec --> HttpObjectAggregator --> FileServerHandler --> RestApiHandler
```

`RestServer.withFiles(fileSet)` puts it exactly there. Note that it also decides where a compressor goes:
`withCompression()` places one *behind* the file handler, where it compresses what the api returns and never
sees a file, so `sendfile(2)` survives. One in front costs it, silently - see below.

```java
FileSet files = FileSet.builder()
        .serve("/files", Paths.get("/var/www"),          // a tree: the rest of the path resolves under it
                PathMask.including("img/**", "*.css")
                        .and(PathMask.excluding("internal/**")))
        .file("/download/report.pdf", Paths.get("/var/data/report.pdf"))   // one file, named here
        .index("index.html")                             // without one, a directory is a 404
        .build();

RestServer.of(api).withFiles(files);                     // put in front of the API handler
pipeline.addLast(new FileServerHandler(files));          // the same by hand, in initChannel
```

- **Matching** is one walk of the path, longest prefix first, nothing copied out of it to compare.
  `/files/img/logo.png` is `img/logo.png` under the root of `/files`.
- **A filter is any rule about a file**, given the path relative to the root and the real `Path` it resolved
  to - the name, the size, the owner, anything. A root takes one or none; `FileFilter.and(...)` makes two into
  one. `PathMask` is the ready one: `?` a character, `*` a segment, `**` any number of them.
- **Everything refused is refused the same way.** Missing, filtered out, outside the root, reached through
  `..`, an encoded `..` or a symbolic link: all `404`, so asking cannot tell them apart. Encoded separators
  are refused outright, filters are asked about the name the file system answers to rather than the one the
  request spelled, and a file a more specific mapping serves has to be asked for by that mapping's path.
- **The file is opened before a header is written**, and answered from the descriptor rather than the path.
  One which cannot be opened is a `404` while that can still be said honestly; one replaced or unlinked
  mid-response is still the file which was measured; one *truncated* after it was measured cannot keep the
  `Content-Length` it promised, so the connection is closed rather than left never ending.
- **`GET` and `HEAD`**, `405` with `Allow` otherwise. A single-range `Range` gets `206`, a range past the end
  `416`, anything odder is ignored and the whole file sent. `Last-Modified` always, `If-Modified-Since` `304`.

### Zero-copy, or not

A `FileRegion` skips every outbound handler which wants the bytes, and not every channel can write one. The
handler asks once per connection - none of this changes under a live channel - and otherwise reads the file
through NIO, a chunk at a time and no faster than the peer takes it. Same response, different cost.

`FileServerHandler.zeroCopySupported(channel)` is false when the channel is not a socket, or an `SslHandler`
or an `HttpContentEncoder` stands **between the handler and the socket**. Only that stretch counts, which is
what lets compressed API responses and zero-copy files share a pipeline - a compressor added behind the file
handler never sees a file:

```
Client --> HttpServerCodec --> HttpObjectAggregator --> FileServerHandler --> HttpContentCompressor --> RestApiHandler
```

Put it in front instead and every file falls back to being pumped: correct, just slower. That path installs a
`ChunkedWriteHandler` once per channel and reads on the event loop, so set
`ChannelOption.WRITE_BUFFER_WATER_MARK` - that is where its backpressure comes from. A transfer whose peer
stops taking it is given up on, either way, after `FileServerHandler.DEFAULT_STALL_TIMEOUT_MILLIS`.

## Runnable examples

In `newa-example`, package `io.github.green4j.newa.example.rest`:

- **`hello.HelloRestServer`** - the smallest thing that serves a route, plus a `/shutdown` which stops it.
- **`chunked.ChunkedRestServer`** - pull: JSON and text rows, a gzipped download, the cursor limit, the
  observer.
- **`chunked.ScheduledChunkedRestServer`** - push: a clock sending the time once a second over server-sent
  events.
- **`files.FileServer`** - files from the page cache, filtered, with ranges and an index, and the REST API
  behind them answering everything they do not own.
- **`pipeline.PipelineRestServer`** - the same server as `files.FileServer` with the bootstrap and the
  pipeline written out by hand: the transport and the groups chosen directly, `SO_BACKLOG`, an
  `IdleStateHandler`, and a compressor placed in front of the file handler. Run it beside `FileServer` and
  ask both for `/v1/zero-copy`: they answer the opposite, which is the cost of that one placement.

The other four are started with `RestServer`; that one is the reason the manual path is documented.
