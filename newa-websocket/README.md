# newa-websocket

WebSocket sessions, broadcasting and subscription channels built on Netty.

```
Client --> HttpServerCodec --> HttpObjectAggregator --> [WebSocketServerCompressionHandler]
           --> WsApiHandler --> [your handlers] --> HandshakeOnlyHandler
```

`WsServer` assembles that pipeline and `NettyServerBuilder` the bootstrap under it, so a working server is one
line. Neither hides anything: they are made of the same public handlers, and the pipeline and the bootstrap
are still yours to take over the moment either needs changing - see [Starting a
server](#starting-a-server).

## Getting started

```java
WsApi api = new SimpleWsApiBuilder(1)          // version 1, so the handshake path is /ws/v1
        .withPathPrefix("ws")
        .withReceiver((session, message) -> session.send(message))   // what handles inbound frames
        .withPingIntervalMs(30_000)            // the keep-alive pair, and these are the defaults:
        .withReadTimeoutMs(90_000)             // ping an idle session, close one whose peer went silent
        .withObservers(AccessLog::new)         // optional, see Observing
        .build();

new Life().run(() -> WsServer.start(9010, api));   // serving ws://127.0.0.1:9010/ws/v1
```

Clients connect to `ws://host:port` plus `api.websocketPath()` - here `ws://127.0.0.1:9010/ws/v1`. The
handshake path is the api's, and nothing repeats it. Leave `withReceiver` out for a connection which only
ever listens.

The `Receiver` belongs to the `WsApi` for the same reason a rest handle belongs to a `RestApi`: it is what
handles what comes in. One consequence is worth knowing - the receiver is built before the api, so a receiver
which wants to call `api.broadcast(...)` cannot simply capture it. Subclass `WsApi` and implement `Receiver`
on the subclass when a receiver needs the api it belongs to.

`SimpleWsApiBuilder` builds an api of plain sessions; `SubscriptionWsApiBuilder` builds one which also keeps
what every session subscribed to - see below. Everything else on the builder is shared by both.

## Starting a server

`WsServer.start(port, api)` is the whole server. What belongs to the api - the path, the ping interval, the
back pressure policy, the receiver, the observers - stays on the api builder and is not repeated here; what
is left is the pipeline and the bootstrap:

```java
NettyServer server = WsServer.of(api)
        .withCompression()                       // permessage-deflate, off by default
        .withChannelErrorHandler(new StdErrChannelErrorHandler())
        .withMaxContentLength(65536)             // the handshake request, not what a session sends
        .withHandler(() -> new RestApiHandler(restApi, new JsonErrorHandler(), errors))
        .start(new NettyServerBuilder()
                .port(9010)
                .host("127.0.0.1")               // every interface by default
                .workerThreads(8)                // a worker per core by default
                .writeBufferWaterMark(low, high));
```

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
        () -> api.broadcast("tick"), 5, 5, TimeUnit.SECONDS);
