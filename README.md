## Netty-based Web API (NeWA)

Netty-based minimalistic REST and WebSocket server framework.

### Module documentation

- [newa-rest/README.md](newa-rest/README.md) - HTTP REST routing and handlers on Netty.
- [newa-websocket/README.md](newa-websocket/README.md) - WebSocket upgrade path and session API on Netty.

Other Gradle modules: `newa-common` (shared utilities used by REST and WebSocket), `newa-all` (combined artifact), and
`newa-example` (runnable demo servers).

### REST Server

```
io.github.green4j.newa.example.rest.hello.HelloRestServer
```

### Websocket Server

```
io.github.green4j.newa.example.ws.echo.EchoWsServer

io.github.green4j.newa.example.ws.broadcast.BroadcastWsServer

io.github.green4j.newa.example.ws.subscriptions.SubscriptionsWsServer

```
