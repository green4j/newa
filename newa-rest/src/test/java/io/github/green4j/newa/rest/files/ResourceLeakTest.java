package io.github.green4j.newa.rest.files;

import com.sun.management.UnixOperatingSystemMXBean;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Every branch of the handler, run enough times that anything it forgets to give back shows up as a file
 * descriptor which was never closed.
 * <p>
 * An {@link EmbeddedChannel} cannot write a file region, so this is the path which really opens the file -
 * exactly the one with something to leak. The counts are checked with a wide tolerance on purpose: what is
 * being caught is a descriptor per request, not a handful the JIT or the class loader opened along the way.
 */
class ResourceLeakTest {
    private static final int REQUESTS = 300;
    private static final int TOLERANCE = 24;

    @TempDir
    private Path root;

    private ResourceLeakDetector.Level level;

    @BeforeEach
    public void setUp() throws IOException {
        level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        Files.createDirectories(root.resolve("img"));
        Files.write(root.resolve("img/a.bin"), new byte[128 * 1024]);
        Files.write(root.resolve("empty.txt"), new byte[0]);
    }

    @AfterEach
    public void tearDown() {
        ResourceLeakDetector.setLevel(level);
    }

    private static long openFiles() {
        final OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        Assumptions.assumeTrue(bean instanceof UnixOperatingSystemMXBean,
                "there is no count of open descriptors to read here");
        return ((UnixOperatingSystemMXBean) bean).getOpenFileDescriptorCount();
    }

    private FileSet files() {
        return FileSet.builder()
                .serve("/files", root, PathMask.excluding("internal/**"))
                .build();
    }

    private void run(final FileSet files,
                     final HttpRequest request,
                     final boolean closeUnderIt) {
        final EmbeddedChannel channel = closeUnderIt
                ? new EmbeddedChannel(new Closer(), new FileServerHandler(files))
                : new EmbeddedChannel(new FileServerHandler(files));
        try {
            channel.writeInbound(request);
            channel.flushOutbound();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ReferenceCountUtil.release(outbound);
            }
        } catch (final Exception expectedOnAClosedChannel) {
            // the response could not be written, which is the point of that run
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Closes the channel as the request goes past, so that everything the handler behind it writes fails
     * straight away - the one path where nothing downstream ever takes what it opened.
     */
    private static final class Closer extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx,
                                final Object msg) {
            ctx.channel().close();
            ctx.fireChannelRead(msg);
        }
    }

    private HttpRequest request(final HttpMethod method,
                                final String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }

    private void hammer(final FileSet files,
                        final int times) {
        final String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusHours(1));
        for (int i = 0; i < times; i++) {
            run(files, request(HttpMethod.GET, "/files/img/a.bin"), false);
            run(files, request(HttpMethod.HEAD, "/files/img/a.bin"), false);
            run(files, request(HttpMethod.GET, "/files/empty.txt"), false);
            run(files, request(HttpMethod.GET, "/files/missing.txt"), false);
            run(files, request(HttpMethod.GET, "/files/../etc/passwd"), false);
            run(files, request(HttpMethod.POST, "/files/img/a.bin"), false);
            run(files, request(HttpMethod.GET, "/v1/not-ours"), false);

            final HttpRequest ranged = request(HttpMethod.GET, "/files/img/a.bin");
            ranged.headers().set("Range", "bytes=999999999-");
            run(files, ranged, false);

            final HttpRequest conditional = request(HttpMethod.GET, "/files/img/a.bin");
            conditional.headers().set("If-Modified-Since", future);
            run(files, conditional, false);

            final HttpRequest partial = request(HttpMethod.GET, "/files/img/a.bin");
            partial.headers().set("Range", "bytes=100-1099");
            run(files, partial, false);

            // and the branch where the response cannot be written at all: whatever the handler opened for it
            // is its own to close, because nothing downstream ever got hold of it
            run(files, request(HttpMethod.GET, "/files/img/a.bin"), true);
        }
    }

    @Test
    public void testNoBranchHoldsOnToAFile() {
        final FileSet files = files();

        hammer(files, 5); // whatever the first run of each branch opens once, it opens before the count

        final long before = openFiles();
        hammer(files, REQUESTS / 11);
        final long after = openFiles();

        Assertions.assertTrue(after - before < TOLERANCE,
                "open descriptors went from " + before + " to " + after + " over " + REQUESTS
                        + " requests, so a branch is not closing the file it opened");
    }

    @Test
    public void testTheRequestIsAlwaysReleased() {
        final FileSet files = files();

        final DefaultFullHttpRequest ours = (DefaultFullHttpRequest) request(HttpMethod.GET, "/files/empty.txt");
        run(files, ours, false);
        Assertions.assertEquals(0, ours.refCnt(), "a request this handler answered is released by it");

        final DefaultFullHttpRequest theirs = (DefaultFullHttpRequest) request(HttpMethod.GET, "/v1/hello");
        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(files));
        try {
            channel.writeInbound(theirs);
            Assertions.assertEquals(1, theirs.refCnt(),
                    "and one it passed on is not: whoever takes it owns it");
            final Object inbound = channel.readInbound();
            Assertions.assertSame(theirs, inbound);
        } finally {
            ReferenceCountUtil.release(theirs);
            channel.finishAndReleaseAll();
        }

        final DefaultFullHttpRequest refused = (DefaultFullHttpRequest) request(HttpMethod.POST, "/files/a");
        run(files, refused, false);
        Assertions.assertEquals(0, refused.refCnt(), "and one it refused is released too");
    }

    @Test
    public void testWhatIsWrittenIsNotWrittenTwice() {
        final FileSet files = files();
        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(files));
        try {
            channel.writeInbound(request(HttpMethod.GET, "/files/missing.txt"));
            channel.flushOutbound();

            int responses = 0;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof io.netty.handler.codec.http.HttpResponse) {
                    responses++;
                }
                ReferenceCountUtil.release(outbound);
            }
            Assertions.assertEquals(1, responses, "one request is one response, whatever went wrong");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testAnUnreadableFileIsNotAStackTraceOnTheWire() throws IOException {
        final Path secret = root.resolve("locked.txt");
        Files.write(secret, "s".getBytes(StandardCharsets.UTF_8));
        Assumptions.assumeTrue(secret.toFile().setReadable(false), "cannot take the read bit away here");

        final EmbeddedChannel channel = new EmbeddedChannel(new FileServerHandler(files()));
        try {
            channel.writeInbound(request(HttpMethod.GET, "/files/locked.txt"));
            channel.flushOutbound();

            final StringBuilder body = new StringBuilder();
            int status = -1;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                if (outbound instanceof io.netty.handler.codec.http.HttpResponse) {
                    status = ((io.netty.handler.codec.http.HttpResponse) outbound).status().code();
                }
                if (outbound instanceof io.netty.handler.codec.http.HttpContent) {
                    body.append(((io.netty.handler.codec.http.HttpContent) outbound)
                            .content().toString(StandardCharsets.UTF_8));
                }
                ReferenceCountUtil.release(outbound);
            }

            Assertions.assertEquals(404, status);
            Assertions.assertFalse(body.toString().contains(root.toString()),
                    "and the answer says nothing about where the file server keeps its files");
        } finally {
            secret.toFile().setReadable(true);
            channel.finishAndReleaseAll();
        }
    }
}
