package io.github.green4j.newa.example.rest.chunked;

import io.github.green4j.newa.example.rest.SoutRestApiObserver;
import io.github.green4j.newa.lang.Life;
import io.github.green4j.newa.rest.PushedResponseBody;
import io.github.green4j.newa.rest.ResponseChunks;
import io.github.green4j.newa.rest.RestApi;
import io.github.green4j.newa.rest.RestApiBuilder;
import io.github.green4j.newa.rest.RestContext;
import io.github.green4j.newa.rest.RestServer;
import io.github.green4j.newa.rest.StaticRestHandler;
import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;
import static io.netty.handler.codec.http.HttpHeaderNames.CACHE_CONTROL;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * A response which is never finished and is not pulled from anything: a clock, one line a second, for as long
 * as the browser tab stays open.
 * <p>
 * The other chunked responses in this package are pulled - the framework asks the cursor for more and the
 * cursor always has more. A clock has nothing to give until the next second arrives, so this one is the other
 * shape: {@link ChunkedInput#readChunk} answers null, which suspends the transfer, and a task on the
 * channel's own event loop resumes it a second later. Nothing polls and nothing sleeps; between ticks the
 * connection costs a scheduled task and no thread at all, so ten thousand open tabs are ten thousand tasks on
 * a handful of threads.
 * <p>
 * Try it:
 * <pre>
 *   open http://127.0.0.1:9011/v1/clock.html   # the clock, in a browser
 *   curl -N 'http://127.0.0.1:9011/v1/clock'   # the same stream, as it arrives
 * </pre>
 * Close the tab and the observer prints the response ending: the scheduled tick is cancelled with it, which
 * is the whole point of {@link ChunkedInput#close()} being called however a response ends.
 */
public class ScheduledChunkedRestServer {
    public static final String API_NAME = "Scheduled Chunked API";
    public static final String API_DESCRIPTION = "A response fed by the clock rather than by a cursor";
    public static final int API_VERSION = 1;
    public static final String API_BUILD_VERSION = "0.0.1";

    public static final String LOCAL_IFC = "127.0.0.1";
    public static final int PORT = 9011;
    public static final String LOCAL_SERVER_ADDRESS = String.format("http://%s:%d", LOCAL_IFC, PORT);

    /** Server-sent events: the one content type a browser will consume a line at a time on its own. */
    private static final AsciiString TEXT_EVENT_STREAM = AsciiString.cached("text/event-stream");
    private static final AsciiString TEXT_HTML = AsciiString.cached("text/html; charset=utf-8");
    private static final AsciiString NO_STORE = AsciiString.cached("no-store");

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final ResponseChunks CHUNKS = ResponseChunks.builder()
            // a tick is due every second, so a minute without one means the peer is gone, not merely slow
            .stallTimeoutMillis(60_000)
            .build();

    private static final String CLOCK_PAGE =
            "<!doctype html><meta charset=\"utf-8\"><title>Clock</title>"
            + "<body style=\"font:6rem monospace;display:grid;place-items:center;height:100vh;margin:0\">"
            + "<output id=\"t\">--:--:--</output>"
            + "<script>new EventSource('/v1/clock').onmessage=e=>t.textContent=e.data</script>";

    /**
     * The clock itself. Everything here runs on the channel's event loop - the tick is scheduled on it - so
     * the flag needs no synchronisation: the task which sets it and the read which clears it are the same
     * thread.
     */
    private static final class ClockBody extends PushedResponseBody {
        private final Channel channel;
        private final ScheduledFuture<?> tick;

        /** True when a second has passed and the line for it has not gone out yet. */
        private boolean due = true; // a clock which starts a second from now looks broken

        private boolean closed;

        private ClockBody(final RestContext context) {
            this.channel = context.channel();
            this.tick = context.executor().scheduleAtFixedRate(
                    this::onTick,
                    1,
                    1,
                    TimeUnit.SECONDS
            );
        }

        private void onTick() {
            due = true;
            // Netty documents ChunkedWriteHandler.resumeTransfer() for this; flush() reaches the same code
            // and needs no lookup of a handler which this framework installs only once a chunked response
            // asks for it
            channel.flush();
        }

        @Override
        protected ByteBuf next(final ByteBufAllocator allocator) {
            if (!due) {
                return null; // nothing until the next tick, which suspends rather than ends the response
            }
            due = false;

            // once a second per connection, so the formatter's garbage is not worth avoiding here
            final byte[] line = ("data: " + LocalTime.now().format(HH_MM_SS) + "\n\n")
                    .getBytes(StandardCharsets.US_ASCII);

            return allocator.buffer(line.length).writeBytes(line);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // the tab is gone; a tick which keeps firing for it would keep this object alive with it
            tick.cancel(false);
        }
    }

    private static RestApi buildApi() {
        final RestApiBuilder apiBuilder = new RestApiBuilder(
                API_NAME,
                API_DESCRIPTION,
                API_VERSION,
                API_BUILD_VERSION
        );

        apiBuilder.get("/clock", (context, result) -> {
            // an event stream a proxy is allowed to cache is a clock stuck in the past
            context.responseHeaders().set(CACHE_CONTROL, NO_STORE);
            result.ok(TEXT_EVENT_STREAM, new ClockBody(context));
        }).withDescription("The current time, one line a second, until the peer goes away.");

        apiBuilder.get("/clock.html", new StaticRestHandler(TEXT_HTML, CLOCK_PAGE.getBytes(StandardCharsets.UTF_8)))
                .withDescription("The same clock, for a browser to render.");

        return apiBuilder.build();
    }

    public static void main(final String[] args) throws Exception {
        final Life life = new Life();
        final RestApi api = buildApi();

        life.run(() -> {
            final NettyServer server = RestServer.of(api)
                    .withResponseChunks(CHUNKS)
                    .withObservers(SoutRestApiObserver.factory())
                    .start(new NettyServerBuilder().port(PORT).host(LOCAL_IFC));

            System.out.printf(
                    "Clock started and listening on %s. Open %s/v1/clock.html%n",
                    LOCAL_SERVER_ADDRESS,
                    LOCAL_SERVER_ADDRESS
            );

            return server;
        });
    }
}
