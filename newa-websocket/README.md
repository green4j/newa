# newa-websocket

WebSocket sessions, broadcasting and subscription channels built on Netty.

```
Client --> HttpServerCodec --> HttpObjectAggregator --> RequestDeadlineHandler
           --> ResponseDeadlineHandler --> DecoderFailureHandler --> OriginCheckHandler
           --> [WebSocketServerCompressionHandler] --> WsApiHandler --> [your handlers]
           --> HandshakeOnlyHandler
```

`WsServer` assembles that pipeline and `NettyServerBuilder` the bootstrap under it, so a working server is one
line. Neither hides anything: they are made of the same public handlers, and the pipeline and the bootstrap
are still yours to take over the moment either needs changing - see [Starting a
server](#starting-a-server).

## Getting started

```java
WsApi api = new WsApiBuilder(1)          // version 1, so the handshake path is /ws/v1
        .withPathPrefix("ws")
        .withTextReceiver(receiver)            // what handles inbound text frames, see below
        .withPingIntervalMs(30_000)            // the keep-alive pair, and these are the defaults:
        .withReadTimeoutMs(90_000)             // ping an idle session, close one whose peer went silent
        .withObservers(AccessLog::new)         // optional, see Observing
        .build();

new Life().run(() -> WsServer.start(api, 9010));   // serving ws://127.0.0.1:9010/ws/v1
```

```java
Receiver.Text receiver = (session, message, last) -> session.sendText(message);
// withBinaryReceiver is not called here, so a binary frame is answered with a 1003 and the session ends
```

Clients connect to `ws://host:port` plus `api.websocketPath()` - here `ws://127.0.0.1:9010/ws/v1`. The
handshake path is the api's, and nothing repeats it. Leave both receivers out for a connection which only
ever listens - one which answers anything a client does send with a `1003`, since there is nothing there to
take it.

There is a receiver per type of frame - `Receiver.Text` and `Receiver.Binary`, each a lambda - and neither
is a no-op: a session takes the types it was given a receiver for and refuses the rest with a `1003`, so a
client which sends a type this end does not serve is told so rather than left waiting for an answer to a
frame which went nowhere.

The receivers belong to the `WsApi` for the same reason a rest handle belongs to a `RestApi`: they are what
handles what comes in. One consequence is worth knowing - a receiver is built before the api, so a receiver
which wants to call `api.broadcastText(...)` cannot simply capture it. Subclass `WsApi` and override
`textReceiver()` / `binaryReceiver()` when a receiver needs the api it belongs to.

`WsApiBuilder` builds an api of plain sessions; `SubscriptionWsApiBuilder` builds one which also keeps
what every session subscribed to - see below. Everything else on the builder is shared by both.

## Starting a server

The whole server, with everything at its default, is one call:

```java
NettyServer server = WsServer.start(api, 9010);
```

Past that there are **two builders**, and they are about two different things. `WsServer` is what runs
*above* the socket; `NettyServerBuilder` is the socket itself. What belongs to the api - the path, the ping
interval, the back pressure policy, the receivers, the observers - is the api builder's and appears in
neither.

**One: the pipeline.**

```java
WsServer ws = WsServer.of(api)

        // who may open a session, and what else runs in the pipeline
        .withOriginPolicy(OriginPolicy.allowing("https://app.example.com"))  // same-origin by default
        .withCompression()                       // permessage-deflate, off by default
        .withHandler(() -> new RestApiHandler(restApi, new JsonErrorHandler(), errors))

        // who hears about a channel which failed
        .withChannelErrorHandler(new StdErrChannelErrorHandler())

        // what a handshake and a frame may be
        .withMaxContentLength(65536)             // the handshake request body, not what a session sends
        .withMaxInitialLineLength(4096)          // its request line: the method, the whole uri, the version
        .withMaxHeaderSize(8192)                 // its header block - where a browser's cookies travel
        .withMaxFramePayloadLength(65536)        // and this is what a session sends, see the limits below

        // when a connection is given up on
        .withRequestDeadlineMs(30_000)           // on by default, see Deadlines
        .withResponseDeadlineMs(30_000);         // on by default, see Deadlines
```

**Two: the socket.**

```java
NettyServerBuilder bootstrap = new NettyServerBuilder()
        .port(9010)
        .host("127.0.0.1")                       // every interface by default
        .workerThreads(8)                        // a worker per core by default
        .maxConnections(4096)                    // unlimited by default
        .writeBufferWaterMark(low, high);
```

**They meet at `start`**, which hands the pipeline to the bootstrap and binds it:

```java
NettyServer server = ws.start(bootstrap);
```

`maxConnections` bounds the file descriptors this server holds, which a fan-out server is the likeliest of
these to run out of: a session costs one for as long as it is subscribed. It is off unless a number is given -
that number belongs to the deployment - and a connection above it is closed as it arrives, counted by
`ConnectionLimitHandler.refused()` and told nothing.

**One port for both.** `withHandler` puts a handler *behind* `WsApiHandler`, which is where a request that is
not the handshake ends up - the handshake handler passes on a uri it does not recognise. So a `RestApiHandler`
there serves the REST api on the websocket's port. Pass the handler, never `RestServer.pipeline()`: the codec
and the aggregator are already in front of it, and a second pair would decode everything twice. Behind
whatever you add stands a `HandshakeOnlyHandler`, which closes a connection nothing answered - see
[Errors](#errors).

`NettyServer` is what you get back, an `AutoCloseable` and nothing more: `port()`, `channel()`, `close()`,
and `workerGroup()` - which is where a periodic broadcast belongs, on the loops the sessions it writes to
already live on:

```java
server.workerGroup().scheduleWithFixedDelay(
        () -> api.broadcastText("tick"), 5, 5, TimeUnit.SECONDS);
```

What runs it is `Life`: `new Life().run(() -> ...)` opens the server, parks this thread until the end is
asked for, closes it, and registers a JVM shutdown hook for the length of the run. The server is opened by
`run` rather than handed to it, so there is no instant at which it is serving and nothing owns it. A `Life`
is an `Ender` from the moment it is constructed, so it is also what an endpoint or a signal handler calls to
ask for that end - see [Ending it from a request](../newa-rest/README.md#ending-it-from-a-request).

Nothing here needs a `Life`, though. A `NettyServer` reports the close of its own listening channel to any
`Ender` - `server.whenEnded(ender)` - which is what a server run some other way is ended by: see [Ending
when the server dies by itself](../newa-rest/README.md#ending-when-the-server-dies-by-itself).

To keep this pipeline but bootstrap it yourself, hand `WsServer.pipeline()` to a `ServerBootstrap` of your
own. To change the pipeline, write it out - `ws.pipeline.PipelineWsServer` in `newa-example` is that.

### Origins

**The `Origin` of a handshake is read, and by default only this server's own is let in.** The same-origin
policy does not cover a websocket handshake: a page on any site may open one here, and the browser sends the
cookies of *this* origin with it - what a `fetch` would have been refused, a `new WebSocket(...)` is not. All
the browser does is say who opened it, in the `Origin` header, so a server which authenticates by cookie and
reads nothing is one anybody's page can read through. That is why `WsServer` checks with
`OriginPolicy.sameOrigin()` until it is given something else, and why opening it up is a line somebody has to
write:

```java
WsServer.of(api)
        .withOriginPolicy(OriginPolicy.allowing("https://app.example.com"))  // the page is served elsewhere
        .start(9010);

WsServer.of(api)
        .withOriginPolicy(OriginPolicy.any())        // a gateway in front has already decided this
        .start(9010);
```

Same means the `Origin` names the host the request was addressed to - its `Host` header, compared without
regard to case and with the port of the scheme filled in where one side left it out. The scheme itself is
**not** compared: behind a TLS terminator this server sees plain HTTP and does not know its own, so the
`https` of the page which opened the session would never match. What the check closes is another origin using
the browser's credentials; the channel is TLS's to defend.

`sameOrigin()` accepts a request carrying **no** `Origin` at all, and so does `allowing(...)` - a browser
always sends one, so its absence says the caller is not a browser and is not what this defends against, and
refusing it would break every service, load generator and test while defending nothing. `strictly(...)` is
for a server only ever reached by a browser. `allowing` and `strictly` *replace* the default rather than add
to it; `sameOrigin().or(allowing(...))` keeps both. A refusal is answered `403`, the connection is closed,
and a `ForbiddenOriginException` goes to the `ChannelErrorHandler`. A pipeline assembled by hand gets none of
this until it adds an `OriginCheckHandler` itself.

This is not CORS and does not become it - there is no preflight on a handshake and no `Access-Control-`
header on its response, so the answer is yes or no. The rest api answers the browser protocol proper; see
`RestServer.withCors` in `newa-rest`.

### Deadlines

The two handlers `newa-rest` uses stand here too, and they bound the two things a session's own timers do
not: **`withRequestDeadlineMs`** what has begun arriving, **`withResponseDeadlineMs`** what has been written
and is not being taken. Both are on by default at thirty seconds, both take 0 to turn off, and neither is
armed while nothing is happening - a session quiet in both directions is watched by its ping and
`readTimeoutMs` and by nothing else.

**The window before the handshake is what the first one is really for.** A session pings what has gone quiet
and closes what has said nothing for `readTimeoutMs`, but a session begins at the handshake: before it there
is no ping interval, no read timeout and nothing at all on a timer. Netty's own handshake timeout does not
help either - that clock starts when the handshake *request* arrives, so a connection which opens and sends
nothing is not covered by it. Afterwards the same handler judges a half-arrived frame, so nothing is taken
out of the pipeline and nothing has to be sized around the ping interval. A session deliberately run without
pings, `withPingIntervalMs(0)`, which the measuring instruments do, is not touched by it.

**The second one is the peer which stops taking frames.** Nothing is queued for it - a frame the channel
cannot take is skipped or fails the session, see [Slow consumers](#slow-consumers) - so what this bounds is
the connection itself, which the session's `readTimeoutMs` would otherwise be the only thing to reach.

Both handlers are `newa-common`'s and public: a pipeline assembled by hand adds them itself, behind the
aggregator.

### Frame and handshake limits

**Frames come in at 64 KB** unless `withMaxFramePayloadLength` says otherwise. The `HttpObjectAggregator` in
front bounds the *body* of the handshake request and nothing after it, so `withMaxContentLength` is not this
and this is not it. A frame past the limit is answered with close status `1009` and the connection goes.
Neither number is what an outbound frame is measured against.

**The handshake's headers are the codec's**, 4096 bytes of request line and 8192 of header block by default -
`withMaxInitialLineLength` and `withMaxHeaderSize`. That block is where a browser puts the cookies of this
origin, and it is the only HTTP request a session ever makes, so it is the number a handshake reaches first.
Past either, the answer is `414` or `431` and the connection closes - the decoder has stopped reading it
anyway.

### Beside a REST server

Two ways, and the first one is not this section: if both can live on one port, `withHandler` above puts a
`RestApiHandler` behind the handshake and there is one server to run.

They need two ports when they need different interfaces, different pools of workers, or different limits on
what a request may be - an admin REST api on the loopback beside a public WebSocket, say. Then it is one
`Life` and one opener made of both, in either order:

```java
final Life life = new Life();

life.run(Life.all(
        () -> WsServer.of(wsApi).start(new NettyServerBuilder().port(9010).workerThreads(6)),
        () -> RestServer.of(restApi).start(new NettyServerBuilder().port(9009).host("127.0.0.1"))));
```

`Life.all` knows nothing about either - an `Opener` returns an `AutoCloseable` and that is all it is - so a
pair of WebSocket servers, or a WebSocket server and something of your own with a `close()`, compose exactly
the same way. Either one dying by itself ends the other, since both are `SelfEnding`. What it buys, and the
one thing it deliberately leaves to you, are in
[newa-rest](../newa-rest/README.md#more-than-one-server-in-one-life); `rest.pair.PairedRestServers` in
`newa-example` runs a pair.

## Sessions

A `ClientSession` appears when the handshake completes and lives until the channel goes away. It is what
everything else is expressed in terms of:

```java
session.sendText("text");                     // encoded UTF-8, one frame
session.sendText(text, StandardCharsets.UTF_8);
session.sendText(byteBuf);                    // the session takes the buffer over
session.sendBinary(byteBuf);                  // the same, as a binary frame
session.ping(payload);
session.close();                              // idempotent
session.closeWith(POLICY_VIOLATION);          // and the peer is told which close this is
```

Every call which sends a buffer says which frame it makes of it, `Text` or `Binary`, and every one of them
takes the buffer over: it is released whatever happens to it - written, skipped because the session cannot
keep up, or dropped because the channel is gone. `send(CharSequence)` is the same as `sendText` and is there
because the `Sender` interface asks for that name.

`close()` closes the connection and says nothing, which a client reads as a `1006` - the status it invents
for a connection which went - and a `1006` is indistinguishable from the network dropping. That difference is
what a client's reconnect is built on: `1001` and it comes back at once, `1008` and it does not come back at
all, `1006` and it backs off. So say which one it is whenever this end knows - the statuses are Netty's
`WebSocketCloseStatus`: `NORMAL_CLOSURE` for a session which is simply over, `ENDPOINT_UNAVAILABLE` for a
server going down, `POLICY_VIOLATION` for a client which broke your protocol. The status goes out over a
channel which is open and writable - a frame put into a buffer nobody is draining would hold the session open
instead of ending it, so a peer which stopped reading gets the bare close it was going to get anyway - and
the session ends once the frame has left, without waiting for the close the peer answers with.

`Receiver.Text.text(session, message, last)` and `Receiver.Binary.binary(session, payload, last)` are called
on that session's event loop, one call per frame; ping, pong and close are answered by Netty underneath, and
a frame of a type the api was given no receiver for is answered with a `1003` and the session ends. What is
handed over is valid for the call and no longer - the decoder releases the frame the moment the call
returns, so a `ByteBuf` which has to outlive it needs a `retain()` and a `CharSequence` needs a copy.

`last` is what a message which arrives in pieces looks like: `false` says it goes on in the frames which
follow, `true` closes it. Nothing here holds the pieces - the frame limit of the pipeline bounds one frame,
not what several of them add up to - so a receiver which assembles them owes itself a limit of its own, and
one which does not want them may end the session as soon as it is handed a piece which is not the last. The
one thing which is put back together is a character cut in two by a frame boundary: a text piece always
arrives as whole characters.

Everything that touches a session must end up on its event loop. `session.executor()` hops back onto it and
`session.scheduler()` repeats work on it; never block either. `channel()`, `isClosed()`, `createTimeMs()`,
`lastReadTimeMs()` and `lastWriteTimeMs()` answer from any thread. `lastReadTimeMs()` is stamped by any
frame the peer sends, a pong included, not by text frames alone.

`putUserData` / `getUserData` hang application state off a session - but on an api built by
`SubscriptionWsApiBuilder` that slot belongs to the subscriptions layer. Use
`ClientSessionSubscriptions.putUserData` there, which is the same idea one level in.

The two keep-alive settings answer one question: is anybody still on the other end? `withPingIntervalMs`
creates the traffic - a fixed-delay task per session pings it when it has been idle and its channel is
writable, since a channel with data still pending needs no keep-alive - and `withReadTimeoutMs` is what
closes: nothing from the peer for that long and the session goes with a `1001`, through the same
`onSessionClosed` as any other ending. Neither works alone. Without the timeout a dead peer is noticed only
once the send buffer fills and the channel stops being writable, which is late and depends on how much you
send rather than on time; without the ping a perfectly healthy subscriber which does nothing but listen is
disconnected as dead, and that is most of a fan-out's clients. They default to 30 s and 90 s - three missed
pings - and either takes 0 to turn it off, which is what to do when the protocol above already carries a
heartbeat.

## Upgrading from an earlier version

**The keep-alive is new and it is on.** Sessions now carry a timer, and a peer silent for 90 s is
disconnected. `withPingIntervalMs(0).withReadTimeoutMs(0)` is the old behaviour.

**Text and binary are named.** Binary frames used to reach nothing at all - they were discarded in silence at
the end of the pipeline - and every call which sent a buffer made a text frame of it without saying so. Both
are named now, which is a break rather than an addition:

| was | is |
|---|---|
| `Receiver.receive(session, message)`, one lambda | `Receiver.Text.text(session, message, last)` and `Receiver.Binary.binary(session, payload, last)`, a lambda each, set with `withTextReceiver` / `withBinaryReceiver` |
| `session.send(ByteBuf)` / `send(CharSequence, Charset)` | `session.sendText(...)`, and `session.sendBinary(ByteBuf)` |
| `api.broadcast(...)` / `broadcastAndRelease(ByteBuf)` | `broadcastText(...)` / `broadcastTextAndRelease(ByteBuf)`, plus the `Binary` twins |
| `entity.publish(ByteBuf)` / `publishAndRelease(ByteBuf)` | `publishText(...)` / `publishTextAndRelease(...)`, plus the `Binary` twins |
| `entity.forEachSession(ByteBuf)` / `forEachSessionAndRelease(ByteBuf)` | `forEachSessionText(...)` / `forEachSessionTextAndRelease(...)`, plus the `Binary` twins |

`send(CharSequence)`, `publish(Consumer)` and `forEachSession(Consumer)` keep their names: the first is the
`Sender` interface's, and the other two never knew the type of the frame in the first place.

Two behaviours changed with them. A frame of a type the api was given no receiver for, and any frame at all
when it was given neither, is answered with a `1003` and the session ends - where before it was dropped in
silence. And a message which arrives in several frames is now handed over piece by piece, with `last` saying
which piece ends it; before, the first fragment of a text message was handed over as if it were the whole of
it and the rest was lost.

## Broadcasting

```java
api.broadcastText("hello");        // every open session
```

A broadcast walks the sessions without a lock and without making anything wait, and opening or closing a
session neither copies that list nor blocks a broadcast. `broadcastText(CharSequence)` encodes the text once
per session; the `ByteBuf` forms encode nothing at all - they give every session a retained duplicate of the
buffer. `broadcastTextAndRelease(ByteBuf)` takes the buffer over and releases it once the fan-out is done;
`broadcastText(ByteBuf)` leaves it to the caller, so the same buffer can be sent again or kept.
`broadcastBinary(ByteBuf)` and `broadcastBinaryAndRelease(ByteBuf)` are the same two on the binary side.

A fan-out is walked to the end. One session which throws - an observer of yours, an allocation, a channel
already torn down - costs that session and nothing else: it is reported through `onWriteFailed` and closed,
exactly as a failed write is, and the walk carries on to the next one. Abandoning it half way is not a state
anything could recover from, since the sessions already reached have the frame. The same holds for
`EntitySubscriptions.publish(Consumer)`, where the consumer is yours; `session.deliveryFailed(cause)` is how
a fan-out you write yourself does the same thing.

For anything with state behind it - a price, a room, an order book - broadcasting is the wrong shape: it sends
to everyone and tells a new session nothing about what it missed. That is what channels are for.

## Channels and subscriptions

```
Channel<S extends EntitySubscriptions>     one per stream: prices, order books, rooms
    `-- EntitySubscriptions                one per entity id, owns the sessions subscribed to it
            `-- ClientSession, ...
```

Build the api with `SubscriptionWsApiBuilder` - it attaches the per-session bookkeeping every channel expects,
and unsubscribes a session which goes away. Without it `Channel.subscribe` throws `IllegalStateException`.

### A channel

```java
final class Prices extends Channel<Prices.Price> {
    @Override
    protected Price newEntitySubscriptions(String entityId) {
        return new Price(entityId);
    }

    static final class Price extends EntitySubscriptions {
        private volatile String last;                    // written before publish(), read by the snapshot

        Price(String entityId) {
            super(entityId);
        }

        void publishValue(String value) {
            last = value;                                                  // the state first
            publish(session -> session.sendText(entityId() + '=' + value));  // then the fan-out
        }

        @Override
        protected void onClientSessionSubscribed(ClientSession session, long publicationSequence) {
            String snapshot = last;
            if (snapshot != null) {
                session.sendText(entityId() + '=' + snapshot);  // goes out before any concurrent update
            }
        }
    }
}
```

`onClientSessionUnsubscribed`, `onClientSessionRepeatedSubscriptionTry` and `onClosed` are there for the same
kind of work. `start()` and `close()` bracket the life of a channel, with `onStarted()` / `onClosed()` to
attach and release whatever feeds it.

### Subscribing

```java
channel.subscribe(session, "EURUSD");                          // creates the entity if there is none
channel.subscribeForKnownOnly(session, ids, unknownIds);       // never creates one
channel.unsubscribe(session, ids, notSubscribedIds);
```

Called on the session's event loop these run inline and their return value and out-lists mean something.
Called from any other thread the work is scheduled onto that loop, `0` comes back and the outcome is only
visible through the callbacks and the observer. Being on that loop is what puts a snapshot ahead of every
concurrent update.

A closed session subscribes to nothing, whether it was closed before the call or while the work was on its
way to the event loop. Everything that unsubscribes a session which goes away runs once, inside
`session.close()`, so a subscription landing after that would be held - and published to - forever.

`subscribeForKnownOnly` is the one to expose to clients: it answers "no such entity" instead of creating one
for every id a client cares to send.

### What a subscriber is promised

**No gaps and no reordering.** Every publication is either included in the snapshot or delivered after it.
`publish` numbers the publication before it reads the subscribers, and `add` reads that number after it makes
the session visible - so a publication racing a subscription lands on one side of the snapshot or the other,
never in the crack between them.

A publication may be seen **twice** - once inside the snapshot, once as an update - so updates must be
idempotent. `publicationSequence` is handed to the snapshot for a subscriber which wants to tell them apart.

Two rules are yours: mutate the state of the entity **before** calling `publish`, and serialize the
publications of one entity. Two concurrent publishers of the same entity have no order between them and
nothing here can invent one. `forEachSession` and its `forEachSessionText` / `forEachSessionBinary` forms
walk the subscribers without numbering anything - for inspection and administrative sends, not for state.

## Slow consumers

A frame is dropped by the transport the moment the channel is over its write watermark. What happens next is
an api-wide decision:

|                          | **Default**                            | **`withSkipOnBackPressure()`**                                    |
|--------------------------|----------------------------------------|-------------------------------------------------------------------|
| The frame                | not written                            | not written                                                       |
| The session              | closed                                 | kept, and marked as lagging                                       |
| When the channel drains  | -                                      | the snapshot of every entity it subscribes to is re-sent          |
| Suitable for             | anything                               | streams which restore a session from a snapshot                   |

A session under back pressure gets no pings - its channel is not writable - but its read timeout keeps
running, because a peer which stopped reading and a peer which is gone look exactly alike from here.

Skipping is off by default, and this is the one place where the promise it makes has to be kept: turn it on
only if *every* subscription served by that api restores a session with a snapshot. A channel relaying
standalone signals has no state to re-send, so a frame skipped there is a hole in the stream and its client
has to be disconnected instead. Marking it per channel would not help - one such frame disconnects the client
anyway, whatever the other channels do.

The threshold itself is Netty's, not ours:

```java
bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(32 * 1024, 64 * 1024));
```

**`newa-rest` answers this differently on purpose**, which is worth knowing if you use both. A response there
is paced rather than dropped - its cursor is simply not stepped while the channel is over the watermark - and
it is given thirty seconds without progress before it is abandoned, where here the question is settled on the
first frame which cannot be written. Neither module is the more careful one. A fan-out frame is perishable
and the next one is already better, so waiting buys nothing and holding it costs memory per subscriber; a
response there has no successor, and half of one is not a smaller one. Dropping is offered here and not there
for the same reason: it is only ever safe where something can rebuild what was dropped, which is what the
snapshot does. See [Slow consumers](../newa-rest/README.md#slow-consumers).

## Errors

A websocket has no response left to render once the handshake is done, so there is no error handler here and
nothing which formats a page: what a client is told about a frame it should not have sent is **a frame of your
own protocol**, and what it is told about a server that broke is **a close**. All this module does is report,
and each failure is reported once, by the stage which knows what it was:

| what happened | where it goes | what the peer gets |
|---|---|---|
| a receiver threw | `WsApiObserver.onReceiveFailed(cause)`, the cause as it was thrown | a `1011` close, and the session ends |
| a frame did not go out | `onWriteFailed(cause)`, and `onWriteBackPressure` before it when the channel was full | the session ends, unless `withSkipOnBackPressure()` |
| the channel itself failed | `ChannelErrorHandler` | the connection goes |
| an HTTP request nothing took - not the handshake path, nothing mounted behind | `ChannelErrorHandler`, a `NotAHandshakeException` carrying the method and the uri | the connection goes, unanswered |
| the client sent something you do not serve | wherever your receiver reports it | whatever your protocol says |
| you ended the session yourself | nowhere - it is not a failure | `closeWith(status)` says which close it is, `close()` says nothing |

```java
final Receiver.Text receiver = (session, message, last) -> {
    if (!command.isKnown(message)) {
        session.sendText("ERR: unknown command");      // your protocol, your error, session lives on
        return;
    }
    handle(session, message);                          // if this throws, the session is closed with a 1011
};   // withBinaryReceiver is not called, so a binary frame gets a 1003
```

A receiver which throws ends its session on purpose: it has said nothing about whether the state behind it
is still whole, and no frame this library could invent would mean anything in a protocol it does not know. The
cause never reaches the `ChannelErrorHandler` - a failure of the application is not a failure of the channel -
and never reaches the peer: `1011` is a status, not a stack trace. Handle what you expect inside the receiver;
what is left is a bug, and a bug closes one session.

`ClientSession.receiveFailed(cause)` is the same treatment, public, for a receiver which hands its frames to
something else and catches there.

The HTTP half of a websocket port is HTTP, and whatever is mounted behind the websocket handler renders its
errors with an `HttpErrorHandler`, exactly as in `newa-rest` - `ws.errors.ErrorsWsServer` runs both halves in
one server.

With nothing mounted there, a request which is not the handshake gets **no response and no port left
holding it**: `HandshakeOnlyHandler`, last in the pipeline, closes the connection and reports a
`NotAHandshakeException` to the `ChannelErrorHandler`. This port speaks HTTP once, to be upgraded away from
it, so there is nothing for it to answer with - and leaving the request where Netty drops it, at the end of
the pipeline, would hold a socket for as long as the peer cared to keep it. Nothing before the handshake is
on a timer: the ping interval and the read timeout belong to a session, which does not exist yet. The type
is there to be read rather than logged blindly - a health check aimed at the wrong port is worth a counter,
a scanner is worth nothing:

```java
WsServer.of(api).withChannelErrorHandler((channel, cause) -> {
    if (cause instanceof NotAHandshakeException) {
        wrongPort.increment();                  // not a failure of this server
        return;
    }
    log.error("Channel {} failed", channel, cause);
});
```

It carries no stack trace - the frames would name Netty's decoders - and its `method()` and `uri()` come
from the peer, so whatever writes them to a log is writing what somebody else chose.

## Observing

The library keeps no metrics. It reports, and what that turns into is yours. One observer per session, made by
a factory given to `AbstractWsApiBuilder.withObservers(...)` - it belongs to the api, like the receiver, so `WsServer`
has nothing to say about it:

```java
public class AccessLog implements WsApiObserver {
    private String peer;

    @Override
    public void onSessionOpened(ClientSession session) {
        peer = session.channel().remoteAddress().toString();   // the only stage given the session
    }

    @Override
    public void onFrameSent(int bytes) { }                     // per frame: keep it cheap

    @Override
    public void onWriteBackPressure(int bytes) {
        log.warn("{} could not take {} bytes", peer, bytes);
    }

    @Override
    public void onSessionClosed(long durationNanos) {
        log.info("{} gone after {}ns", peer, durationNanos);
    }
}

new WsApiBuilder(1).withObservers(AccessLog::new).build();
```

`onSessionClosed` fires **exactly once per session**, however it ended, so counting sessions never means
adding two events up.

`SubscriptionsWsApiObserver extends WsApiObserver` adds what the session subscribed to - pass a
`SubscriptionsWsApiObserverFactory` to `SubscriptionWsApiBuilder` to get those stages:

```
a session:      onSessionOpened -> ( onFrameReceived | onFrameSent )*
                                -> [ onReceiveFailed | onWriteFailed ] -> onSessionClosed
subscribing:    onSubscribed | onRepeatedSubscription | onUnknownEntity | onUnsubscribed
falling behind: onWriteBackPressure -> onWriteResumed -> onResynced        (skip mode)
                onWriteBackPressure -> onSessionClosed                     (default)
```

A session still subscribed when it goes away is unsubscribed by the api itself, so what it leaves behind is
reported rather than lost - before `onSessionClosed` when the session is closed on its own event loop, which
is how a channel going away closes one.

Every method has a no-op default. A call comes from whichever thread did the work: the event loop of the
session for what the api does on its own, and the publishing thread for the frames it sends. Do not block in
them, and expect them from several threads at once.

Both halves are optional. `withObservers` may be left out entirely, and `newObserver()` may return `null` for
a session that is not worth observing - then not even the clock is read for it. A shared instance is allowed
too, and then telling the sessions apart is yours.

## Memory budget

**Size the watermark in time, not in bytes.** How much room a number buys depends entirely on what a session
subscribed to: 64 KB is a second and a half of a subscriber taking 200 messages a second, and fourteen
milliseconds of one taking 20 000. Decide instead how far behind a subscriber may be and still be worth
serving - call it the lag - and derive the mark from the stream that subscriber is actually sent:

```
high = lag (seconds) x SUM over the entities one session subscribes to of (publications/s x frame bytes)
low  = high / 2
```

**Worked example.** A hundred subscribers, one entity published 2 000 times a second, frames of 200 bytes, and
a decision that a subscriber more than 100 ms behind is no longer being served:

```
one session is sent   2 000 x 200            = 400 KB/s
high                  0.1 x 400 KB/s         =  40 KB   -> 200 frames of slack
low                   high / 2               =  20 KB
```

The number of subscribers does not enter the mark: it is set by the stream *one* session is sent, and a
session subscribing to several entities is sent the sum of them.

**What that costs the process.** The frames waiting for a session are direct memory - Netty writes from direct
buffers whatever the buffer you published was - and how much of it depends on whether laggards share a stream:

```
one entity, everyone on it     the pending frames are shared, so the bound is one session's
                               high, about 40 KB whatever the number of subscribers
an entity per subscriber       the pending sets are disjoint: high x sessions, 100 x 40 KB = 4 MB
```

Give `-XX:MaxDirectMemorySize` the disjoint figure with room to spare unless you know the subscriptions
overlap, and remember the floor: the pooled allocator reserves in chunks (4 MB in Netty 4.2) across roughly
two arenas per core, so a small budget still costs that much. On the heap the same backlog costs only its
bookkeeping - one duplicate object per pending frame per session, tens of bytes each, so the 20 000 duplicates
of the worst case above are a couple of megabytes.

The lag is therefore the memory budget as well as the deadline, which is the point of choosing it rather than
a byte count: raising it does not make a server keep up, it makes a subscriber which cannot keep up survive
longer and hold that much more while it does.

**Work it the other way round for a container.** CPU divides itself - fewer cores only means slower - while
memory past the limit is a dead process, by `OutOfMemoryError` or by the cgroup killer, and nothing degrades
automatically. So fix the two numbers you control and derive the rest:

```
high      = lag x (publications/s x frame bytes)         per session, from the deadline you chose
peak      = high x streams that can lag at once          one entity: high. One per session: high x sessions
sessions  = (budget - peak) / per-session overhead       what is left over caps the connections
```

The example above - 100 sessions, 2 000 x 200 B, 100 ms - needs 40 KB per session and 4 MB in the worst case.
What enforces it - the library bounds what one session may hold, not how many sessions there are:

```java
bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(20 * 1024, 40 * 1024));              // low = high / 2

if (open.incrementAndGet() > 100) { ch.close(); return; }             // AtomicInteger open
ch.closeFuture().addListener(f -> open.decrementAndGet());
```

Outbound frames live off-heap, where `-Xmx` does not bound them and the cgroup limit does, so the limit has
to cover heap plus direct memory plus metaspace, code cache and thread stacks:

```
-XX:MaxRAMPercentage=50 -XX:MaxDirectMemorySize=64m
-Dio.netty.maxDirectMemory=67108864 -XX:+ExitOnOutOfMemoryError
```

Refusing a connection is the cheap failure.

## Tuning for high load

**Watermarks are the real knob.** `WRITE_BUFFER_WATER_MARK` decides how much a session may fall behind
before it counts as slow, and everything else here is downstream of it - see [Memory
budget](#memory-budget) above for the number to put in it. Under `WsServer` that number and the thread counts
are set on the `NettyServerBuilder` handed to `start(...)`: `writeBufferWaterMark(low, high)` and
`workerThreads(n)`. There is no queue of ours behind it: a frame the channel cannot take is never queued, it
is released, and the session is closed or marked lagging there and then.

**Encode a fan-out once.** `sendText(CharSequence)` encodes UTF-8 per session, which for a publication
reaching thousands of subscribers is most of the work. Render the frame once and give it to
`publishTextAndRelease(ByteBuf)`, which hands every session a retained duplicate of it and releases the
buffer when the fan-out is done - a session releases the frame it was given whatever becomes of it, written
or skipped:

```java
ByteBuf encoded = allocator.buffer();
// ... render the update into it ...
entity.publishTextAndRelease(encoded);   // rendered once, a duplicate per session, released here
```

It is `broadcastTextAndRelease(ByteBuf)` for one entity instead of every open session. `publishText(ByteBuf)`
is the same fan-out without taking the buffer over - for a frame the publisher keeps, pools or sends to more
than one entity. `publishBinary` and `publishBinaryAndRelease` are the binary twins of both. Each has a
`forEachSessionText` / `forEachSessionBinary` twin for an administrative frame which is not a change of the
state, and `publish(session -> ...)` stays for the case where a session needs a frame of its own.

**Keep the snapshot cheap.** `onClientSessionSubscribed` runs inline on the event loop of the subscribing
session; a heavy snapshot delays every other session sharing that loop. Build it from state that is already in
memory, and page it if it cannot be.

**Publish from one thread per entity, and know which thread that is.** The fan-out runs on the publishing
thread: the encoding happens there, and each frame is then handed to its session's loop. A slow consumer never
blocks a publisher, but an expensive `publish` lambda multiplies by the number of subscribers.

**Create entities up front.** `getOrCreateEntitySubscriptions` takes the lock of the channel, and creating an
entity is the only thing that does. It holds no more than the entry it adds, so a hundred thousand entities
cost what they are - but doing it at start-up, and serving clients with `subscribeForKnownOnly`, keeps the
lock off the command path and stops a client populating the server with entities of its own invention.

**What a subscription costs.** Nothing is copied to subscribe or to unsubscribe: a session goes into a free
slot of the subscriber list and leaves a hole behind when it goes, and the holes are collected only once
there are more of them than there are subscribers. A storm of clients arriving at a popular entity is linear
and produces no garbage on the way; the fan-out still walks a snapshot without a lock, skipping the holes.
Unsubscribing scans the list to find the session - memory read, nothing allocated. Closing a session and
re-synchronizing one cost what that session subscribed to, not what its channels hold, so a channel of tens
of thousands of entities is fine. `Channel.unsubscribeAll(session)` is the exception: it is asked for
explicitly and walks the whole channel.

**The keep-alive costs a timer per session**, and it is on by default - the right default facing the open
internet, where a peer that vanishes without a FIN would otherwise hold its session and every subscription on
it forever. `withPingIntervalMs(0).withReadTimeoutMs(0)` turns the pair off, which is what a protocol with a
heartbeat of its own wants, and what a measuring instrument wants so the pings do not land in its counts. The
pair is described under [Sessions](#sessions).

**Compression is per connection.** `permessage-deflate` holds a compressor context per session and runs on
every frame - the price of a fan-out grows with the number of subscribers, not with the number of distinct
messages. Bandwidth against CPU and memory: measure before enabling it for a broadcast-heavy server.

**Extensions follow `withCompression()`.** With it, `permessage-deflate` is negotiated and the decoder
accepts the reserved bits it sets; without it, nothing in the pipeline could inflate a frame and the
reserved bits are a protocol violation. This is not a preference and there is no knob for it: the two have
to agree, and `WsServer` makes them. A pipeline assembled by hand says it itself, in the four-argument
`WsApiHandler` constructor - the two-argument one means "no compression handler here", which is what a
pipeline that does not add one means.

**Event loops.** A session is pinned to one worker loop for its whole life, so the worker group is what
parallelism is bought with - the examples use one worker because one is easier to follow, not because one is
enough.

**The observer is on the hot path.** `onFrameSent` and `onFrameReceived` fire per frame. Keep them to a
counter, or return `null` from the factory for the sessions you do not need to watch.

## Runnable examples

In `newa-example`, package `io.github.green4j.newa.example.ws`:

- **`echo.EchoWsServer`** - the smallest thing that serves a session: a `Receiver.Text` echoing text back,
  and a `Receiver.Binary` echoing binary back as binary.
- **`broadcast.BroadcastWsServer`** - `WsApi.broadcastText` to every open session, on a timer scheduled on
  `NettyServer.workerGroup()`.
- **`subscriptions.SubscriptionsWsServer`** - two channels of five entities, a client protocol of
  `[A|B]:[S|U]:[ID]` commands, publications on a timer and snapshots on subscribe, with
  `withSkipOnBackPressure()` turned on because both channels restore a session from a snapshot.
- **`errors.ErrorsWsServer`** - an error where there is nothing to render: a bad command answered with a frame
  of the protocol's own, and a `BOOM` which throws, ends its session with a `1011` and puts the cause in
  `onReceiveFailed` and nowhere else. Commands to try are printed at startup.
- **`pipeline.PipelineWsServer`** - a websocket and a REST stats endpoint on one port, with the bootstrap and
  the pipeline written out by hand. The composition itself needs no hand assembly - `withHandler(...)` above
  produces the same pipeline - but its watermarks are computed from the fan-out it expects rather than left
  at a default, which is what no helper can guess.
- **`StdOutWsApiObserver`** - an observer per session, from `StdOutWsApiObserver.factory()`, which counts
  what it sent and prints the totals when the session closes.

All but `pipeline.PipelineWsServer` are started with `WsServer`; that one is the reason the manual path is
documented.
