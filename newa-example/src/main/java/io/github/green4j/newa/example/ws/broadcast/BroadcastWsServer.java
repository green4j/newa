package io.github.green4j.newa.example.ws.broadcast;

import io.github.green4j.newa.example.ws.StdOutWsApiObserverFactory;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.SimpleWsApiBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsServer;

import java.util.concurrent.TimeUnit;

public class BroadcastWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();

        final SimpleWsApiBuilder apiBuilder = new SimpleWsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withPingIntervalMs(10_000)
                .withObservers(new StdOutWsApiObserverFactory());

        final WsApi api = apiBuilder.build();

        life.run(() -> {
            final NettyServer server = WsServer.of(api)
                    .withCompression()
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf(
                    "Server started and listening on %s. Websocket path: %s%s%n",
                    LOCAL_SERVER_ADDRESS,
                    LOCAL_SERVER_ADDRESS,
                    api.websocketPath()
            );

            // on the loops the sessions already live on, so a broadcast reaches them without a hand-off
            server.workerGroup().scheduleWithFixedDelay(
                    () -> api.broadcast("Hello from WS server"),
                    5_000,
                    5_000,
                    TimeUnit.MILLISECONDS
            );

            return server;
        });

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