```

What runs it is `Life`: `new Life().run(() -> ...)` opens the server, parks this thread until the end is
asked for, closes it, and registers a JVM shutdown hook for the length of the run. The server is opened by
`run` rather than handed to it, so there is no instant at which it is serving and nothing owns it. A `Life`
is an `Ender` from the moment it is constructed, so it is also what an endpoint or a signal handler calls to
ask for that end - see `Ending it from a request` in
[newa-rest](../newa-rest/README.md#ending-it-from-a-request).

To keep this pipeline but bootstrap it yourself, hand `WsServer.pipeline()` to a `ServerBootstrap` of your
own. To change the pipeline, write it out - `ws.pipeline.PipelineWsServer` in `newa-example` is that.

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
the same way. What it buys, and the two things it deliberately leaves to you, are in
[newa-rest](../newa-rest/README.md#more-than-one-server-in-one-life); `rest.pair.PairedRestServers` in
`newa-example` runs a pair.

## Sessions

A `ClientSession` appears when the handshake completes and lives until the channel goes away. It is what
everything else is expressed in terms of:

```java
session.send("text");                         // encoded UTF-8, one frame
session.send(text, StandardCharsets.UTF_8);
session.send(byteBuf);                        // the session takes the buffer over
session.ping(payload);
session.close();                              // idempotent
```

`Receiver.receive(session, text)` is called for text frames only, on that session's event loop; ping, pong
and close are answered by Netty underneath. The `CharSequence` is valid for the call - copy what outlives it.

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
closes: nothing from the peer for that long and the session goes, through the same `close()` and the same
`onSessionClosed` as any other ending. Neither works alone. Without the timeout a dead peer is noticed only
once the send buffer fills and the channel stops being writable, which is late and depends on how much you
send rather than on time; without the ping a perfectly healthy subscriber which does nothing but listen is
disconnected as dead, and that is most of a fan-out's clients. They default to 30 s and 90 s - three missed
pings - and either takes 0 to turn it off, which is what to do when the protocol above already carries a
heartbeat.

Upgrading from a version before this pair existed: sessions now carry a timer and a peer silent for 90 s is
disconnected. `withPingIntervalMs(0).withReadTimeoutMs(0)` is the old behaviour.

## Broadcasting

```java
api.broadcast("hello");        // every open session
```

A broadcast walks the sessions without a lock and without making anything wait, and opening or closing a
session neither copies that list nor blocks a broadcast. `broadcast(CharSequence)` encodes the text once per
session; the `ByteBuf` forms encode nothing at all - they give every session a retained duplicate of the
buffer. `broadcastAndRelease(ByteBuf)` takes the buffer over and releases it once the fan-out is done;
`broadcast(ByteBuf)` leaves it to the caller, so the same buffer can be sent again or kept.

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
            publish(session -> session.send(entityId() + '=' + value));    // then the fan-out
        }

        @Override
        protected void onClientSessionSubscribed(ClientSession session, long publicationSequence) {
            String snapshot = last;
            if (snapshot != null) {
                session.send(entityId() + '=' + snapshot);   // goes out before any concurrent update
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
nothing here can invent one. `forEachSession` walks the subscribers without numbering anything - for
inspection and administrative sends, not for state.

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

## Errors

A websocket has no response left to render once the handshake is done, so there is no error handler here and
nothing which formats a page: what a client is told about a frame it should not have sent is **a frame of your
own protocol**, and what it is told about a server that broke is **a close**. All this module does is report,
and each failure is reported once, by the stage which knows what it was:

| what happened | where it goes | what the peer gets |
|---|---|---|
| the `Receiver` threw | `WsApiObserver.onReceiveFailed(cause)`, the cause as it was thrown | a `1011` close, and the session ends |
| a frame did not go out | `onWriteFailed(cause)`, and `onWriteBackPressure` before it when the channel was full | the session ends, unless `withSkipOnBackPressure()` |
| the channel itself failed | `ChannelErrorHandler` | the connection goes |
| an HTTP request nothing took - not the handshake path, nothing mounted behind | `ChannelErrorHandler`, a `NotAHandshakeException` carrying the method and the uri | the connection goes, unanswered |
| the client sent something you do not serve | wherever your `Receiver` reports it | whatever your protocol says |

```java
final Receiver receiver = (session, message) -> {
    if (!command.isKnown(message)) {
        session.send("ERR: unknown command");   // your protocol, your error, and the session lives on
        return;
    }
    handle(session, message);                   // if this throws, the session is closed with a 1011
};
```

A `Receiver` which throws ends its session on purpose: it has said nothing about whether the state behind it
is still whole, and no frame this library could invent would mean anything in a protocol it does not know. The
cause never reaches the `ChannelErrorHandler` - a failure of the application is not a failure of the channel -
and never reaches the peer: `1011` is a status, not a stack trace. Handle what you expect inside the receiver;
what is left is a bug, and a bug closes one session.

`ClientSession.receiveFailed(cause)` is the same treatment, public, for a receiver which hands its frames to
something else and catches there.

The HTTP half of a websocket port is HTTP, and whatever is mounted behind the websocket handler renders its
errors with an `HttpErrorHandler`, exactly as in `newa-rest` - see `ws.errors.ErrorsWsServer` for both
halves in one server.

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
a factory given to `WsApiBuilder.withObservers(...)` - it belongs to the api, like the receiver, so `WsServer`
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

new SimpleWsApiBuilder(1).withObservers(AccessLog::new).build();
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

```
-XX:MaxDirectMemorySize=64m -Dio.netty.maxDirectMemory=67108864
-XX:+ExitOnOutOfMemoryError
```

Refusing a connection is the cheap failure.

## Tuning for high load

**Watermarks are the real knob.** `WRITE_BUFFER_WATER_MARK` decides how much a session may fall behind before
it counts as slow, and everything else here is downstream of it - see `Memory budget` above for the number to
put in it. Under `WsServer` that number and the thread counts are set on the `NettyServerBuilder` handed to
`start(...)`: `writeBufferWaterMark(low, high)` and `workerThreads(n)`. There is no queue of ours behind it: a frame the channel cannot take is never queued, it is
released, and the session is closed or marked lagging there and then.

**Encode a fan-out once.** `send(CharSequence)` encodes UTF-8 per session, which for a publication reaching
thousands of subscribers is most of the work. Render the frame once and give it to
`publishAndRelease(ByteBuf)`, which hands every session a retained duplicate of it and releases the buffer
when the fan-out is done - a session releases the frame it was given whatever becomes of it, written or
skipped:

```java
ByteBuf encoded = allocator.buffer();
// ... render the update into it ...
entity.publishAndRelease(encoded);   // rendered once, a duplicate per session, released here
```

It is `broadcastAndRelease(ByteBuf)` for one entity instead of every open session. `publish(ByteBuf)` is the
same fan-out without taking the buffer over - for a frame the publisher keeps, pools or sends to more than
one entity. Each has a `forEachSession` twin for an administrative frame which is not a change of the state,
and `publish(session -> ...)` stays for the case where a session needs a frame of its own.

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

**The keep-alive costs a timer per session**, and it is on by default. That is the right default for a
server facing the open internet, where a peer that vanishes without a FIN would otherwise hold its session
and every subscription on it forever. Set `withPingIntervalMs(0).withReadTimeoutMs(0)` when the protocol
above already has a heartbeat, or on a measuring instrument where the ping frames would land in somebody's
counts.

**Compression is per connection.** `permessage-deflate` holds a compressor context per session and runs on
every frame - the price of a fan-out grows with the number of subscribers, not with the number of distinct
messages. Bandwidth against CPU and memory: measure before enabling it for a broadcast-heavy server.

**Frames come in at 64 KB.** That is Netty's default payload limit for an inbound frame; the
`HttpObjectAggregator` in front only bounds the handshake request. Neither is what an outbound frame is
measured against.

**Size the container for direct memory, not just the heap.** Outbound frames live off-heap, where `-Xmx` does
not bound them and the cgroup limit does. The limit has to cover heap plus direct memory plus metaspace, code
cache and thread stacks:

```
-XX:MaxRAMPercentage=50
-XX:MaxDirectMemorySize=512m
-Dio.netty.maxDirectMemory=536870912
-XX:+ExitOnOutOfMemoryError
```

**Event loops.** A session is pinned to one worker loop for its whole life, so the worker group is what
parallelism is bought with - the examples use one worker because one is easier to follow, not because one is
enough.

**The observer is on the hot path.** `onFrameSent` and `onFrameReceived` fire per frame. Keep them to a
counter, or return `null` from the factory for the sessions you do not need to watch.

## Runnable examples

In `newa-example`, package `io.github.green4j.newa.example.ws`:

- **`echo.EchoWsServer`** - the smallest thing that serves a session: a `Receiver` echoing text back.
- **`broadcast.BroadcastWsServer`** - `WsApi.broadcast` to every open session, on a timer scheduled on
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
