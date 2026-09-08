## Netty-based Web API (NeWA)

A minimalistic REST and WebSocket server framework, built to:
* Serve a large number of clients at the performance Netty makes possible (~2.9x. as many HTTP requests per second per CPU 
core comparing to Spring Boot; see [newa-performance](newa-performance/README.md))
* Provide a general purpose WebSocket stack leaves to the application: channels and
subscriptions. A subscriber joins an entity, is given its snapshot if there is one, 
and is then promised every publication after it - in order, with no holes, and with an explicit policy for 
a subscriber which cannot keep up
* Prevent OOM on server side with memory admission/budgeting: REST, file and WebSocket servers estimate their heap and 
direct-memory cost per connection and may draw it from one process-wide budget

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
on a `NettyServerBuilder` which picks the best transport this machine has, a worker per core, and the
loopback until a server is told which interface to be reachable on. `Life` opens the server, parks the main
thread until it should stop, closes it, and does the same when the JVM is going down or when the server's
own listening channel closes under it. Everything is still yours to take over:
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

### Memory budget

Several servers in one JVM may draw their connections from one estimated heap/direct-memory budget instead
of partitioning fixed `maxConnections` values between them:

The runnable [shared-budget example](newa-example/src/main/java/io/github/green4j/newa/example/budget/SharedMemoryBudgetServers.java)
starts one REST, file and WebSocket server on the same budget.

```java
ServerMemoryBudget memory = ServerMemoryBudget.builder()
        .heapPercentage(70)                  // of Runtime.maxMemory()
        .directMemoryPercentage(70)          // of Netty's effective direct-memory maximum
        .build();

RestServer.of(restApi)
        .withMaxContentLength(256 * 1024)     // inbound: the largest body a client may upload
        .withMemoryBudget(memory, 512 * 1024) // outbound: the largest answer a handler renders
        .start(new NettyServerBuilder()
                .port(9009)
                .minConnections(10)           // optional guaranteed floor
                .maxConnections(500));        // optional fairness ceiling

FileServer.of(files)
        .withChunkSize(32 * 1024)
        .withMemoryBudget(memory)
        .start(new NettyServerBuilder().port(9010).maxConnections(500));

WsServer.of(wsApi)
        .withMaxFramePayloadLength(16 * 1024) // inbound: the largest frame a session may send
        .withMemoryBudget(memory, 16 * 1024)  // outbound: the largest frame this server publishes
        .start(new NettyServerBuilder().port(9011).maxConnections(5_000));
```

The two sizes on a server are two different directions and nothing ties them together: a two-hundred-byte
`GET` renders a megabyte of listing, and an upload endpoint answers it with `201`. Each is the size of a
buffer this server may hold, and the numbers above are only what one deployment happened to measure.

The same `ServerMemoryBudget` instance is the process-wide pool. A connection reserves the estimate derived
from the final REST, file or WebSocket settings and returns it when it closes, so capacity which is not
guaranteed follows traffic rather than belonging permanently to one port. `minConnections` and
`maxConnections` are both optional (`0` leaves that bound unset): a floor reserves `min × estimate` when the
server registers, while a ceiling remains an independent fairness limit. Admissions above the floor share
the remainder, and registration fails if its floor does not fit the capacity remaining at that moment.

This is admission accounting, not a memory sampler or an allocator limit. The percentages leave a safety
margin, but the guarantee is only as accurate as the protocol estimates and application state supplied to
it; `withAdditionalMemoryEstimate(heap, direct)` accounts for session, observer and custom-handler state a
server cannot infer. A floor deliberately trades some of the pool's elasticity for guaranteed admissions.

`ServerMemoryBudget.Builder.observer(...)` reports server registration/closure and every admission, refusal
and release. Refusal events distinguish the local connection limit, heap, direct memory, both capacities and
a closed registration; every event carries the server's `Registration`, current process reservations and
server connection count. A ceiling refusal is reported as `CONNECTION_LIMIT`. The budget keeps no historical
counters: logs, counters and gauges belong in the observer. Callbacks run outside the accounting lock, may be
concurrent, and an exception from one is caught and logged at debug through Netty's `InternalLogger`: it
cannot be allowed out, because an admission is already accounted for by the time its observer is told.

### Integration tests

What the budget promises can only be shown against a JVM which is able to run out of memory, so the checks
for it live apart from the unit tests: `newa-all/src/intTest` runs the three servers in a container held to
a real `--memory`, `-Xmx` and `-XX:MaxDirectMemorySize`, floods them from the test JVM, and asks whether the
process refused connections or died. They need a Docker daemon and a JDK 17 or newer - `gradle build` runs
neither them nor anything Docker-dependent.

```shell
./gradlew :newa-all:intTest                   # capacities from container limits, flood, one minute of soak
./gradlew :newa-all:intTest -Psoak=10m        # a soak worth the name
./gradlew :newa-all:intTest -PincludeControl  # and the same flood with no budget, which kills its container
```

### Module documentation

- [newa-rest/README.md](newa-rest/README.md) - HTTP REST routing and handlers on Netty.
- [newa-websocket/README.md](newa-websocket/README.md) - WebSocket sessions, broadcasting and subscription channels on Netty.
- [newa-performance/README.md](newa-performance/README.md) - the benchmarks the numbers above come from.
