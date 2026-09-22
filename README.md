## Netty-based Web API (NeWA)

A minimalistic REST and WebSocket API server framework, built to:
* Serve a large number of clients at the performance Netty makes possible: about 2.9 times as many HTTP
  requests per CPU core as Spring Boot in the [newa-performance](newa-performance/README.md) benchmark.
* Provide a general-purpose WebSocket stack which leaves channels and subscriptions to the application. A
  subscriber joins an entity, receives its snapshot when there is one, then receives every publication in
  order, with no holes and with an explicit policy for a subscriber which cannot keep up.
* Prevent server-side OOM with [memory admission](docs/memory-budget.md): REST, file and WebSocket servers
  estimate their heap and direct-memory cost per connection and may draw it from one process-wide budget.

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

`RestServer`, `FileServer` and `WsServer` assemble their documented pipelines from public handlers.
`NettyServerBuilder` controls transport, threads, channel options and connection limits; it chooses the best
available transport, one worker per core and the loopback by default. The module guides document both
builders, and `newa-example` includes the hand-written `rest.pipeline.PipelineRestServer` and
`ws.pipeline.PipelineWsServer`.

`Life` opens a server, parks the main thread until it should stop, then closes it. It also handles JVM
shutdown and the server's own listening channel closing.

Both in one process is `Life.all(...)`, which runs any number of them as one - opened in the order given,
closed together, and rolled back if a later one cannot be opened:

```java
new Life().run(Life.all(
        () -> RestServer.start(restApi, 9009),
        () -> WsServer.start(wsApi, 9010)));
```

A WebSocket server can also serve a REST api on its own port, with no second server at all - see [One port
for both](newa-websocket/README.md#starting-a-server).

### Memory budget

REST, file and WebSocket servers can draw differently sized connections from one process-wide
`ServerMemoryBudget` instead of partitioning fixed connection counts:

```java
ServerMemoryBudget memory = ServerMemoryBudget.builder()
        .heapPercentage(70)
        .directMemoryPercentage(70)
        .build();

new Life().run(Life.all(
        () -> RestServer.of(restApi)
                .withMemoryBudget(memory, 64 * 1024)  // largest ordinary response
                .start(9009),
        () -> WsServer.of(wsApi)
                .withMemoryBudget(memory, 64 * 1024)  // largest outbound frame estimate
                .start(9010)));
```

The sizes describe the deployment rather than library defaults. See the
[memory budget guide](docs/memory-budget.md) for setup, process limits, guarantees, observation and
integration tests, together with the runnable
[shared-budget example](newa-example/src/main/java/io/github/green4j/newa/example/budget/SharedMemoryBudgetServers.java).

Protocol-specific sizing and blind spots remain in the
[REST/file](newa-rest/README.md#memory-budget) and
[WebSocket](newa-websocket/README.md#memory-budget) guides.

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

### Documentation

- [docs/memory-budget.md](docs/memory-budget.md) - shared memory admission, process limits and guarantees.
- [newa-rest/README.md](newa-rest/README.md) - HTTP REST routing and handlers on Netty.
- [newa-websocket/README.md](newa-websocket/README.md) - WebSocket sessions, broadcasting and subscription channels on Netty.
- [newa-performance/README.md](newa-performance/README.md) - the benchmarks the numbers above come from.
