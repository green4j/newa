# newa-rest

HTTP REST API routing and handlers built on Netty.

```
Client --> IdleConnectionHandler --> HttpServerCodec --> HttpObjectAggregator
           --> SingleHttpExchangeHandler --> RequestDeadlineHandler --> ResponseDeadlineHandler
           --> DecoderFailureHandler
           --> [CorsHandler] --> [your handlers] --> [HttpContentCompressor] --> RestApiHandler
```

`RestServer` assembles that pipeline and `NettyServerBuilder` the bootstrap under it, so a working server is
one line. Neither hides anything: they are made of the same public handlers, and the pipeline and the
bootstrap are still yours to take over the moment either needs changing - see [Starting a
server](#starting-a-server).

Files are a server of their own - `FileServer`, the same shape and the same `with...`, with a
`FileServerHandler` where the api handler is. Either one composes into the other through `withHandler`; see
[Serving files](#serving-files).

## Getting started

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");

builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");

RestApi api = builder.build();                       // or buildWithHelp(JsonHelp.factory())

new Life().run(() -> RestServer.start(api, 9009));   // serving GET /v1/hello/world
```

`getJson` / `getTxt` / `postJson` / ... register handles which render a response and return. `get` / `post` /
... register a full `RestHandle`, which is given the `Result` and may finish it later.

## Starting a server

The whole server, with everything at its default, is one call:

```java
NettyServer server = RestServer.start(api, 9009);                // on the loopback
NettyServer server = RestServer.start(api, "10.0.0.5", 9009);    // on one network
NettyServer server = RestServer.start(api, ANY_HOST, 9009);      // on every interface
```

**A server binds the loopback until it is told otherwise**, and that default is about safety rather than
convenience: a service nobody opened up is one no other machine can reach, so exposing it is a line
somebody has to write - the address to listen on, or `NettyServerBuilder.ANY_HOST` for every interface,
which is what Netty's own `bind(int)` would have done from the start. A null host is refused rather than
read as either: whichever way it was taken, the server would be listening somewhere nobody chose, and one
of the two answers is the whole machine.

Past that there are **two builders**, and they are about two different things. `RestServer` is what runs
*above* the socket; `NettyServerBuilder` is the socket itself.

**One: the pipeline.** Every one of these is optional, and none of them is about the api - what belongs to
the api is the `RestApiBuilder`'s.

```java
RestServer rest = RestServer.of(api)

        // what else runs in the pipeline
        .withHandler(() -> new AuthFilter())     // in front of the api handler, one per channel
        .withCompression()                       // off by default
        .withCors(corsConfig)                    // off by default, see Cross-origin requests
        .withResponseChunks(chunks)              // see Chunked responses

        // what an answer is, and who hears about it
        .withErrorHandler(new JsonErrorHandler())
        .withChannelErrorHandler(new StdErrChannelErrorHandler())
        .withObservers(observers)                // per request, see Observing
        .withConnectionObserver(connections)     // per connection, see Connections

        // what a request may be
        .withMaxContentLength(1024 * 1024)       // the request body, not its headers and not a response
        .withMaxInitialLineLength(4096)          // the request line: the method, the whole uri, the version
        .withMaxHeaderSize(8192)                 // the header block, all of it together

        // when a connection is given up on
        .withIdleTimeoutMs(60_000)               // on by default, see Idle connections
        .withRequestDeadlineMs(30_000)           // on by default, see Deadlines
        .withResponseDeadlineMs(30_000);         // on by default, see Deadlines
```

**Two: the socket.** The transport, the threads, the port and every channel option:

```java
NettyServerBuilder bootstrap = new NettyServerBuilder()
        .port(9009)
        .host(ANY_HOST)                          // every interface; the loopback by default
        .workerThreads(8)                        // a worker per core by default
        .writeBufferWaterMark(32 * 1024, 64 * 1024)
        .maxConnections(4096)                    // unlimited by default, see below
        .backlog(1024)                           // what the OS says by default
        .transport(Transport.auto());            // kqueue, epoll, or NIO
```

**They meet at `start`**, which hands the pipeline to the bootstrap and binds it:

```java
NettyServer server = rest.start(bootstrap);
```

The other direction is open too: `pipeline()` hands the same initializer to a `ServerBootstrap` of Netty's
own, which is what `rest.pipeline.PipelineRestServer` does.

`maxConnections` is what bounds file descriptors, and it is off unless you say a number: the right one is
what the process may open minus everything else it holds, which the deployment knows and this library does
not - a number invented here would cut working traffic quietly. A connection arriving above it is closed as
it arrives, without a byte written back, because writing a `503` means holding the descriptor through a write
and a flush at the moment there are none to spare, and it would not arrive reliably either. Overload is
therefore invisible to the peer and visible here: `ConnectionLimitHandler.refused()` counts it, and a
hand-written pipeline can put that handler at its own head. `backlog` is `SO_BACKLOG`, how deep the kernel
queues what has not been accepted yet; unset it is whatever the operating system allows, which is also the
most it allows.

`withHandler` puts a handler *in front of* `RestApiHandler`, which is the only place one can still act:
the api handler answers every request it sees, with a 404 when nothing routed, so nothing behind it would
ever run. That is where a filter goes, and where a `FileServerHandler` goes - it lands in front of the api
and behind the compressor, which is the placement that keeps `sendfile(2)`.

`Transport.auto()` uses kqueue on macOS and epoll on Linux when the matching Netty artifact is on the
classpath, and falls back to NIO in silence when it is not - nothing reports that you meant to run on epoll
and did not, so print `Transport.auto().name()` at startup if it matters. `Transport.nio()` asks for the
portable one on purpose, which is also what a GraalVM native image wants.

To keep this pipeline but bootstrap it yourself, hand `RestServer.pipeline()` to a `ServerBootstrap` of your
own. To change the pipeline, write it out - `rest.pipeline.PipelineRestServer` in `newa-example` is that,
and says which four things made it worth doing.

`NettyServer` is what you get back, and it is an `AutoCloseable` and nothing more: `port()` (the one thing
worth having after binding to port 0), `channel()`, `workerGroup()` for periodic work on the loops the
connections already live on, `close()`, and `whenEnded(Ender)` - see [Ending when the server dies by
itself](#ending-when-the-server-dies-by-itself).

What runs it is `Life`. It opens the server, parks the calling thread until the end is asked for, closes it,
and registers a JVM shutdown hook for the length of the run:

```java
new Life().run(() -> RestServer.start(api, 9009));
```

Note that the server is **opened by** `run`, not handed to it. That is what leaves no window: there is no
instant at which the server is accepting requests and nothing yet owns it.

### Idle connections

A connection on which nothing has been read and nothing has been written for a minute is closed. This is on
by default - `withIdleTimeoutMs(0)` turns it off, and any other value moves it.

What it takes back is a file descriptor: a connection which opened and never asked anything, a keep-alive
connection whose client walked away, and a peer which died without a FIN - the last being the one no amount
of correct client code prevents. Nothing else in the pipeline would ever close any of them.

It only reclaims descriptors from connections nobody is using, which is the common case and not the dangerous
one. Connections which are all busy at once cost one each until they are done, and that is what
`NettyServerBuilder.maxConnections` is for.

**Both directions count**, which is what makes it safe in front of a long response: a chunked response
still being written keeps its own connection alive however long it takes, and only a connection where
*neither* side has said anything is closed. A transfer counts while it is *moving*, not only when it
lands - one big file to one slow peer is a single write which completes at the end, and a timer waiting
for that completion would cut the download off in the middle of itself. Raise the timeout for a response
which suspends for longer than it while writing nothing.

**It judges neither of the two slow peers, and cannot.** All it knows is that bytes moved. A client
dribbling a header block a byte at a time is reading and writing all the while; a peer taking a response a
byte every ten seconds moves the outbound buffer every ten seconds. Both look busy from here, and both are
bounded by the pair below, which counts what actually arrived. Keep the idle timeout above them - it defaults
to twice their window - or the coarsest instrument takes the decisions of the precise ones.

The handler is `IdleConnectionHandler`, and it is public: a pipeline assembled by hand wants one first of
all, in front of the codec. Note that Netty's own `IdleStateHandler` is only half of it - it fires an
`IdleStateEvent` and closes nothing, so a pipeline with one and no handler for that event holds the
connection exactly as long as it would have without it.

Nothing is written back when it closes - the timeout is this server's own, and a connection nobody is using
has nobody waiting to read an explanation. A `ConnectionObserver` is told all the same, which is the only
trace the close leaves; see [Connections](#connections).

### Deadlines

A connection has two ends, and a peer can be slow at either. Both are bounded by the same idea - *what has
begun has this long to finish* - and by a pair of handlers standing together, directly behind the aggregator:

```java
RestServer.of(api)
        .withRequestDeadlineMs(30_000)    // on by default, 0 turns it off
        .withResponseDeadlineMs(30_000)   // on by default, 0 turns it off
        .start(9009);
```

**`withRequestDeadlineMs`** is what an idle timeout cannot be: the bound on how long a request may take to
arrive. nginx calls it `client_header_timeout`, Tomcat `connectionTimeout`, Node `headersTimeout`. Nothing
the peer sends extends it once it is running, so a request dribbled out a byte at a time runs out of it.
Every request of a keep-alive connection is judged, not only the first, and so is a connection which opens
and asks nothing. It covers the request whole, body included, which is what `withMaxContentLength` makes
honest: a server which raises that to take uploads raises this with it.

**`withResponseDeadlineMs`** is what judges a slow reader, on what reached the peer rather than on whether
anything moved:

- **nothing is timed while nothing is owed.** The clock starts on a write and stops once every write has
  landed, so a response which is merely slow to produce - a chunked one ticking once a minute, a suspended
  cursor - is never on it. What is timed is a peer which has been given something and is not taking it;
- **each write is given one window per 64K of it**, so a large response is not judged by the clock of a small
  one, and a peer taking a megabyte honestly is never near it;
- **a file renews its window every 64K that reaches the peer**, which is how a trickle is caught inside a
  transfer of any size - the one case where progress exists and means nothing.

Together the unit and the window are a floor on throughput: 64K per 30 seconds, about 2.2 KB/s, and the same
floor for a chunked response, a file and an ordinary one. Before this pair they were three different answers,
and an ordinary response had none at all.

The handlers are `RequestDeadlineHandler` and `ResponseDeadlineHandler`, both public and both in
`newa-common`: a pipeline assembled by hand adds them itself, *behind* the codec and the aggregator. That
placement is not a preference - the request half tells bytes which became a message from bytes which did not,
and in front of a decoder every read looks the same.

Both close silently, and both report to a `ConnectionObserver` first. For the request half that is the only
report there can be: no request arrived, so no observer of requests has anything to be told about.

### Cross-origin requests

Without `withCors` a cross-origin request is still served - the server neither knows nor cares which page
sent it - and the side effects of a simple `GET` or `POST` have happened by the time the response is written.
What is missing is the `Access-Control-` headers on it, so the browser refuses to let the page read an answer
it already holds. The request which never arrives is the preflighted one: the browser asks with `OPTIONS`
first, gets an answer without those headers, and does not send the real request at all.

`withCors` is what makes the answer readable. It takes Netty's own `CorsConfig` and puts a `CorsHandler` in
the pipeline, which does the whole protocol - the preflight, the `Access-Control-` headers,
`allowCredentials()`, `shortCircuit()`:

```java
RestServer.of(api)
        .withCors(CorsConfigBuilder.forOrigin("https://app.example.com")
                .allowedRequestMethods(HttpMethod.GET, HttpMethod.POST)
                .shortCircuit()                  // answer a wrong origin 403, rather than let it through
                .build())                        // without the headers which would let the page read it
        .start(9009);
```

It goes **in front of** the file handler, and that is what makes a file carry the headers too: the file
handler writes its response head from its own place in the pipeline, so only a handler nearer the front than
it ever sees one. The consequence to know about: a preflight `OPTIONS` is answered by the CORS handler and
never reaches the files or the api, so the `405 Allow: GET, HEAD` a file path gives an `OPTIONS` is no
longer what a browser sees.

A websocket handshake is not covered by any of this and does not need to be - there is no preflight on one
and no header to add to its response. It gets an `OriginPolicy` instead, which answers yes or no - and that
one, unlike this, is on by default; see `newa-websocket`.

### Ending it from a request

A `Life` is an `Ender` from the moment it is constructed, and that is the whole point: a `/shutdown` endpoint
has to be registered before the api is built, and the server does not exist until after that, so there is
nothing else for the handle to hold.

```java
final Life life = new Life();

apiBuilder.postJson("/shutdown", new JsonExecute(() -> life.end("Called by REST API")));

life.run(() -> RestServer.of(apiBuilder.build()).start(9009));
```

`end(...)` does no I/O: it releases `run` and returns, so the request handler is free immediately and the
closing happens on the thread which called `run`. That is not a detail. Closing a server from one of its own
event loops - which is where a request handler runs - makes that loop wait for a shutdown it is itself
holding up, and costs the full timeout every time.

It is also safe before the server is open, and idempotent: asked for first, nothing is started at all; asked
for while the server is being opened, it is honoured the instant opening returns.

### Ending when the server dies by itself

A server whose listening channel closed under it - rather than because it was closed - looks like nothing
from the outside: the port is gone, and whoever waits for the end goes on waiting. So the server is what
says it:

```java
server.whenEnded(ender);   // ender.end("Port 9009 closed"), once, when the channel closes
```

`Ender` is the same one-method interface a `/shutdown` endpoint is handed, so this is a lambda when what
ends the process is something of your own - a `CountDownLatch`, a container's callback, a test.

Under a `Life` there is nothing to write at all. `NettyServer` is `SelfEnding`, and `run` registers itself
with whatever it opens which is, so the close of the channel is the end of the `Life` - which is what keeps
a process from staying up owning a server that is no longer serving. Anything of your own joins that by
implementing `SelfEnding` rather than `AutoCloseable`.

Two things hold however it is used. The end is reported however it came about, `close()` included, and a
channel which closed before anybody asked to hear about it is reported just the same - both are why an
`Ender` is idempotent, and why the first cause given is the one reported. And it is reported on the
channel's event loop, which is the reason an `Ender` does no I/O.

### More than one server in one Life

A `Life` runs one resource, and `Life.all(...)` makes several into one - opened in the order given, closed
together when the end is asked for:

```java
final Life life = new Life();   // registered with the admin api's /shutdown before either server exists

life.run(Life.all(
        () -> RestServer.of(publicApi)
                .start(new NettyServerBuilder().port(9009).workerThreads(6)),
        () -> RestServer.of(adminApi)
                .start(new NettyServerBuilder().port(9010).host("127.0.0.1").workerThreads(1))));
```

It exists for the one thing a caller cannot do for itself: **a later opener which fails closes the servers
already opened**. Until the opener returns, the `Life` owns nothing, so a server bound beside one which then
failed to bind is a server nothing else would ever close. Closing is done **at once** rather than one after
another - servers share nothing, each shutting down event loop groups of its own - which keeps the worst
case at the timeouts of one close instead of the sum, and that is what a JVM shutdown hook has to fit into.
When the order of closing does matter, write an opener which closes them in the order they need. Openers
compose, so `Life.all(a, Life.all(b, c))` is an opener too, and the `run(opener, observer)` form is
unchanged: `onRunning` is the moment both are up.

None of this is about REST. An `Opener` returns an `AutoCloseable` - `SelfEnding` or not, which is all a
`Life` ever knows of it - so a `WsServer` mixes into the same call as readily as a second `RestServer`, and
a REST api on one port beside a WebSocket on another is the usual pair. A WebSocket server can also carry a
REST api on its own port instead, with no second server to run at all: see [One port for
both](../newa-websocket/README.md#starting-a-server).

**Any one of them ending by itself ends them all**, because each is `SelfEnding` and all of them are
registered with the `Life`: half of what was promised is not something to stay up serving.

One thing stays yours, because it is not a `Life`'s business:

- **Threads do not divide themselves.** Every `start()` makes event loop groups of its own and there is
  nothing to share them with, while `workerThreads` defaults to a worker per core - two per core on two
  servers left at the default. Say what each one gets, and count them all when budgeting render buffers.

`rest.pair.PairedRestServers` in `newa-example` is the whole of it, running.

## Routing

`RestApi` resolves each `FullHttpRequest` with `PathMatcher`; routes are grouped by method inside
`RestApiBuilder`.

- **Methods**: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD` and `OPTIONS`, each as the `xxx` / `xxxJson` /
  `xxxTxt` triple.
- **HEAD follows GET**: a `HEAD` with no endpoint of its own on that path is answered by the `GET` one, so
  every path served on `GET` answers `HEAD` too. The handler renders its response as usual and the codec
  drops the body, so the peer is told the length it would have been sent. Register `head...` only where the
  answer differs from running the `GET` - a length known without reading anything, say.
- **OPTIONS is routed like any other method**: only where an endpoint was registered for it, 405 otherwise.
  A CORS preflight never reaches it - `CorsHandler` answers that in front of the api, see
  [Cross-origin requests](#cross-origin-requests).
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

A `Result` which outlives `handle()` is the easiest one to finish twice - two callbacks, or a callback and a
timeout, each answering. **One request is answered once**: whatever sends the response ends the result, and
every call after it is dropped rather than written, because a second response would be read by the peer as
the answer to its next request. Whatever the dropped call was handed - a `ByteBuf`, a `ChunkedInput` - is
released, and the mistake goes to the `ChannelErrorHandler`, with the stack of the call that was dropped.
Nothing is thrown: the response which did go out is the correct one.

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

- **The request body** - `HttpObjectAggregator` caps it and nothing else does; a body past the cap is
  answered `413` and the connection closed. A REST server caps it at 1 MB, a file server at 64 KB, since a
  file server is sent `GET`s with nothing in them. The request line and the header block are the codec's own
  limits, 4096 and 8192 bytes, and each has its own knob - past them the answer is `414` or `431`:

  ```java
  RestServer.of(api).withMaxContentLength(2 * 1024 * 1024)             // largest request
                    .withMaxInitialLineLength(16 * 1024)               // longest uri
                    .withMaxHeaderSize(32 * 1024);                     // largest header block
  pipeline.addLast(new HttpObjectAggregator(2 * 1024 * 1024, true));   // the body, by hand
  ```

  An inbound limit bounds the largest buffer held at once. This server aggregates, so that buffer is the
  whole request; a WebSocket server holds a frame, so
  [its number is a frame](../newa-websocket/README.md#frame-and-handshake-limits).

  **A request body is never inflated** - `withCompression()` is outbound only. An `HttpContentDecompressor`
  added through `withHandler` lands behind the aggregator, so `maxContentLength` would then bound the
  compressed body alone: give the decompressor a maximum allocation and count it in the budget.

- **`N` is the connection count**, because every server built by `RestServer` or `FileServer` holds a
  connection to one unfinished response. HTTP/1.1 permits a client to send the next request without waiting
  for the answer to the previous one, and that is served to a depth of one: reads are paused until the final
  response content is written, and the one request the codec had already decoded from the same network read
  is kept and replayed after it, in order. A further request on top of that one closes the connection, which
  is what a pipelining client has to be ready for anyway. So a connection is charged two requests and one
  response, not one of each - which is what the estimates below count. The handler is
  `SingleHttpExchangeHandler`, public and in `newa-common` like the deadline handlers above it: a pipeline
  written out from `HttpServerCodec` upwards adds one itself, directly behind the aggregator. `WsServer` has
  one too, so an api sharing a port with a websocket is held to the same invariant - see
  [newa-websocket](../newa-websocket/README.md#starting-a-server).

- **Chunked responses** remain bounded globally too: cap the cursors so application work cannot open more
  producers than intended - a request past the cap is answered `503` before its cursor is opened:

  ```java
  ResponseChunks.builder().size(64 * 1024).maxOpenCursors(256).build();
  ```

- **Then size the process for it.** The limit has to cover heap plus direct memory plus metaspace, code
  cache and thread stacks:

  ```
  -XX:MaxDirectMemorySize=512m -Dio.netty.maxDirectMemory=536870912
  -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError
  ```

Refusing a connection is the cheap failure; being killed with every in-flight response is the expensive one.

### A shared process budget

`ServerMemoryBudget` turns the same estimate into dynamic admission when REST and file servers share a JVM:

```java
ServerMemoryBudget memory = ServerMemoryBudget.builder()
        .heapPercentage(70)
        .directMemoryPercentage(70)
        .build();

RestServer.of(api)
        .withMaxContentLength(2 * 1024 * 1024)
        .withMemoryBudget(memory, 8 * 1024 * 1024)
        .start(new NettyServerBuilder()
                .port(9009)
                .workerThreads(4)
                .minConnections(10)                       // optional guaranteed floor
                .maxConnections(100));                    // optional fairness ceiling

FileServer.of(files)
        .withMaxContentLength(8 * 1024)
        .withChunkSize(64 * 1024)
        .withMemoryBudget(memory)
        .start(new NettyServerBuilder()
                .port(9010)
                .workerThreads(2)
                .maxConnections(500));
```

The percentages are applied to `Runtime.maxMemory()` and Netty's effective direct-memory maximum once, when
the budget is built. Every active connection is covered by both estimates atomically; whichever capacity
would be crossed refuses admission. Closing returns both reservations, so capacity which is not guaranteed
to a server follows traffic between them.

For REST the estimate includes the configured request maximum twice - the request being answered and the
one the exchange gate may be holding behind it - and the larger of the ordinary response estimate passed
with the budget or the chunked response backlog. The response estimate does not cap a
handler's output - it states the largest ordinary response buffer the admission assumption is based on,
including the result of custom output transformations. For files the pumped path is budgeted as the
effective write watermark plus two chunks, whether this pipeline currently uses zero-copy or not: a file is
as long as it is, so `withChunkSize` and the watermark are what bound a file server rather than any
response size. The
estimator adds the source and a conservative encoded-size bound when built-in compression is enabled.
Staging introduced by custom handlers still belongs in `withAdditionalMemoryEstimate`.
`withAdditionalMemoryEstimate` adds observer, handler and application-owned memory which neither server can
derive.

`minConnections` and `maxConnections` are both optional (`0` leaves that bound unset). A minimum reserves
`minConnections × estimate` when the server registers, guaranteeing that many admissions but making the
unused reservation unavailable to the other servers; registration fails if the floor does not fit the
capacity remaining at that moment. A maximum remains an independent fairness ceiling. Admissions above the
floor share whatever capacity remains.

Current totals are available from `memory.snapshot()`, and current per-server figures from
`NettyServer.memoryRegistrationSnapshot()`. The optional budget observer reports lifecycle, admission,
release and reasoned refusal events for logs and metrics. Each event carries the server's
`ServerMemoryBudget.Registration`; connection-limit refusals use `CONNECTION_LIMIT`. The budget deliberately
retains no historical counters.

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
  does not bound them and the cgroup limit does, so a container sized from the heap alone is killed for its
  RSS instead of failing with `OutOfMemoryError` - the flags are in [Memory budget](#memory-budget) above.

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
itself, and the progress an observer is told.

One difference from pull: `maxOpenCursors` does not count these - it counts what `ChunkedRestHandler` and its
siblings open, not what you hand to `Result.ok`. The gap between two ticks needs no thought at all, however
long it is: `withResponseDeadlineMs` times what has been written and not taken, and between ticks nothing has
been written.

### Settings

Built once, when the server is assembled, and handed to every `RestApiHandler`:

```java
ResponseChunks chunks = ResponseChunks.builder()
        .size(64 * 1024)          // bytes a chunk is filled to
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
- giving up on a peer which is not taking the chunks is not here any more: it is
  [`withResponseDeadlineMs`](#deadlines), in the pipeline, where a file and an ordinary response are judged
  by the same rule. Without it a cursor - a snapshot, a file handle, a lock - is held for as long as a
  half-open connection lingers, which can be hours.
- **`maxOpenCursors()`** (unlimited by default) - a request which would open one cursor too many is answered
  `503` *before* its cursor is opened, so the resource is never taken. Unlimited because what a cursor holds
  is yours to know.

`chunks.openCursors()` reports how many are open right now, across every channel.

## Slow consumers

Everything here reads one signal - Netty's write watermark, which is what `Channel.isWritable()` answers -
and what is built on it is layered rather than alternative. A peer which falls behind meets each layer in
turn:

| layer | what happens | default |
|---|---|---|
| **pace** | the cursor is not stepped while the channel is over its watermark, so a chunked response takes exactly as long as its peer takes and costs one chunk of memory while it does | always |
| **give up** | a write which has not reached the peer within its window - one window per 64K of it - is given up on and its connection closed, whether it is a chunk, a file or an ordinary response | 30 s, see [Deadlines](#deadlines) |
| **refuse** | a request which would open one cursor too many is answered `503` before its cursor is opened | unlimited |
| **backstop** | a connection where nothing at all has moved in either direction is closed, whatever it was or was not doing | 60 s, see [Idle connections](#idle-connections) |

The first three know what they are protecting and measure exactly that - what reached the peer, and cursors
held. The last one knows only that bytes moved, which is why it is the outermost and the loosest, and why its
timeout has to stay above theirs: set it below and the coarsest instrument takes the decisions of the precise
ones.

**Nothing is ever dropped.** A response here is all or nothing - half a JSON document is not a smaller JSON
document - so pacing is the only correct answer to a peer which cannot keep up, and the only question left is
how long to keep pacing before giving up. That question is the whole of `withResponseDeadlineMs`.

**`newa-websocket` answers it differently on purpose**, which is worth knowing if you use both. Nothing is
paced there: the question is settled on the *first* frame which cannot go out, by closing the session or -
with `withSkipOnBackPressure()` - by dropping that frame and refilling the gap from a snapshot once the
channel drains. Neither module is the more careful one. A fan-out frame is perishable and the next one is
already better, so waiting buys nothing and holding it costs memory per subscriber; a response here has no
successor, and giving up on it throws away work already done. Dropping is on offer there and not here for
the same reason: it is only ever safe where something can rebuild what was dropped. See [Slow
consumers](../newa-websocket/README.md#slow-consumers).

## Errors

Two things happen when a request ends badly, and they are separate on purpose: something is **rendered** for
the client, and something is **reported** for a log or a metric. The first is an `HttpErrorHandler`, the second
an `HttpObserver`. A response never carries what only a log should have.

### What the client is told

`HttpErrorHandler` is the single funnel: routing which refused the request, a handler which threw, a handler
which called `result.error(...)`, and the file server in front of the API all end here, and nothing else writes
an error body. It has one method, so a whole set of pages is one lambda:

```java
byte[][] pages = errorPages();          // rendered once, when the server is assembled

RestServer.of(api)
        .withErrorHandler(error -> new DefaultFullHttpResponseContent(
                TEXT_HTML, pages[error.status().code()], 0, pages[error.status().code()].length));
```

The status is `error.status()` and is not the handler's to choose; what it returns is the body and the headers
describing it. One method rather than one per error because the set of errors is open - see below. A page
which renders `PathNotFoundException.path()` into markup has to escape it: that is the request's own path,
echoed back.

`JsonErrorHandler` (the default) and `TextErrorHandler` (the file server's, when it is built by hand) render
the status, the path or the method it was about, and the message the error carries.

### What is never rendered

An `InternalServerErrorException` is a failure rather than an answer: it always carries a `500` and the
exception which caused it. Its message is that exception's `toString()` - a type of the implementation and a
file path as often as not - and its stack trace names the classes the server is built from. **The default
handlers render none of it**: the client is told `Internal Server Error` and no more, and the cause goes to
`onResponseFailed` instead, where a log can have it.

Everything else carries a message written by hand, and that is rendered - a `400` says what was malformed, a
`503` says what the limit was.

```java
new JsonErrorHandler()                     // the default: says nothing of a failure
JsonErrorHandler.disclosingInternals()     // class, message, stack trace, every cause
TextErrorHandler.disclosingInternals()
```

`disclosingInternals()` is a development mode. It hands whoever asked the shape of the process, so it is
switched on in code, deliberately, and never by a system property something could carry into production.

### Your own exceptions

`HttpException` is the type an error response is made of, and it is yours to extend or to throw as it is:

```java
public class OutOfStockException extends HttpException {
    public OutOfStockException(String sku) { super(CONFLICT, "Out of stock: " + sku); }
}

throw new HttpException(CONFLICT, "Out of stock");   // or without a type of your own
```

Either reaches the `HttpErrorHandler` as it was thrown, with its status and its message, from any handle - they
all declare `throws HttpException`. The four this library throws itself are ordinary subclasses of it:
`PathNotFoundException` (404), `MethodNotAllowedException` (405), `BadRequestException` (400) and
`InternalServerErrorException` (500).

Anything thrown which is *not* an `HttpException` is a failure: it is wrapped, answered `500`, and nothing of
it is said. The safe answer is what the type system gives you, not what you remembered to do.

### What is reported, and where

Every error is reported exactly once, by the stage which knows most about it. Count responses with
`onRequestCompleted` - it is the one terminal event - and read the rest for what went wrong:

| what happened | where it goes |
|---|---|
| nothing served the request, `404` / `405` - API or files | `onRequestNotRouted(HttpException)` |
| a handler threw, or the file server failed | `onResponseFailed(status, cause)`, the cause as it was thrown |
| a file was cut short - truncated under the transfer, or the peer went away mid-download | `onResponseFailed(status, cause)`, with the status the head already promised, and the connection goes |
| a chunked response was refused, `503` | `onCursorRefused(openCursors)`, and `onResponseFailed` for the response |
| a chunked response stalled or was abandoned | `onCursorClosed(..., Outcome)` |
| the `HttpErrorHandler` itself threw | `ChannelErrorHandler`, and the connection goes: the response cannot be written |
| the channel failed | `ChannelErrorHandler` |
| the body was larger than `maxContentLength` | answered `413` in front of the api, and `onRequestRefused(status, cause)` |
| the request line or the headers were past their limits | answered `414` / `431` in front of the api and the connection closed, and `onRequestRefused(status, cause)` |
| the connection was closed by a rule of this server's own - idle, a deadline, the connection limit | `ConnectionObserver`, see [Connections](#connections) |

`onRequestNotRouted` and `onResponseFailed` are both on `HttpObserver`, so a plain observer sees the
failures of the API and of the file server alike. For a request which reached an endpoint it also falls
inside the handling bracket, so it is the failing close of that bracket as well - however the handle ended in
it, throwing or declaring.

## Observing

The library keeps no metrics. It reports, and what that turns into is yours. One observer per request, made by
a factory - `RestServer.withObservers(factory)`, or the `RestApiHandler` constructor which takes one. The
same factory reaches the file handler too, so a request is observed once wherever it is answered - and
`HttpObserver` is what a file request is observed through, every method of it: the file server serves,
refuses and fails through the same four stages an endpoint does.

```java
public class AccessLog implements HttpObserver {
    private HttpMethod method;
    private String uri;

    @Override
    public void onRequestReceived(ChannelHandlerContext ctx, HttpRequest request) {
        method = request.method();   // the only stage given these: copy what the later ones need
        uri = request.uri();
    }

    @Override
    public void onRequestNotRouted(HttpException cause) { }

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

`onRequestNotRouted` and `onResponseFailed` are on this interface too - see [Errors](#errors) for which
failure reaches which. `RestApiObserver extends HttpObserver` adds the stages after routing: pass a
`RestApiObserverFactory` where a `HttpObserverFactory` is taken and they arrive with the rest.

One rule orders all of it: **`onRequestCompleted` is outermost, brackets nest and never cross, and a failure
is reported before the close of its own level.**

```
in one piece:   onRequestReceived -> onHandlingStarted -> onHandlingFinished -> onRequestCompleted
handler failed: onRequestReceived -> onHandlingStarted -> onResponseFailed
                                                       -> onHandlingFinished -> onRequestCompleted
chunked:        onRequestReceived -> onHandlingStarted -> onCursorOpened -> onChunkWritten*
                                  -> onCursorClosed -> onHandlingFinished -> onRequestCompleted
refused (503):  onRequestReceived -> onHandlingStarted -> onCursorRefused -> onResponseFailed
                                                       -> onHandlingFinished -> onRequestCompleted
not routed:     onRequestReceived -> onRequestNotRouted -> onRequestCompleted (404 | 405)
refused:        onRequestReceived -> onRequestRefused   -> onRequestCompleted (413 | 414 | 431 | 400)
a file:         onRequestReceived -> onRequestCompleted
a file failed:  onRequestReceived -> onResponseFailed  -> onRequestCompleted
```

A refused request is the one bracket opened and closed from outside the api: the limits answer in front of
every handler, so nothing inside was ever asked. It is still one `onRequestCompleted`, which is what keeps a
count of requests a count of all of them. Two things are its own: `durationNanos` is zero, nothing at the
front of the pipeline knowing when the request began arriving, and for a `414` or a `431` the request handed
to `onRequestReceived` is the substitute the decoder built - `GET /bad-request`, told apart by
`request.decoderResult().isFailure()` - rather than what the peer sent.

A file fails in two shapes and both are that one line. Before anything was written it is answered as an
error, and the status of the two events is the same. After the head has gone there is nothing left to answer
with: `onResponseFailed` carries the status which was promised, `onRequestCompleted` carries the bytes which
really reached the channel - fewer than the `Content-Length` the peer was given - and the connection is
closed rather than left carrying a response which will not end. A response present in `onRequestCompleted`
and absent from `onResponseFailed` is a response the peer got whole.

That holds by construction rather than by timing, which is the point of it. A write from the event loop
completes its listener inline, so a whole cursor can be opened, drained and closed before the handle has
returned - and on a peer which reads slowly, none of it has. `onHandlingFinished` fires immediately before
`onRequestCompleted` and only for a request which was routed, so neither ordering is visible to an observer.
Its place being fixed, it does not say *when* the handle returned: it is where you take back what you put
aside at `onHandlingStarted`.

It is handed the same `(status, bytes, durationNanos)` as `onRequestCompleted`, and that is the one thing in
this interface which is said twice. What the bracket measures is a request which was routed, told apart by
the endpoint `onHandlingStarted` named - a latency per endpoint rather than per server - and an observer
which measures that and nothing else has all of it in the one call, with no field to carry the status
forward and no second reading of a clock.

Every method has a no-op default. Calls come from event loop threads, so do not block in them.

Nothing else is repeated after the opening stages, and that is the point: the channel and the request come
with `onRequestReceived`, the `RestContext` with `onHandlingStarted`, and neither survives what follows. Copy
what a later stage needs - `context.pathExpression()` is a plain string and the label a metric wants, where the URI
is one of unboundedly many.

`newObserver()` may return a shared instance instead, and then telling the requests apart is yours. Return
null and the request is not observed at all - not even the clock is read for it.

### Connections

An `HttpObserver` sees requests, and some of what a server does is not one. A connection refused by
`maxConnections`, one taken back by the idle timeout, a request which ran out of its deadline before it
arrived, a peer which stopped reading its response, a client pipelining deeper than one request: no request
carries any of them, and none is answered on the wire, so a peer cannot tell them from a server which died -
and neither can this side without being told.

```java
RestServer.of(api)
        .withObservers(observers)                        // per request
        .withConnectionObserver(new StdErrConnections()) // per connection, one for the whole server
        .start(bootstrap);
```

One instance serves the whole server - it is called from every event loop at once, so it must not block -
and every call happens before the close, while the channel still knows its peer. Handed to the bootstrap's
connection limit as well, so one observer covers all five. Under a `ServerMemoryBudget` the refusal is the
budget's to report instead, as `RefusalReason.CONNECTION_LIMIT`.

A pipeline assembled by hand passes it to the handlers itself: `ConnectionLimitHandler`,
`IdleConnectionHandler`, `RequestDeadlineHandler`, `ResponseDeadlineHandler` and `SingleHttpExchangeHandler`
each take one. So do the two which refuse requests - `ObservedHttpObjectAggregator` and
`DecoderFailureHandler` - but those take a `RefusedRequestObserver`, and `new RefusedRequestReporter(factory)`
is what turns the same factory `withObservers` was given into one. See `rest.pipeline.PipelineRestServer`.

## Serving files

Reading a file into a buffer to write it back out is the one thing `## Large responses` above says not to do.
`FileServerHandler` sends it from the page cache to the socket instead - `sendfile(2)`, which Netty offers as
`FileRegion` - so the bytes never enter the process. It is a handler of your pipeline, not a route of your
API, and a request whose path it does not own is passed on untouched:

```
Client --> HttpServerCodec --> HttpObjectAggregator --> FileServerHandler --> RestApiHandler
```

```java
FileSet files = FileSet.builder()
        .serve("/files", Paths.get("/var/www"),          // a tree: the rest of the path resolves under it
                PathMask.including("img/**", "*.css")
                        .and(PathMask.excluding("internal/**")))
        .file("/download/report.pdf", Paths.get("/var/data/report.pdf"))   // one file, named here
        .index("index.html")                             // without one, a directory is a 404
        .build();
```

`FileServer` is that handler with a pipeline around it, and reads exactly like `RestServer` - the same
`with...`, the same `pipeline()` and `start(...)`, both on the same `AbstractHttpServer`:

```
Client --> [IdleConnectionHandler] --> HttpServerCodec --> HttpObjectAggregator
       --> [RequestDeadlineHandler] --> [ResponseDeadlineHandler] --> DecoderFailureHandler
           --> [CorsHandler] --> [HttpContentCompressor] --> FileServerHandler --> [your handlers]
           --> FilesOnlyHandler
```

```java
FileServer.start(files, 9012);                           // the whole file server
FileServer.of(files)                                     // and with an api sharing the port
        .withHandler(() -> new RestApiHandler(api, errors, channelErrors))
        .start(9012);

RestServer.of(api)                                       // or the other way round: an api which also
        .withHandler(() -> new FileServerHandler(        // serves files
                files, errors, channelErrors, observers))
        .start(9009);

pipeline.addLast(new FileServerHandler(files));          // the same by hand, in initChannel
```

Which of the two you start from decides where the compressor may go, and that is not a detail:
`RestServer.withCompression()` places one *behind* everything `withHandler` added, where it compresses what
the api returns and never sees a file, so `sendfile(2)` survives. `FileServer.withCompression()` places one
*in front of* the file handler - the only place from which a file can be compressed at all - and pays
`sendfile(2)` for it. Off by default on both.

`FilesOnlyHandler` ends a file server's pipeline, because `FileServerHandler` passes on what it does not own
and a request nothing answered would otherwise sit at the end of the pipeline holding its connection open. It
answers with the file handler's own `404`, message included: a path which is not served and a file which may
not be served have to look the same, or the shape of the answer says which prefixes are served and which
files are being kept back.

A channel which fails ends at whichever handler catches it: the file handler reports the cause to its own
`ChannelErrorHandler` and closes the connection, which is what releases a file still being written. It does
not pass the event on, so composing the two means giving the file handler and the API handler the same
`ChannelErrorHandler` and the same `HttpErrorHandler` - `FileServer` does that for the handlers it builds
itself, and the one you hand to `withHandler` is yours to construct with them. `new FileServerHandler(files)`
alone prints what is not an `IOException` to stderr and renders its errors with a plain `TextErrorHandler`,
which says nothing of a failure beyond its status.

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
  `416`, anything odder is ignored and the whole file sent.
- **`Last-Modified` and a strong `ETag`** on everything, the tag being when the file changed and how large it
  is - a server which sends from the page cache does not read every file to hash it. `If-None-Match` answers
  `304` and is asked *first*: an `If-Modified-Since` sent beside a tag is not looked at. `If-Range` decides
  whether a `Range` may be answered at all - a peer resuming a download of a file which has changed under it
  gets the new one whole rather than ten bytes spliced into what it kept. The tag is strong on purpose;
  a weak one may not be ranged against, so it would silently cost every resumed download its range.
- **`x-content-type-options: nosniff`** on every response, the `404` of `FilesOnlyHandler` included. The type
  of a file is what the `FileSet` says it is; a browser sniffing one of its own out of the first bytes is how
  something uploaded as a picture comes to be run as a script.

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

Put it in front instead - which is what `FileServer.withCompression()` does, and the only way a file gets
compressed at all - and every file falls back to being pumped: correct, just slower. That path installs a
`ChunkedWriteHandler` once per channel and reads on the event loop, so set
`ChannelOption.WRITE_BUFFER_WATER_MARK` - that is where its backpressure comes from. A transfer whose peer
stops taking it is given up on either way, after `FileServer.withResponseDeadlineMs` - the pumped path is
judged a chunk at a time, and `sendfile(2)` by the bytes the region reports, both against the same window.

Reading on the event loop is a stall the other connections of that loop pay for whenever a page is not in the
cache. `FileServer.withReadExecutor(executor)` - the last argument of `new FileServerHandler(...)` by hand -
moves the read to threads of yours:

```java
ExecutorService reads = Executors.newFixedThreadPool(4);

new Life().run(Life.all(
        () -> reads::shutdown,                   // the pool is not the server's to close
        () -> FileServer.of(files)
                .withCompression()               // which is what put the files on this path
                .withReadExecutor(reads)         // and this is what keeps them off the loop
                .start(9012)
));
```

One chunk is read *ahead*, while the one before it is being written, so a thread of that pool is held for one
`read(2)` and never for a transfer: the pool bounds the reads in flight, not the downloads, and a peer which
stops taking the response stops asking for reads. The threads are yours and nothing here shuts them down,
which is what the opener above is for - the pool then ends exactly where the server does.

It is not the default, and for the assets of a page it should not be: those sit in the page cache, where a
read is a memcpy and the hop to another thread costs more than the read itself. It is for files large or cold
enough to be waited for. Note what it does not cover - `sendfile(2)` blocks on a cold page too, and so do the
`stat` and `open` every request begins with; this moves the reading of the body, which on that path is all of
the file and most of the waiting.

## Runnable examples

In `newa-example`, package `io.github.green4j.newa.example.rest`:

- **`hello.HelloRestServer`** - the smallest thing that serves a route, plus a `/shutdown` which stops it.
- **`pair.PairedRestServers`** - two servers on two ports run by one `Life.all(...)`: a public api and an
  admin api on the loopback, and either one dying alone ends both.
- **`chunked.ChunkedRestServer`** - pull: JSON and text rows, a gzipped download, the cursor limit, the
  observer.
- **`chunked.ScheduledChunkedRestServer`** - push: a clock sending the time once a second over server-sent
  events.
- **`errors.ErrorsRestServer`** - both halves of an error: a page of your own for every status, an exception
  of your own carrying a `409`, and a `500` whose cause reaches the observer while the client is told the
  status and no more. `curl` lines to try are printed at startup.
- **`pipeline.PipelineRestServer`** - the same server as `files.SimpleFileServer` with the bootstrap and the
  pipeline written out by hand: the transport and the groups chosen directly, `SO_BACKLOG`, an
  `IdleStateHandler`, and a compressor placed in front of the file handler. Run it beside `SimpleFileServer`
  and ask both for `/v1/zero-copy`: they answer the opposite, which is the cost of that one placement.

And in `io.github.green4j.newa.example.files`:

- **`SimpleFileServer`** - files from the page cache, filtered, with ranges and an index, started with
  `FileServer`, and a REST API handed to `withHandler` answering everything the files do not own.

All but `pipeline.PipelineRestServer` are started with `RestServer` or `FileServer`; that one is the reason
the manual path is documented.
