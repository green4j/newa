/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.ws.errors;

import io.github.green4j.newa.example.ws.StdOutWsApiObserver;
import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.HttpErrorHandler;
import io.github.green4j.newa.rest.JsonErrorHandler;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestApiHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.WsApi;
import io.github.green4j.newa.websocket.WsApiBuilder;
import io.github.green4j.newa.websocket.WsServer;

/**
 * What an error is on a websocket, where there is no response left to render.
 * <p>
 * Once the handshake is done a session has no status to answer with and no body to put one in, so an error is
 * one of two things and never a third:
 * <ul>
 *   <li><b>a frame of your own protocol</b>, when the application knows what went wrong - a command it does
 *       not serve, a field it will not accept. The session lives on, and no part of this library is involved
 *       in saying so: the protocol on this connection is yours;</li>
 *   <li><b>the end of the session</b>, when it does not. A {@link Receiver} which throws has said nothing
 *       about whether the state behind it is still whole, so its session is closed with a {@code 1011} - a
 *       status, which the peer can read, rather than a disconnect it can only guess at.</li>
 * </ul>
 * There is nothing to render either way, so the whole of what a failure gets is the reporting axis:
 * {@code WsApiObserver.onReceiveFailed} is given the cause as it was thrown, and is the only place it is ever
 * told. It does not reach the peer, and it does not reach the {@code ChannelErrorHandler} - a failure of the
 * application is not a failure of the channel. Watch {@code BOOM} below land in the one and not the other.
 * <p>
 * The handshake itself is an ordinary HTTP request until the {@code 101}, so a request which is not it - a
 * handshake at a path this server does not serve, or plain {@code curl} - is answered by whatever is mounted
 * behind the websocket handler. That is the one line of REST here; see {@code rest.errors.ErrorsRestServer}
 * for what an {@link HttpErrorHandler} is and for the rule a custom one has to keep.
 * <pre>
 * wscat -c ws://127.0.0.1:9015/ws/v1   then  ECHO:hi | WHAT | BOOM
 * wscat -c ws://127.0.0.1:9015/ws/v2   # not the websocket path: a 404, and no session
 * curl -sD- http://127.0.0.1:9015/v1/health
 * </pre>
 */
public class ErrorsWsServer {
    public static final int API_VERSION = 1;

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9015;

    private static final String ECHO = "ECHO:";
    private static final String BOOM = "BOOM";

    /**
     * A protocol of two commands, and everything an error can be in one:
     * <ul>
     *   <li>{@code ECHO:<text>} - answered with the text;</li>
     *   <li>anything else - answered with an error <em>of this protocol</em>, and the session lives on, which
     *       is what a client wants from a mistyped command;</li>
     *   <li>{@code BOOM} - throws, and this session ends: the cause goes to {@code onReceiveFailed} of the
     *       observer and the peer is told {@code 1011}.</li>
     * </ul>
     * Everything you can name, name here. What escapes is what the application could not classify, and for
     * that the session is the only honest answer.
     */
    private static final Receiver.Text RECEIVER = (session, message, last) -> {
        final String command = message.toString();

        if (command.startsWith(ECHO)) {
            session.sendText(command.substring(ECHO.length()));
            return;
        }

        if (BOOM.equals(command)) {
            throw new IllegalStateException("Failed to read /etc/secret/db.conf");
        }

        session.sendText("ERR: expected " + ECHO + "<text> or " + BOOM + ", got " + command);
    }; // and nothing takes binary here, so a binary frame is answered with a 1003 and the session ends

    public static void main(final String[] args) throws Exception {
        final WsApi api = new WsApiBuilder(API_VERSION)
                .withPathPrefix("ws")
                .withTextReceiver(RECEIVER)
                .withObservers(StdOutWsApiObserver.factory())
                .build();

        // the channel itself failing, which is neither of the two errors above. BOOM never reaches this
        final ChannelErrorHandler channelErrors =
                (channel, cause) -> System.out.printf("   channel failed: %s [%s]%n", cause, channel);

        // the HTTP half of the port, and this object belongs to the RestApiHandler: there is nowhere on a
        // WsApi to put one, because a websocket has nothing for it to render
        final HttpErrorHandler httpErrors = new JsonErrorHandler();

        new Life().run(() -> {
            final NettyServer server = WsServer.of(api)
                    .withChannelErrorHandler(channelErrors)
                    // behind the handshake handler: whatever was not the websocket path carries on to here
                    .withHandler(() -> new RestApiHandler(healthApi(), httpErrors, channelErrors))
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf("Server started on ws://%s:%d%s. Try:%n",
                    LOCAL_IFC, PORT, api.websocketPath());
            System.out.printf("  wscat -c ws://%s:%d%s   then:%n", LOCAL_IFC, PORT, api.websocketPath());
            System.out.println("    ECHO:hi   -> comes back");
            System.out.println("    WHAT      -> an error frame of this protocol, and the session lives on");
            System.out.println("    BOOM      -> the receiver throws: a 1011 close, and the cause is "
                    + "printed here by the observer");
            System.out.printf("  wscat -c ws://%s:%d/ws/v2          -> 404: not the websocket path%n",
                    LOCAL_IFC, PORT);
            System.out.printf("  curl -sD- http://%s:%d/v1/health   -> the HTTP half of the same port%n",
                    LOCAL_IFC, PORT);

            return server;
        });

        System.out.println("Server stopped");
    }

    private static RestApi healthApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                "Health API", "Whatever is mounted beside a websocket", API_VERSION, "0.0.1");

        apiBuilder.getJson("/health", (context, output) -> output.stringValue("ok"))
                .withDescription("Answers while the server is up.");

        return apiBuilder.build();
    }
}
