# Memory budget

REST, file and WebSocket servers can draw connection admissions from one estimated heap and direct-memory
budget instead of partitioning fixed `maxConnections` values between ports. The budget is admission
accounting: it neither samples current allocation nor limits what an admitted connection can allocate.

The runnable
[`SharedMemoryBudgetServers`](../newa-example/src/main/java/io/github/green4j/newa/example/budget/SharedMemoryBudgetServers.java)
starts all three server types on one budget.

## Set the process limits first

newa is a library in the application's JVM, not a server binary with its own launcher. Heap and direct-memory
limits therefore belong on the command which starts that JVM, before `-jar` or the main class, or in the
equivalent JVM-options field of the service manager. `JAVA_TOOL_OPTIONS` is a JVM-recognised way to supply
them where the command is not exposed; `JAVA_OPTS` is effective only when a particular launch script reads
it.

For a process with fixed limits, for example:

```shell
java -Xmx512m \
     -XX:MaxDirectMemorySize=256m \
     -Dio.netty.maxDirectMemory=268435456 \
     -XX:+ExitOnOutOfMemoryError \
     -jar application.jar
```

In a container, either keep an explicit `-Xmx` or derive the heap from the cgroup limit:

```shell
docker run --memory=1g application-image \
  java -XX:MaxRAMPercentage=50 \
       -XX:MaxDirectMemorySize=256m \
       -Dio.netty.maxDirectMemory=268435456 \
       -XX:+ExitOnOutOfMemoryError \
       -jar application.jar
```

`-Xmx` is the heap ceiling; `-Xms` chooses only its initial size. Without an explicit `-Xmx`, a
container-aware JVM applies `MaxRAMPercentage` to the cgroup limit. `ServerMemoryBudget` sees the result as
`Runtime.maxMemory()` and does not read cgroups itself.

Direct memory has two related but independent controls. `-XX:MaxDirectMemorySize` is the JVM's direct-buffer
limit and is what Netty detects by default. A positive `io.netty.maxDirectMemory` sets Netty's own limit and
the value returned by `PlatformDependent.maxDirectMemory()`; it is a raw byte count, so the options above
express the same 256 MiB in different forms. Making them equal makes the admission base unambiguous, but
does not create one process-wide off-heap limit: JDK direct buffers, Netty allocations and other native
allocations may use different paths.

The container or service-manager limit is the hard bound on the whole process. It must cover the heap, every
direct/native pool, metaspace, code cache, thread stacks and allocator slack. In particular, `-Xmx` does not
cover responses - `getJson` and `getTxt` write theirs straight into direct memory, and so does
`Utf8JsonWriter` a frame - nor their transport backlog, and a container sized as heap plus one nominal direct
limit can still be killed for RSS.

The JVM and Netty read these limits at startup. `ServerMemoryBudget.builder()` captures their two maximums,
then `build()` applies `heapPercentage` and `directMemoryPercentage` (70% each by default). Changing process
limits requires a restart; changing percentages requires a new budget and re-registration of its servers.

## Sharing one process budget

Create one `ServerMemoryBudget` and pass that same object to every server which shares the process capacity:

```java
ServerMemoryBudget memory = ServerMemoryBudget.builder()
        .heapPercentage(70)
        .directMemoryPercentage(70)
        .build();

RestServer.of(restApi)
        .withCompression()
        .withMemoryBudget(memory, 64 * 1024)       // largest ordinary response
        .start(new NettyServerBuilder()
                .port(9009)
                .minConnections(10)
                .maxConnections(500));

FileServer.of(files)
        .withChunkSize(32 * 1024)
        .withMemoryBudget(memory)
        .start(new NettyServerBuilder()
                .port(9010)
                .maxConnections(500));

WsServer.of(wsApi)
        .withCompression()
        .withMaxFramePayloadLength(256 * 1024)     // largest inbound frame, inflated included
        .withMemoryBudget(memory, 64 * 1024)       // largest outbound frame estimate
        .start(new NettyServerBuilder()
                .port(9011)
                .maxConnections(5_000));
```

The sizes in this example describe one deployment, not defaults to copy. Inbound and outbound sizes are
independent: a small `GET` may render a large listing, while a large upload may answer with `201`. Each is a
buffer the server may hold on one connection.

A connection atomically reserves the heap and direct-memory estimate derived from its server's final
settings, and returns both when it closes. Capacity which is not guaranteed to one server follows traffic
between them.

The protocol guides show how those estimates are derived and where they can be exceeded:

