package io.github.green4j.newa.example.ws.subscriptions;

import io.github.green4j.newa.example.ws.StdOutWsApiObserverFactory;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsServer;
import io.github.green4j.newa.websocket.subscriptions.SubscriptionWsApiBuilder;


public class SubscriptionsWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9010;
    public static final String LOCAL_SERVER_ADDRESS = String.format("ws://%s:%d", LOCAL_IFC, PORT);

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();

        final Channels channels = new Channels();

        final SubscriptionWsApiBuilder apiBuilder = new SubscriptionWsApiBuilder(
                API_VERSION
        )
                .withPathPrefix("ws")
                .withReceiver(channels)
                .withPingIntervalMs(10_000)
                .withObservers(new StdOutWsApiObserverFactory())
                .withSkipOnBackPressure(); // both channels here restore a session with
        // a snapshot, so one which can not keep up is re-synchronized, not disconnected

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

            return server;
        });

        channels.close();

        System.out.println("Server stopped"); // never reached until life.end(...) is called
    }
}
