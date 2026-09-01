# newa-performance

What a REST endpoint costs on newa and on Spring Boot, with each side written the way its own framework is
normally written. The question is not what either can be tuned into: it is what you get by default, from
ordinary code, on the same machine and the same load.

**DISCLAIMER: Read the ratios, not the absolute numbers.** Everything here runs on one laptop over the loopback: client
and server share its cores and its kernel, and both scenarios end up against a limit which belongs to neither
of them - about 160 000 request-response round trips a second, about 200 000 to 300 000 delivered events a
second. Those figures are properties of this machine and would move on another one, or across a real network.
What carries over is what one side got out of a core against the other, and what each allocated to do it.

**The machine both scenarios were measured on.** Apple M1 Pro, 10 cores (8 performance, 2 efficiency), 32 GB,
macOS 26.4.1, Corretto 21.0.3 on arm64, Netty 4.2.17, Spring Boot 3.5.16, 1 GB of heap fixed at both ends. Ten
cores here are not ten of the same thing, and which kind a thread lands on is the scheduler's business.

Not published, and not part of `check`. Builds only on JDK 17 and later, so `settings.gradle` leaves it out
of the build on an older JDK. It is the one module compiled at 17 rather than at newa's own 11, which is why
records appear here and nowhere else.

## REST

### The experiment

- **The load.** `GET /v1/quotes/{sequence}` - a JSON array of 16 objects, about 2.3 KB, every field derived
  from the sequence asked for, so nothing can be cached or hoisted out of the measurement. A *client* is one
  keep-alive connection with one request in flight, sending the next when the response arrives.
- **Ordinary code on both sides.** newa writes the rows straight into the response buffer with
  `JsonGenerator`; Spring returns `List<Quote>` from a `@RestController` and lets Jackson serialise it. The
  difference between those two is the thing being measured, so both are held to the same bytes -
  `RestPayloadParityTest` fails the build if they ever diverge.
- **Defaults on both sides.** No compression, no access log, keep-alive, equal heaps. Tomcat's pool meeting a
  thousand clients with a couple of hundred threads is part of what is measured. The one default overridden is
  `server.tomcat.max-keep-alive-requests`, set to `-1`: at Boot's 100 a run spends its time reconnecting.
- **The split.** The client takes half the cores and no more; newa gets 3 workers, the fewest that reach its
  ceiling. Both run on the native transport. Server and client are separate JVMs, the server forked afresh for
  every row.
- **A row** is 30 seconds measured after 10 seconds of warmup, and counts if the server delivered the offered
  rate.

### Throughput

```
server    clients        req/s   req/core-s       MB/s srv cores cli cores     gc   gcMs  alloc B/req
newa            1        31058        63125       69.3      0.49      0.29      3      3       2228.9
newa          100       162100        55258      361.8      2.93      2.70     17     16       2244.8
newa         1000       164047        55789      366.1      2.94      2.70     17     20       2124.9
spring          1        17068        22065       38.1      0.77      0.21     12     40      14504.0
spring        100       110709        18945      247.1      5.84      1.70     79    107      15114.0
spring       1000       113439        19460      253.2      5.83      1.53     82    160      15052.6
```

### Latency, 20 000 req/s offered

```
server    clients  offered/s   actual/s    p50 us    p90 us    p99 us  p99.9 us  p99.99us    max us srv cores cli cores   backlog
newa          100      20000      20088      50.2      77.8     191.5     619.0    1958.9    3850.2      0.36      1.34         0
spring        100      20000      20096      92.2     134.1     626.7    9715.7   15261.7   18300.9      1.15      1.45       258
```

Both delivered the offered rate. Latency is timed from the instant each request was *due* rather than the
instant it went out, so a server which falls behind is charged for it.

### How many times more work newa got out of a core

| | 1 client | 100 clients | 1000 clients |
|---|---|---|---|
| throughput | 2.86x | 2.92x | 2.87x |
| latency at 20 000 req/s | | 3.17x, and 3.27x on p99 | |

`req/core-s` - requests answered per second of *server* processor time - is the number to compare on, because
the two are not allowed the same amount of machine: newa is held to its three workers while Tomcat's pool is
not, and Spring is measured using around 5.8 cores against newa's 2.9. It answers about three times as many
requests per core, and allocates about a seventh as much per request.

Raw `req/s` belongs to this machine rather than to either framework. The ratio is what carries over.

### Why it stops at about 160 000 requests a second

That is the loopback, measured in requests rather than in bytes. The cost is per request: one row per
response instead of sixteen leaves the rate exactly where it is and drops the traffic from 366 to 21 MB/s.
And it is not either side running out of processor - past three workers newa's loops fall to 68% and then
44% busy while the total stops rising, the client sits at 2.70 cores of its five, and a second client
process offering the same load adds nothing. About 160 000 request-response round trips a second, one packet
each way, is what this machine's loopback does.

