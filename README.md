## Netty-based Web API (NeWA)

A minimalistic REST and WebSocket server framework, built to serve a large number of clients at the
performance Netty makes possible.

On the WebSocket side it also carries what a general purpose stack leaves to the application: channels and
subscriptions. A subscriber joins an entity, is given its snapshot if there is one, and is then promised
every publication after it - in order, with no holes, and with an explicit policy for a subscriber which
cannot keep up. That is what an event subscription protocol of your own is built out of.

### Quick start

A server is one line, and the api is the only thing you write:

```java
RestApiBuilder builder = new RestApiBuilder("My API", "Desc", 1, "1.0.0");

builder.getJson("/hello/{name}", (context, output) ->
        output.stringValue("Hello " + context.pathParameters().valueRequired("name"))
).withPathParameterDescriptions("name - Greeting target");

RestApi api = builder.build();                       // or buildWithHelp(JsonHelp.factory())

new Life().run(() -> RestServer.start(api, 9009));   // REST, GET /v1/hello/world
```

```java
WsApi api = new WsApiBuilder(1)
        .withTextReceiver((session, message, last) -> session.sendText(message))
        .build();

new Life().run(() -> WsServer.start(api, 9010));     // WebSocket, echoing
```

`RestServer`, `FileServer` and `WsServer` assemble the documented pipeline out of the same public handlers,
on a `NettyServerBuilder` which picks the best transport this machine has and a worker per core. `Life` opens
the server, parks the main thread until it should stop, closes it, and does the same when the JVM is going
down or when the server's own listening channel closes under it. Everything is still yours to take over:
`NettyServerBuilder` for the transport, the threads, the channel options and how many connections a server
will hold at once, and `RestServer.pipeline()` / `FileServer.pipeline()` / `WsServer.pipeline()` - or a
hand-written pipeline - for what runs above the socket. The module READMEs document both, and `newa-example`
has a server of each shape: `rest.pipeline.PipelineRestServer` and `ws.pipeline.PipelineWsServer` are the
ones assembled from scratch.

Both in one process is `Life.all(...)`, which runs any number of them as one - opened in the order given,
closed together, and rolled back if a later one cannot be opened:

```java
new Life().run(Life.all(
        () -> RestServer.start(restApi, 9009),
        () -> WsServer.start(wsApi, 9010)));
```

A WebSocket server can also serve a REST api on its own port, with no second server at all - see [One port
for both](newa-websocket/README.md#starting-a-server).

### Binaries

Binaries for Maven, Ivy, Gradle, and others can be found at
https://central.sonatype.com/search?q=g:io.github.green4j.

Example for Maven:

```xml
<dependency>
    <groupId>io.github.green4j</groupId>
    <artifactId>newa-all</artifactId>
    <version>${newa.version}</version>
</dependency>
```

Four artifacts are published, all under `io.github.green4j`:

| artifact | what it is |
|---|---|
| `newa-rest` | REST routing, handlers and the file server. Brings `newa-common` with it |
| `newa-websocket` | sessions, broadcasting and subscription channels. Brings `newa-common` with it |
| `newa-common` | the transport selection, the bootstrap builder and the handlers both servers stand on |
| `newa-all` | the three above in one jar, for a build which would rather name one dependency than three |

### Dependencies

newa is built for Java 11 and pulls in two things, both as `compile` dependencies of the artifacts above:
[Netty](https://netty.io) 4.2 - `netty-transport`, `netty-handler`, `netty-codec`, `netty-codec-http` and
`netty-buffer` - and [green-jelly](https://github.com/green4j/green-jelly), the allocation-free JSON
generator responses are rendered with. Nothing else: no logging framework, no dependency injection
container, no annotation processing.

The native transports are deliberately **not** among them. `Transport.auto()` looks for
`netty-transport-native-epoll` and `netty-transport-native-kqueue` by name and falls back to NIO in silence
when neither is on the classpath, so a build which wants one adds it itself, with the classifier of the
machine it will run on:

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-epoll</artifactId>
    <version>${netty.version}</version>
    <classifier>linux-x86_64</classifier>
</dependency>
```

`newa-all` is a shaded jar of the three newa modules only - Netty and green-jelly stay where they are, named
by its POM, so nothing is duplicated on a classpath which already has them.

### Against Spring Boot

Measured in [newa-performance](newa-performance/README.md), with each side written the way its own framework
is normally written - newa rendering into a reused buffer, Spring returning objects for Jackson to serialise.

Everything below is measured over the loopback, so read the ratios rather than the absolute rates.

- **REST**: about **2.9x** as many requests per second of server processor time, at a seventh of the
  allocation per request, written the way the project writes ordinary code. At 20 000 req/s offered, p99 is
  191 us against 627 us.
- **WebSocket fan-out**: a higher sustained rate per subscription, and the gap widens with the number of
  subscribers. From a hundred subscribers to a thousand newa goes from 250 000 to 300 000 events a second
  while Spring's own handler falls from 200 000 to under 120 000 - 2 500 events a second per subscription
  against 2 000, and 300 against 121.
- **STOMP**: Spring's simple broker is the only subscription mechanism it ships, and it delivers an ordered
  stream only with its outbound channel pinned to one thread, which caps it at 100 000 events a second
  whatever the number of subscribers. Left at Boot's default pool it is faster and delivers a destination
  out of order, which is not a subscription at all.

### Module documentation

- [newa-rest/README.md](newa-rest/README.md) - HTTP REST routing and handlers on Netty.
- [newa-websocket/README.md](newa-websocket/README.md) - WebSocket sessions, broadcasting and subscription channels on Netty.
- [newa-performance/README.md](newa-performance/README.md) - the benchmarks the numbers above come from.

`newa-common` and `newa-all` are documented by the two above and listed under [Binaries](#binaries).
`newa-example` is not published - it holds twelve runnable demo servers, ten started with the helpers above
and two with the Netty pipeline written out by hand.
