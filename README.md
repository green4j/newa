## Netty-based Web API (NeWA)

Netty-based minimalistic REST and WebSocket server framework, built to serve a large number of clients 
with perfect performance we can achieve with Netty framework.

On the WebSocket side it also provides what a general purpose stack leaves to the application: channels and
subscriptions - a subscriber joins an entity, is given its snapshot (optionally), and is then promised every publication
after it, in order and with no holes, with an explicit policy for one which cannot keep up. This feature allows to build
highly customizable and well-looking event subscription protocols.

### Quick start

A server is one line, and the api is the only thing you write:

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");

builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");

RestApi api = builder.build();                       // or buildWithHelp(JsonHelp.factory())

new Life().run(() -> RestServer.start(9009, api));   // REST, GET /v1/hello/world
```

```java
WsApi api = new SimpleWsApiBuilder(1)
        .withReceiver((session, message) -> session.send(message))
        .build();

new Life().run(() -> WsServer.start(9010, api));     // WebSocket, echoing
```

`RestServer` and `WsServer` assemble the documented pipeline out of the same public handlers, on a
`NettyServerBuilder` which picks the best transport this machine has and a worker per core. `Life` opens the
server, parks the main thread until it should stop, closes it, and does the same when the JVM is going down. Everything is
still yours to take over: `NettyServerBuilder` for the transport, the threads and the channel options, and
`RestServer.pipeline()` / `WsServer.pipeline()` - or a hand-written pipeline - for what runs above the
socket. The module READMEs document both, and `newa-example` has a server of each shape:
`rest.pipeline.PipelineRestServer` and `ws.pipeline.PipelineWsServer` are the ones assembled from scratch.

Both in one process is `Life.all(...)`, which runs any number of them as one - opened in the order given,
closed together, and rolled back if a later one cannot be opened:

```java
new Life().run(Life.all(
        () -> RestServer.start(9009, restApi),
        () -> WsServer.start(9010, wsApi)));
```

A WebSocket server can also serve a REST api on its own port, with no second server at all - see [One port
for both](newa-websocket/README.md#starting-a-server).

### Against Spring Boot

Measured in [newa-performance](newa-performance/README.md), with each side written the way its own framework
is normally written - newa rendering into a reused buffer, Spring returning objects for Jackson to serialise.

- **REST**: about **2.9x** as many requests per second of server processor time, at a seventh of the allocation
  per request with programming style canonical for the project. At 20 000 req/s offered, p99 is 191 us 
  against 627 us on loopback interface.
- **WebSocket fan-out**: a higher sustained rate per subscription, and the gap widens with the number of
  subscribers. From a hundred subscribers to a thousand newa goes from 250 000 to 300 000 events total a second
  while Spring's own handler falls from 200 000 to under 120 000 - 2 500 events a second per subscription
  against 2 000, and 300 against 121 on loopback interface.
- **STOMP**: Spring's simple broker is the only subscription mechanism it ships, and it delivers an ordered
  stream only with its outbound channel pinned to one thread, which caps it at 100 000 events total a second
  whatever the number of subscribers on loopback interface. Left at Boot's default pool it is faster and delivers a destination out
  of order, which is not a subscription at all.

### Module documentation

- [newa-rest/README.md](newa-rest/README.md) - HTTP REST routing and handlers on Netty.
- [newa-websocket/README.md](newa-websocket/README.md) - WebSocket sessions, broadcasting and subscription channels on Netty.
- [newa-performance/README.md](newa-performance/README.md) - the benchmarks the numbers above come from.

Other Gradle modules: `newa-common` (shared utilities, the transport selection and the bootstrap builder used
by REST and WebSocket), `newa-all` (combined artifact), and `newa-example` (runnable demo servers - seven
started with the helpers above, and two with the Netty pipeline written out by hand).