- [REST and file servers](../newa-rest/README.md#memory-budget)
- [WebSocket servers](../newa-websocket/README.md#memory-budget)

### Floors and ceilings

`minConnections` and `maxConnections` are independent and optional; `0` leaves that bound unset. A minimum
reserves `minConnections * estimate` when the server registers. It guarantees those admissions, but keeps
the unused reservation from other servers, and registration fails if the floor does not fit the capacity
remaining at that moment.

A maximum is a local fairness ceiling, not a memory calculation. Admissions above a server's floor compete
for the shared remainder until either capacity or that ceiling is reached.

### Application-owned memory

Built-in estimates cannot see session and observer state, retained buffers, asynchronous work or staging
introduced by custom handlers. Add their worst per-connection heap and direct cost with
`withAdditionalMemoryEstimate(heap, direct)`. Global caches and other process state are not per-connection;
leave room for them outside the budget percentages.

Work which is heavy only on some requests is better reserved for as long as it runs.
`tryReserve(heap, direct)` draws on the same capacity as connections, cannot spend a floor, and returns `null`
when there is no room. A REST handle reserves through its context instead, which answers `503` for it and,
on a server without a budget, returns `Lease.NO_BUDGET`:

```java
builder.getJson("/rows", (context, output) -> {
    try (ServerMemoryBudget.Lease rows = context.reserveMemory(limit * rowBytes, 0)) {   // the forecast
        // render the rows
    }
});
```

Close the lease when the work has really ended, which is not the moment the peer disconnects. The budget
observer is not told about a reservation; its bytes show in snapshots and event gauges, and a refused REST
request is reported to the request observer as its `503`. A `RestApiHandler` added by hand, to a `WsServer`
for example, reserves from the budget given to its constructor.

Work on threads of its own, a batch loader or a worker pool, waits for room instead of polling:
`reserve(heap, direct)` waits until both sizes fit at the same moment, and `tryReserve(heap, direct, timeout,
unit)` waits at most the timeout and returns `null` after it. Nothing is held while waiting, and a zero size
never waits for its capacity. Both throw `InterruptedException`, with nothing reserved, when the thread is
interrupted before or while waiting. There is no fairness: connections and immediate reservations may take
freed bytes ahead of a waiter, so the timeout bounds a wait without guaranteeing progress. Never wait on a
Netty event loop - connections are released there, so the loop would stall the releases it waits for.

Work which holds one kind of memory names it: `tryReserveHeap`, `reserveHeap` and the timed `tryReserveHeap`,
and the same three for `DirectMemory`, take one size and never consult the other capacity. A REST context
has the same pair beside `reserveMemory`: `reserveHeap(bytes)` and `reserveDirectMemory(bytes)`.

## Guarantee and known limits

In one `ServerMemoryBudget`, the sum of active-connection estimates, unused guaranteed floors and open
application reservations never exceeds its heap or direct-memory capacity. It does **not** guarantee that
actual heap, direct memory or RSS stays below a JVM or container limit:

- Admission uses configured per-connection estimates, not current allocation. Memory already in use is not
  sampled, and an admitted connection is not rechecked or evicted if it later grows.
- Only registrations sharing this exact budget object are counted. Another budget, an unbudgeted server,
  application caches, other libraries and allocations made before or outside a connection consume the same
  process without reserving from it.
- The REST response size and WebSocket outbound frame passed to `withMemoryBudget` are estimates, not output
  limits. A handler may produce more. Built-in inbound request and frame limits are hard bounds only for the
  built-in pipeline stages which enforce them.
- Protocol estimates assume the pipeline assembled by the corresponding server helper. A hand-written HTTP
  pipeline without `SingleHttpExchangeHandler`, for example, may serve several unfinished exchanges while
  its connection is charged for the helper's bounded depth.
- Built-in estimates cannot see application session/observer state, retained buffers, custom-handler
  staging, a custom decompressor's inflated body or an application which collects WebSocket fragments.
  Include their worst per-connection cost with `withAdditionalMemoryEstimate`.
- Per-worker render buffers, event-loop and global state, and Netty pool chunks, arenas and fragmentation do
  not rise and fall with budget leases. They need explicit headroom; a small direct budget does not prevent
  the pooled allocator from reserving memory in multi-megabyte chunks.
- The direct capacity is Netty's effective direct-memory maximum, not a ceiling on all off-heap/native
  memory. Metaspace, code cache, stacks, JNI/native allocations and JVM direct allocations can still exhaust
  the process or its cgroup.
- `-XX:+ExitOnOutOfMemoryError` handles JVM out-of-memory failures, but is not a substitute for admission and
  does not make a cgroup OOM kill graceful. Netty direct-allocation failures must also reach the
  application's fatal-error policy.

Keep safety margin in all three layers. Observe budget accounting and actual heap, direct memory and
container RSS separately, and validate the estimates under the deployment's worst request, response, frame
and slow-reader load.

## Snapshots and the budget observer

`memory.snapshot()` reports current process reservations;
`NettyServer.memoryRegistrationSnapshot()` reports one server's registration. They are gauges, not evidence
that memory released by a connection has already returned to the operating system.

`ServerMemoryBudget.Builder.observer(...)` reports registration, closure, admission, refusal and release.
Refusals distinguish `REGISTRATION_CLOSED`, `CONNECTION_LIMIT`, `HEAP`, `DIRECT_MEMORY` and
`HEAP_AND_DIRECT_MEMORY`. Every event carries the server's `Registration`, current process reservations and
server connection count.

The budget keeps no historical counters; logs, counters and gauges belong in the observer. Callbacks run
outside the accounting lock and may be concurrent. An exception from one is caught and logged at debug
through Netty's `InternalLogger`: an admission is already accounted for by the time its observer is told.

## Integration tests

The budget's claims need a JVM which is allowed to run out of memory, so these checks live apart from the
unit tests. `newa-all/src/intTest` runs the three servers in a container with real `--memory`, `-Xmx` and
`-XX:MaxDirectMemorySize` limits, floods them from the test JVM, and asks whether the process refused
connections or died.

They require a Docker daemon and JDK 17 or newer. `gradle build` runs neither them nor anything
Docker-dependent. They demonstrate capacity detection, refusal under one worst-sized-body flood and
accounting under a soak; they do not prove that an application's custom pipelines, state or workload fit
their estimates.

```shell
./gradlew :newa-all:intTest                   # capacities from container limits, flood, one minute of soak
./gradlew :newa-all:intTest -Psoak=10m        # a soak worth the name
./gradlew :newa-all:intTest -PincludeControl  # repeat the flood without a budget; the container is killed
```