Client and server therefore share a limit that belongs to neither. To see past it, drive the server from
another host.

## Websocket

### The experiment

- **The load.** One publisher thread per channel publishes at a fixed rate; a *subscriber* is one connection
  taking every channel of the run, so a row delivers `rate x channels x subscribers` events. An event is one
  JSON object of 224 bytes, every field derived from the publication sequence.
- **Ordinary code on all three sides.** newa renders with green-jelly into a buffer it reuses, with no event
  object at any point; Spring's `@EnableWebSocket` handler builds a `WsEvent` and asks an `ObjectMapper` for
  the text; Spring's simple STOMP broker is handed the object and converts it itself. `WsPayloadParityTest`
  holds all three to the same bytes.
- **Defaults on both sides, except order.** The broker's outbound channel is pinned to one thread: Boot's
  default pool delivers a destination out of order, and an ordered subscription is what the other two provide.
  What that costs is reported rather than avoided (`-Poutbound=0` puts the pool back).
- **The split.** As above - half the cores to the client, 3 delivery threads to newa and to Spring's handler,
  native transport, a forked JVM per row.
- **A row** is 30 seconds after 10 of warmup, and counts if the stream was *served*: the offered rate reached,
  p99 inside the service level (`-Plag`, 100 ms), nothing lost and nothing reordered. All four are measured by
  the client, so the verdict does not depend on what a server does with a subscriber it cannot serve -
  disconnect it, as newa does, or stall the thread writing to it, as a blocking send does. Servers get ten
  times the service level as a valve against unbounded queueing, and it decides nothing.

### Throughput

100 subscribers, one channel:

```
server        offered/s  achieved/s   events/s  events/core-s   p50 us    p99 us  alloc B/event   verdict
newa               1000        1000     100045          95811    296.4    1598.5          516.8        ok
newa               2000        2000     200006         110906    217.1     376.8          516.7        ok
newa               2500        2500     250007         109094    225.3     475.1          514.5        ok
newa               3000        2858     285760         101536    284.7  347865.1          528.6   dropped
spring             2000        2000     200003          98302    234.0     579.1         1307.8        ok
spring             2500        1650     165001         113800    153.6     414.2         1378.2   dropped
spring-stomp       1000        1000     100002          98631    402.4    1129.5         5145.1        ok
spring-stomp       2000         512      51166            n/a  6677332.0 11291066.4         n/a    behind
```

The highest rate each server served within 100 ms, and how that moves with the width of the fan-out:

| subscribers | newa | spring | spring-stomp |
|---|---|---|---|
| 10 | 200 000 /s | 200 000 /s | 100 000 /s |
| 100 | 250 000 /s | 200 000 /s | 100 000 /s |
| 1000 | 300 000 /s | under 200 000 /s | 100 000 /s |


### Running it

```
./gradlew :newa-performance:restBenchmark -Pworkers=3 -Pclients=1,100,1000
./gradlew :newa-performance:restBenchmark -Pworkers=3 -Pmode=latency -Pclients=100 -Prate=20000
./gradlew :newa-performance:wsBenchmark -Pworkers=3 -Pclients=100 -Prate=1000,2000,2500,3000
```

| `-P` | meaning | default |
|---|---|---|
| `clients` | keep-alive connections, one request in flight each. A list runs each in turn | `100` |
| `mode` | `throughput` or `latency`, never both in one run | `throughput` |
| `rate` | requests per second the client offers, `latency` only | `50000` |
| `workers` | event loops for the **newa** server. Spring keeps Boot's own thread pool | `cores - clientThreads` |
| `servers` | which servers to run, comma separated | `newa,spring` |
| `warmup` / `duration` | seconds | `10` / `30` |
| `transport` | `nio` forces the portable transport instead of the native one | auto |
| `port` / `heap` | for the forked server | `9100` / `1g` |
| `channels` | channels the fan-out publishes into, one publisher thread each | `1` |
| `message` | bytes an event is | `224` |
| `lag` | milliseconds a subscriber may be behind and still count as served | `100` |
| `outbound` | threads on the STOMP broker's outbound channel; `0` restores Boot's pool, which reorders | `1` |

`restServer -Pserver=newa|spring` leaves one server running for profiling; `restClient -Ptarget=host:port`
points the load at something already up. `wsServer` and `wsClient` are the same pair for the fan-out, with
`-Pserver=newa|spring|spring-stomp`.

`backlog` counts requests that came due with no free connection: anything much above zero says the offered
rate was past what the server could take. Responses other than 200, connections lost with a request
outstanding and connections the server closed have no column, because in a run worth reading they are zero -
when they are not, the run says so under the table.
