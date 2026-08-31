# newa-websocket

WebSocket sessions, broadcasting and subscription channels built on Netty. The library supplies the handshake
handler, the session and the subscription types only: your application owns `ServerBootstrap`, the event loop
groups and the pipeline.

```
Client --> HttpServerCodec --> HttpObjectAggregator --> [WebSocketServerCompressionHandler] --> WsApiHandler
```

## Getting started

```java
WsApi api = new SimpleWsApiBuilder(1)          // version 1, so the handshake path is /ws/v1
        .withPathPrefix("ws")
        .withPingIntervalMs(10_000)            // 0 disables the keep-alive entirely
        .withObservers(AccessLog::new)         // optional, see Observing
        .build();

Receiver receiver = (session, message) -> session.send(message);

pipeline.addLast(new HttpServerCodec());
pipeline.addLast(new HttpObjectAggregator(65536, true));
pipeline.addLast(new WebSocketServerCompressionHandler(0));            // optional
pipeline.addLast(new WsApiHandler(api, receiver, channelErrorHandler));
```

Clients connect to `ws://host:port` plus `api.websocketPath()` - here `ws://127.0.0.1:9010/ws/v1`. The
`WsApiHandler` overload without a `Receiver` serves a connection which only ever listens.

`SimpleWsApiBuilder` builds an api of plain sessions; `SubscriptionWsApiBuilder` builds one which also keeps
what every session subscribed to - see below. Everything else on the builder is shared by both.

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
`lastReadTimeMs()` and `lastWriteTimeMs()` answer from any thread.

`putUserData` / `getUserData` hang application state off a session - but on an api built by
`SubscriptionWsApiBuilder` that slot belongs to the subscriptions layer. Use
`ClientSessionSubscriptions.putUserData` there, which is the same idea one level in.

A ping interval greater than zero schedules a fixed-delay task per session which pings only when the channel
has been idle and is writable - a channel with data still pending needs no keep-alive.

## Broadcasting

```java
api.broadcast("hello");        // every open session
```

A broadcast walks the sessions without a lock and without making anything wait, and opening or closing a
session neither copies that list nor blocks a broadcast. `broadcast(CharSequence)` encodes the text once per
session; the `ByteBuf` forms encode nothing at all - they give every session a retained duplicate of the
buffer. `broadcastAndRelease(ByteBuf)` takes the buffer over and releases it once the fan-out is done;
`broadcast(ByteBuf)` leaves it to the caller, so the same buffer can be sent again or kept.

For anything with state behind it - a price, a room, an order book - broadcasting is the wrong shape: it sends
to everyone and tells a new session nothing about what it missed. That is what channels are for.

## Channels and subscriptions

```
Channel<S extends EntitySubscriptions>     one per stream: prices, order books, rooms
    └── EntitySubscriptions                one per entity id, owns the sessions subscribed to it
            └── ClientSession, ...
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

### Housekeeping

`getOrCreateEntitySubscriptions`, `getEntitySubscriptions`, `removeEntitySubscriptions`, `isSubscribed`,
`forEachSubscription`, `isEmpty`, `numberOfSubscribedSessions`. Lookups and iteration take no lock; creating
or removing an entity takes the one of the channel, and subscribing takes a short one of the entity, which is
why entities are best created up front and publishing never waits for either.
`ClientSessionSubscriptions.numberOfSubscribedEntities()` answers the same question from the session's side.

A session keeps its own registry of the entities it is subscribed to, and `EntitySubscriptions` maintains it:
`EntitySubscriptions.add(session)` on its own - for an application which owns its routing and wants no channel
in the way - is unsubscribed when the session goes away just as a channel subscription is, and is re-sent its
snapshot when the session catches up.

## Slow consumers

A frame is dropped by the transport the moment the channel is over its write watermark. What happens next is
an api-wide decision:

|                          | **Default**                            | **`withSkipOnBackPressure()`**                                    |
|--------------------------|----------------------------------------|-------------------------------------------------------------------|
| The frame                | not written                            | not written                                                       |
| The session              | closed                                 | kept, and marked as lagging                                       |
| When the channel drains  | -                                      | the snapshot of every entity it subscribes to is re-sent          |
| Suitable for             | anything                               | streams which restore a session from a snapshot                   |

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

## Observing

The library keeps no metrics. It reports, and what that turns into is yours. One observer per session, made by
a factory:

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
                                -> [ onWriteFailed ] -> onSessionClosed
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

## Tuning for high load

**Watermarks are the real knob.** `WRITE_BUFFER_WATER_MARK` decides how much a session may fall behind before
it counts as slow - that is where the memory a slow peer costs is bounded, and where `withSkipOnBackPressure`
starts to matter. Everything else here is downstream of it.

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

**Pings cost a timer per session.** Set `withPingIntervalMs(0)` when the protocol above already has a
heartbeat, or when an idle connection is not worth detecting.

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
- **`broadcast.BroadcastWsServer`** - `WsApi.broadcast` to every open session.
- **`subscriptions.SubscriptionsWsServer`** - two channels of five entities, a client protocol of
  `[A|B]:[S|U]:[ID]` commands, publications on a timer and snapshots on subscribe, with
  `withSkipOnBackPressure()` turned on because both channels restore a session from a snapshot.
- **`StdOutWsApiObserverFactory`** - an observer per session which counts what it sent and prints the totals
  when the session closes.
