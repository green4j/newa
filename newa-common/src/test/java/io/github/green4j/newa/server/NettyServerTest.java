package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * newa-common has no HTTP codec, so this drives the smallest thing a server can be: a handler which writes
 * back whatever was read.
 */
class NettyServerTest {
    private static final String HOST = "127.0.0.1";
    private static final int TIMEOUT_MILLIS = 5_000;

    private static final class Echo extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx,
                                final Object msg) {
            ctx.writeAndFlush(msg); // the buffer goes to the channel, which releases it
        }
    }

    private static NettyServerBuilder echoServer() {
        return new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(new Echo());
                    }
                });
    }

    private static int roundTrip(final int port,
                                 final int sent) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);

            final OutputStream out = socket.getOutputStream();
            out.write(sent);
            out.flush();

            final InputStream in = socket.getInputStream();
            return in.read();
        }
    }

    @Test
    public void bindsAnEphemeralPortAndEchoes() throws Exception {
        try (NettyServer server = echoServer().start()) {
            Assertions.assertTrue(server.port() > 0);
            Assertions.assertEquals(42, roundTrip(server.port(), 42));
        }
    }

    @Test
    public void pipelineConsumerIsTheSameSink() throws Exception {
        try (NettyServer server = new NettyServerBuilder()
                .host(HOST)
                .port(0)
                .workerThreads(1)
                .pipeline(pipeline -> pipeline.addLast(new Echo()))
                .start()) {
            Assertions.assertEquals(7, roundTrip(server.port(), 7));
        }
    }

    @Test
    public void closeIsIdempotentAndStopsAccepting() throws Exception {
        final NettyServer server = echoServer().start();
        final int port = server.port();

        server.close();
        server.close(); // the second one must do nothing rather than wait or throw

        Assertions.assertThrows(ConnectException.class, () -> roundTrip(port, 1));
    }

    @Test
    public void startWithoutAChildHandlerIsRefused() {
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> new NettyServerBuilder().port(0).start()
        );
    }

    @Test
    public void bindFailureIsReportedAndLeavesTheGroupsShutDown() throws Exception {
        try (NettyServer taken = echoServer().start()) {
            final NettyServerBuilder clashing = new NettyServerBuilder()
                    .host(HOST)
                    .port(taken.port())
                    .workerThreads(1)
                    .pipeline(pipeline -> pipeline.addLast(new Echo()));

            Assertions.assertThrows(IllegalStateException.class, clashing::start);

            // a bind which failed must not have kept the ports or the threads of the attempt: another
            // server still starts, and the JVM this test runs in still exits
            try (NettyServer next = echoServer().start()) {
                Assertions.assertEquals(9, roundTrip(next.port(), 9));
            }
        }
    }

    @Test
    public void workerGroupRunsScheduledWork() throws Exception {
        try (NettyServer server = echoServer().start()) {
            final CountDownLatch fired = new CountDownLatch(1);
            server.workerGroup().schedule(fired::countDown, 1, TimeUnit.MILLISECONDS);
            Assertions.assertTrue(fired.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void childOptionOverridesTheDefault() throws Exception {
        // TCP_NODELAY is on by default; asking for it again is how a default is taken back
        try (NettyServer server = echoServer()
                .childOption(ChannelOption.TCP_NODELAY, Boolean.FALSE)
                .start()) {
            Assertions.assertEquals(3, roundTrip(server.port(), 3));
        }
    }
}
