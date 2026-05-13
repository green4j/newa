# newa-websocket

WebSocket upgrade, framing, and session helpers on Netty.

## Goals

- Offer the same embedding style as REST: your server owns the bootstrap; the library supplies protocol handlers and
  callbacks.
- Center application logic on `ClientSession` and `Receiver`, with optional lifecycle and back-pressure hooks via
  `WsApiListener`.
- Support broadcasting text to all open sessions through `WsApi.broadcast`.

## Architecture principles

- **Handshake path**: `WsApiBuilder` computes `websocketPath()` from `withPathPrefix(...)` plus `/v<version>`. Example:
  prefix `"ws"` and version `1` yields `/ws/v1`.
- **Embedding**: The library supplies `WsApi`, `WsApiHandler`, and related callbacks only; your application owns
  `ServerBootstrap`, event loop groups, listen address, and the channel pipeline (HTTP codec stages plus
  `WsApiHandler`). This matches the embedding split used by `newa-rest`.
- **Pipeline**: Install HTTP codecs first, then `HttpObjectAggregator`, optionally `WebSocketServerCompressionHandler`,
  then `WsApiHandler` (which extends Netty `WebSocketServerProtocolHandler`). See `EchoWsServer` in `newa-example` for
  compression-on examples.
- **Receiver**: Implement `Receiver.receive(ClientSession, CharSequence)` to handle inbound text; reply with
  `ClientSession.send(...)`.
- **Listener**: `WsApiListener` extends session open/close notifications with `onWriteBackPressure`. Provide a non-null
  listener when using `SimpleWsApiBuilder` / `WsApi` construction paths that register sessions (`ClientSessions` invokes
  listener callbacks).
- **Ping scheduling**: `withPingIntervalMs(0)` disables periodic ping tasks in `ClientSession`; values greater than zero
  schedule fixed-delay pings when writes are idle.

Connection flow:

```
Client --> HttpServerCodec --> HttpObjectAggregator --> WsApiHandler --> Receiver / ClientSession
```

Subscription-oriented demos (`SubscriptionWsApiBuilder`, channels) live under
`src/main/java/io/github/green4j/newa/websocket/subscriptions/`.

## API usage patterns

1. Build a `WsApi`:
   `new SimpleWsApiBuilder(version).withPathPrefix("ws").withPingIntervalMs(10_000).withListener(listener).build()`.
2. Add `new WsApiHandler(api, receiver, channelErrorHandler)` to the pipeline (overload without `Receiver` exists when
   you only need the protocol handler side).
3. Clients connect to `ws://host:port` plus `api.websocketPath()` (for example `ws://127.0.0.1:9010/ws/v1`).

Minimal illustration:

```java
WsApi api = new SimpleWsApiBuilder(1)
        .withPathPrefix("ws")
        .withPingIntervalMs(0)
        .withListener(myWsApiListener)
        .build();
Receiver receiver = (session, message) -> session.send(message);
// pipeline: ... addLast(new WsApiHandler(api, receiver, channelErrorHandler));
```

Runnable reference: `newa-example/.../EchoWsServer.java`.