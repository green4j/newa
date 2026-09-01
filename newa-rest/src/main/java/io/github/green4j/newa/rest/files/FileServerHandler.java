package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.rest.ErrorHandler;
import io.github.green4j.newa.rest.FullHttpResponseContent;
import io.github.green4j.newa.rest.HttpApiObserver;
import io.github.green4j.newa.rest.HttpApiObserverFactory;
import io.github.green4j.newa.rest.InternalServerErrorException;
import io.github.green4j.newa.rest.MethodNotAllowedException;
import io.github.green4j.newa.rest.PathNotFoundException;
import io.github.green4j.newa.rest.RestException;
import io.github.green4j.newa.rest.TextErrorHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentEncoder;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Date;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCEPT_RANGES;
import static io.netty.handler.codec.http.HttpHeaderNames.ALLOW;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_RANGE;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.netty.handler.codec.http.HttpHeaderNames.LAST_MODIFIED;
import static io.netty.handler.codec.http.HttpHeaderNames.RANGE;
import static io.netty.handler.codec.http.HttpHeaderValues.BYTES;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;

/**
 * Answers the requests a {@link FileSet} owns with the files behind them, and lets every other request past
 * untouched. It goes in front of whatever answers the rest - a {@code RestApiHandler}, usually:
 * <pre>
 * Client --&gt; HttpServerCodec --&gt; HttpObjectAggregator --&gt; FileServerHandler --&gt; RestApiHandler
 * </pre>
 * One per channel, built in the {@code ChannelInitializer} like the handlers around it. The {@link FileSet}
 * is built once and shared; what a request writes into belongs to the handler, so nothing here is
 * synchronised and nothing is {@code @Sharable}.
 * <p>
 * Where the pipeline allows it the file goes from the page cache to the socket without being read into the
 * process at all - {@code sendfile(2)}, which Netty offers as {@link FileRegion}. Where it does not - TLS and
 * a content encoder both have to see the bytes, and not every channel can write a region - the file is pumped
 * through NIO instead, a chunk at a time and no faster than the peer takes it. Which of the two it is follows
 * from the pipeline and the transport, neither of which changes under a live channel, so it is worked out
 * once per channel and then simply done.
 * <p>
 * Serves {@code GET} and {@code HEAD}, answers {@code 405} to anything else on a path it owns, honours a
 * single-range {@code Range} and an {@code If-Modified-Since}, and answers a file it may not serve exactly as
 * it answers one which is not there.
 */
public class FileServerHandler extends ChannelInboundHandlerAdapter {
    /**
     * How much of a file is read at a time when it cannot be sent from the page cache.
     */
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024;

    /**
     * How long a transfer may go without a byte of it reaching the peer before the connection is given up on.
     */
    public static final int DEFAULT_STALL_TIMEOUT_MILLIS = 30_000;

    private static final int MAX_PATH_BYTES = 4096;

    private static final AsciiString ALLOWED = AsciiString.cached("GET, HEAD");

    private static final int UNDECIDED = 0;
    private static final int ZERO_COPY = 1;
    private static final int PUMPED = 2;

    /**
     * Whether a file written by the file handler of this pipeline would reach the socket without being read
     * into the process.
     *
     * @param channel the response would be written to
     * @return whether {@code sendfile(2)} is what would carry it, false if there is no file handler here
     */
    public static boolean zeroCopySupported(final Channel channel) {
        final ChannelHandlerContext ctx = channel.pipeline().context(FileServerHandler.class);
        return ctx != null && zeroCopySupported(ctx);
    }

    /**
     * The same question asked from the handler itself.
     * <p>
     * False when the channel cannot write a {@link FileRegion} at all - an embedded or a local one - and when
     * something the response is written through has to see the bytes: TLS encrypts them, a content encoder
     * compresses them, and a region reaches neither.
     * <p>
     * Only what stands between this handler and the socket counts. A response written here travels towards
     * the head of the pipeline, so a compressor added <em>after</em> this handler - which is where it belongs
     * when the API behind it wants compression and the files want {@code sendfile(2)} - never sees a byte of
     * a file, and does not cost the files anything.
     *
     * @param ctx of the file handler
     * @return whether {@code sendfile(2)} is what would carry it
     */
    public static boolean zeroCopySupported(final ChannelHandlerContext ctx) {
        if (!(ctx.channel() instanceof SocketChannel)) {
            return false;
        }
        final ChannelPipeline pipeline = ctx.pipeline();
        final List<String> names = pipeline.names();
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            if (name.equals(ctx.name())) {
                return true; // everything from here on is behind us, and never sees what we write
            }
            final ChannelHandler handler = pipeline.get(name);
            if (handler instanceof SslHandler || handler instanceof HttpContentEncoder) {
                return false;
            }
        }
        return true;
    }

    private final FileSet files;
    private final ErrorHandler errorHandler;
    private final HttpApiObserverFactory observerFactory;
    private final int chunkSize;
    private final int stallTimeoutMillis;

    private final FileSet.Match match = new FileSet.Match();
    private final ContentTypes contentTypes;
    private final byte[] decoded = new byte[MAX_PATH_BYTES];
    private final StringBuilder canonical = new StringBuilder(128);

    private int transfer = UNDECIDED;
    private boolean swallowing;

    /**
     * @param files this handler owns the paths of
     */
    public FileServerHandler(final FileSet files) {
        this(files, new TextErrorHandler(), null, DEFAULT_CHUNK_SIZE, DEFAULT_STALL_TIMEOUT_MILLIS);
    }

    /**
     * @param files this handler owns the paths of
     * @param errorHandler rendering what a refused request is answered with
     * @param observerFactory asked for an observer per request, or null to observe nothing
     */
    public FileServerHandler(final FileSet files,
                             final ErrorHandler errorHandler,
                             final HttpApiObserverFactory observerFactory) {
        this(files, errorHandler, observerFactory, DEFAULT_CHUNK_SIZE, DEFAULT_STALL_TIMEOUT_MILLIS);
    }

    /**
     * @param files this handler owns the paths of
     * @param errorHandler rendering what a refused request is answered with
     * @param observerFactory asked for an observer per request, or null to observe nothing
     * @param chunkSize a file is read in when it cannot be sent from the page cache
     * @param stallTimeoutMillis a transfer may go without a byte reaching the peer, zero to wait forever
     */
    public FileServerHandler(final FileSet files,
                             final ErrorHandler errorHandler,
                             final HttpApiObserverFactory observerFactory,
                             final int chunkSize,
                             final int stallTimeoutMillis) {
        this.files = files;
        this.errorHandler = errorHandler;
        this.observerFactory = observerFactory;
        this.chunkSize = chunkSize;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.contentTypes = new ContentTypes(files.contentTypes());
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) {
        if (swallowing) {
            // the head of this request was ours and was answered without its body; the rest of it is not
            // going anywhere else
            if (msg instanceof HttpContent) {
                swallowing = !(msg instanceof LastHttpContent);
                ReferenceCountUtil.release(msg);
                return;
            }
            swallowing = false;
        }

        if (!(msg instanceof HttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }

        final HttpRequest request = (HttpRequest) msg;
        final boolean ours;
        try {
            ours = files.match(request.uri(), match);
        } catch (final RuntimeException error) {
            // nothing has taken the message and nothing is going to: it is released here or never
            ReferenceCountUtil.release(msg);
            throw error;
        }

        if (!ours) {
            ctx.fireChannelRead(msg); // not a path this handler owns: ownership of the message goes with it
            return;
        }

        swallowing = !(msg instanceof LastHttpContent);
        try {
            answer(ctx, request);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void answer(final ChannelHandlerContext ctx,
                        final HttpRequest request) {
        final HttpApiObserver observer = observerFactory != null ? observerFactory.newObserver() : null;
        final long startedAt = observer != null ? System.nanoTime() : 0;
        if (observer != null) {
            observer.onRequestReceived(ctx, request);
        }

        try {
            serve(ctx, request, observer, startedAt);
        } catch (final RestException refused) {
            respondError(ctx, request, observer, startedAt, refused);
        } catch (final IOException | RuntimeException error) {
            respondError(ctx, request, observer, startedAt, new InternalServerErrorException(error));
        }
    }

    private void serve(final ChannelHandlerContext ctx,
                       final HttpRequest request,
                       final HttpApiObserver observer,
                       final long startedAt) throws RestException, IOException {
        final HttpMethod method = request.method();
        final boolean bodyless = HttpMethod.HEAD.equals(method);
        if (!bodyless && !HttpMethod.GET.equals(method)) {
            throw new MethodNotAllowedException(method.name());
        }

        final FileSet.Mapping mapping = match.mapping();

        String relative = relativePath(mapping);
        Path file = realPathOf(mapping, relative);
        BasicFileAttributes attributes = attributesOf(file);

        if (attributes.isDirectory()) {
            final String index = files.index();
            if (index == null || mapping.exact()) {
                throw notFound();
            }
            relative = relative + '/' + index;
            file = realPathOf(mapping, relative);
            attributes = attributesOf(file);
        }
        if (!attributes.isRegularFile()) {
            throw notFound(); // a device, a socket, a directory with no index: none is a file to answer with
        }

        final AsciiString contentType = mapping.contentType() != null
                ? mapping.contentType()
                : contentTypes.of(relative, 0, relative.length());

        final long lastModified = attributes.lastModifiedTime().toMillis();

        if (notModified(request, lastModified)) {
            respondEmpty(ctx, request, observer, startedAt, HttpResponseStatus.NOT_MODIFIED, null, -1);
            return;
        }

        if (bodyless) {
            // nothing is going to be sent, so nothing is opened either: the size of the response which was
            // not asked for is the size the file has at this moment, and no more can be said about it
            answerBodyless(ctx, request, observer, startedAt, contentType, attributes);
            return;
        }

        // opened before a single header is written, and everything after this is answered from the handle
        // rather than from the path: a file which is replaced, renamed over or unlinked from here on is
        // still the file this response measured, and one which cannot be opened has not been answered yet
        final FileChannel channel = openOrNotFound(file);

        final long size;
        try {
            size = channel.size();
        } catch (final IOException cannotBeRead) {
            closeQuietly(channel);
            throw notFound();
        }

        final ByteRange range = ByteRange.parse(request.headers().get(RANGE), size);
        if (range == ByteRange.UNSATISFIABLE) {
            closeQuietly(channel);
            respondEmpty(ctx, request, observer, startedAt,
                    HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "bytes */" + size, 0);
            return;
        }

        final long offset = range != null ? range.offset() : 0;
        final long length = range != null ? range.length() : size;

        final HttpResponse head = headOf(request, contentType, lastModified, range, offset, length, size);
        final boolean keepAlive = keepAlive(ctx, request, head.headers());

        if (length == 0) {
            closeQuietly(channel);
            ctx.write(head);
            complete(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), null, null, 0,
                    keepAlive, observer, head.status(), startedAt);
            return;
        }

        // the open file goes with it, and is closed by whichever of them takes it
        if (zeroCopy(ctx)) {
            sendRegion(ctx, head, channel, offset, length, keepAlive, observer, startedAt);
        } else {
            pump(ctx, head, channel, offset, length, keepAlive, observer, startedAt);
        }
    }

    private void answerBodyless(final ChannelHandlerContext ctx,
                                final HttpRequest request,
                                final HttpApiObserver observer,
                                final long startedAt,
                                final AsciiString contentType,
                                final BasicFileAttributes attributes) {
        final long size = attributes.size();
        final ByteRange range = ByteRange.parse(request.headers().get(RANGE), size);
        if (range == ByteRange.UNSATISFIABLE) {
            respondEmpty(ctx, request, observer, startedAt,
                    HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "bytes */" + size, 0);
            return;
        }

        final long offset = range != null ? range.offset() : 0;
        final long length = range != null ? range.length() : size;

        final HttpResponse head = headOf(request, contentType,
                attributes.lastModifiedTime().toMillis(), range, offset, length, size);
        final boolean keepAlive = keepAlive(ctx, request, head.headers());

        ctx.write(head);
        complete(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), null, null, 0,
                keepAlive, observer, head.status(), startedAt);
    }

    private static HttpResponse headOf(final HttpRequest request,
                                       final AsciiString contentType,
                                       final long lastModified,
                                       final ByteRange range,
                                       final long offset,
                                       final long length,
                                       final long size) {
        final HttpResponse head = new DefaultHttpResponse(request.protocolVersion(),
                range != null ? HttpResponseStatus.PARTIAL_CONTENT : HttpResponseStatus.OK);
        final HttpHeaders headers = head.headers();
        headers.set(CONTENT_TYPE, contentType);
        headers.set(ACCEPT_RANGES, BYTES);
        headers.set(LAST_MODIFIED, DateFormatter.format(new Date(lastModified)));
        if (range != null) {
            headers.set(CONTENT_RANGE, "bytes " + offset + '-' + (offset + length - 1) + '/' + size);
        }
        HttpUtil.setContentLength(head, length);
        return head;
    }

    private static FileChannel openOrNotFound(final Path file) throws PathNotFoundException {
        try {
            return FileChannel.open(file, StandardOpenOption.READ);
        } catch (final IOException cannotOpen) {
            // gone since it was resolved, or never readable by this process, or no longer a file at all:
            // none of that is the peer's business, and none of it is a 500
            throw notFound();
        }
    }

    /**
     * The file within its root, as the request named it. What is refused here is refused before the file
     * system is asked anything at all.
     *
     * @param mapping the request matched
     * @return the path of the file within the root, or the name of an exactly served file
     * @throws PathNotFoundException if the request may not name it
     */
    private String relativePath(final FileSet.Mapping mapping) throws PathNotFoundException {
        if (mapping.exact()) {
            if (!match.tailIsEmpty()) {
                throw notFound();
            }
            return mapping.target().getFileName().toString();
        }

        final int length = FilePaths.decode(match.path(), match.tailStart(), match.tailEnd(), decoded);
        if (length < 0) {
            throw notFound();
        }

        final String index = files.index();
        if (length == 0) {
            if (index == null) {
                throw notFound();
            }
            return index;
        }

        final String relative = new String(decoded, 0, length, StandardCharsets.UTF_8);
        if (!FilePaths.isSafe(relative)) {
            throw notFound();
        }
        return relative;
    }

    /**
     * Where the request names, as the file system knows it - which is the only form a root can be compared
     * with, since resolving the text of a path cannot see a symbolic link leading out of it.
     *
     * @param mapping the request matched
     * @param relative path of the file within the root
     * @return the real path of the file
     * @throws PathNotFoundException if it is not there, or is not somewhere this may answer from
     */
    private Path realPathOf(final FileSet.Mapping mapping,
                            final String relative) throws PathNotFoundException {
        final Path file;
        if (mapping.exact()) {
            file = mapping.target();
        } else {
            final Path resolved = mapping.target().resolve(relative).normalize();
            if (!resolved.startsWith(mapping.target())) {
                throw notFound();
            }
            file = resolved;
        }

        final Path real;
        try {
            real = file.toRealPath();
        } catch (final IOException notThere) {
            // not there, not readable, not reachable: one answer for all of them, so that asking cannot tell
            throw notFound();
        }
        if (!mapping.exact()) {
            if (!real.startsWith(mapping.target())) {
                throw notFound();
            }
            if (mapping.nestedOwns(real)) {
                throw notFound(); // it belongs to a mapping with rules of its own: ask for it by that path
            }
        }
        if (mapping.filtered() && !mapping.accepts(real, canonicalRelativeOf(mapping, real, relative))) {
            throw notFound(); // a file kept out by a filter is answered exactly as a missing one is
        }
        return real;
    }

    /**
     * What the file system calls the file, relative to its root - which is what a rule about it has to be
     * asked with. The name a request came in with is not it: a file system which does not tell {@code Secret}
     * from {@code secret}, or which keeps names in a normal form of its own, answers to more names than the
     * one it has, and a filter given the name as asked would be reading a different name from the one being
     * opened.
     *
     * @param mapping the request matched
     * @param real path of the file
     * @param requested path of it within the root, as the request spelled it
     * @return the path within the root, separated by {@code /}, valid until the next request
     */
    private CharSequence canonicalRelativeOf(final FileSet.Mapping mapping,
                                             final Path real,
                                             final String requested) {
        if (mapping.exact()) {
            return requested;
        }
        final Path relative = mapping.target().relativize(real);
        canonical.setLength(0);
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                canonical.append('/');
            }
            canonical.append(relative.getName(i).toString());
        }
        return canonical;
    }

    private static BasicFileAttributes attributesOf(final Path file) throws PathNotFoundException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class);
        } catch (final IOException cannotRead) {
            // gone since it was resolved, or never readable by this process: neither is anything the peer
            // gets told about, and neither is a 500
            throw notFound();
        }
    }

    private static PathNotFoundException notFound() {
        // the same answer whether it is missing, filtered out or outside the root: asking must not tell which
        return new PathNotFoundException(null, "No such file");
    }

    private boolean notModified(final HttpRequest request,
                                final long lastModified) {
        final String since = request.headers().get(IF_MODIFIED_SINCE);
        if (since == null) {
            return false;
        }
        final Date date = DateFormatter.parseHttpDate(since);
        if (date == null) {
            return false;
        }
        // the header carries seconds, so a file changed within the second it was sent in is not "modified"
        return lastModified / 1000 <= date.getTime() / 1000;
    }

    private void sendRegion(final ChannelHandlerContext ctx,
                            final HttpResponse head,
                            final FileChannel file,
                            final long offset,
                            final long length,
                            final boolean keepAlive,
                            final HttpApiObserver observer,
                            final long startedAt) {
        final FileRegion region = new DefaultFileRegion(file, offset, length);

        try {
            ctx.write(head);
        } catch (final RuntimeException error) {
            // the region has not been handed to anything yet, so it is this method which still owes its
            // release - and releasing it is what closes the file. After the next line it never owes it again
            region.release();
            throw error;
        }

        // ownership of the region, and of the open file in it, passes to the pipeline here: it is released
        // after the last byte, after a failed write, or when the channel goes away with the write queued
        ctx.write(region);
        final ChannelFuture written = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        // the listener first: it is what closes things, and it must be there before anything which can fail
        complete(written, null, region::transferred, length, keepAlive, observer, head.status(), startedAt);
        watch(ctx, written, region::transferred);
    }

    private void pump(final ChannelHandlerContext ctx,
                      final HttpResponse head,
                      final FileChannel channel,
                      final long offset,
                      final long length,
                      final boolean keepAlive,
                      final HttpApiObserver observer,
                      final long startedAt) throws IOException {
        ChunkedNioFile body = null;
        try {
            body = new ChunkedNioFile(channel, offset, length, chunkSize);
            // both of these can fail on a channel which is on its way out - the pipeline this is asked to
            // change may no longer hold this handler - and an open file must not be what is left of that
            ensureChunkedWrites(ctx);
            ctx.write(head);
        } catch (final IOException | RuntimeException error) {
            if (body != null) {
                closeQuietly(body);
            } else {
                closeQuietly(channel);
            }
            throw error;
        }

        // ownership of the body passes to the pipeline here: ChunkedWriteHandler closes it whether it runs to
        // the end, fails, or the channel goes away under it
        final ChannelFuture written = ctx.writeAndFlush(new HttpChunkedInput(body));

        // the listener first: it is what closes the body when the write never got as far as the handler which
        // would have closed it
        complete(written, body, body::progress, length, keepAlive, observer, head.status(), startedAt);
        watch(ctx, written, body::progress);
    }

    /**
     * Puts a {@link ChunkedWriteHandler} in front of this one, once per channel and only when a file is
     * actually pumped through it: it queues everything written through it, which the responses this handler
     * writes in one go have no use for.
     *
     * @param ctx of this handler
     */
    private static void ensureChunkedWrites(final ChannelHandlerContext ctx) {
        final ChannelPipeline pipeline = ctx.pipeline();
        if (pipeline.get(ChunkedWriteHandler.class) == null) {
            pipeline.addBefore(ctx.name(), null, new ChunkedWriteHandler());
        }
    }

    private boolean zeroCopy(final ChannelHandlerContext ctx) {
        if (transfer == UNDECIDED) {
            // neither the transport nor the pipeline changes under a live channel, so this is asked once and
            // the answer stands for every file this channel is ever answered with
            transfer = zeroCopySupported(ctx) ? ZERO_COPY : PUMPED;
        }
        return transfer == ZERO_COPY;
    }

    private void complete(final ChannelFuture written,
                          final ChunkedInput<?> body,
                          final Progress progress,
                          final long expected,
                          final boolean keepAlive,
                          final HttpApiObserver observer,
                          final HttpResponseStatus status,
                          final long startedAt) {
        written.addListener((ChannelFutureListener) completed -> {
            if (!completed.isSuccess() && body != null) {
                // the write never reached ChunkedWriteHandler, so nothing else is going to close the body
                closeQuietly(body);
            }

            final long sent = progress != null ? progress.get() : 0;
            // a file truncated after it was measured sends fewer bytes than the length which was promised.
            // Nothing can take that back, so the connection has to go rather than leave the peer reading a
            // response which will not end, and the next one on it framed against the wrong offset
            final boolean whole = sent == expected;

            if (!keepAlive || !completed.isSuccess() || !whole) {
                completed.channel().close();
            }
            if (observer != null) {
                observer.onRequestCompleted(status, sent, System.nanoTime() - startedAt);
            }
        });
    }

    private void watch(final ChannelHandlerContext ctx,
                       final ChannelFuture written,
                       final Progress progress) {
        if (stallTimeoutMillis < 1) {
            return;
        }
        try {
            new StallWatch(ctx, progress, stallTimeoutMillis).start(written);
        } catch (final RejectedExecutionException shuttingDown) {
            // the event loop is going, so the connection is going with it, and there is nothing left to watch
            // for. Never worth failing a response which is already on its way over
            ctx.close();
        }
    }

    private void respondEmpty(final ChannelHandlerContext ctx,
                              final HttpRequest request,
                              final HttpApiObserver observer,
                              final long startedAt,
                              final HttpResponseStatus status,
                              final String contentRange,
                              final int contentLength) {
        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.protocolVersion(), status, Unpooled.EMPTY_BUFFER);
        final boolean keepAlive;
        try {
            final HttpHeaders headers = response.headers();
            if (contentRange != null) {
                headers.set(CONTENT_RANGE, contentRange);
            }
            if (contentLength > -1) {
                headers.setInt(CONTENT_LENGTH, contentLength);
            }
            keepAlive = keepAlive(ctx, request, headers);
        } catch (final RuntimeException error) {
            response.release();
            throw error;
        }
        complete(ctx.writeAndFlush(response), null, null, 0, keepAlive, observer, status, startedAt);
    }

    private void respondError(final ChannelHandlerContext ctx,
                              final HttpRequest request,
                              final HttpApiObserver observer,
                              final long startedAt,
                              final RestException error) {
        if (observer != null) {
            observer.onRequestNotRouted(error);
        }

        final FullHttpResponseContent content = render(error);
        final FullHttpResponse response = new DefaultFullHttpResponse(
                request.protocolVersion(), error.status(), content.toByteBuf(ctx.alloc()));

        final boolean keepAlive;
        try {
            final HttpHeaders headers = response.headers();
            if (content.contentType() != null) {
                headers.set(CONTENT_TYPE, content.contentType());
            }
            headers.setInt(CONTENT_LENGTH, response.content().readableBytes());
            if (error instanceof MethodNotAllowedException) {
                headers.set(ALLOW, ALLOWED);
            }
            keepAlive = keepAlive(ctx, request, headers);
        } catch (final RuntimeException failed) {
            // the buffer came from the channel's allocator and is nobody else's yet
            response.release();
            throw failed;
        }
        complete(ctx.writeAndFlush(response), null, null, 0, keepAlive, observer, error.status(),
                startedAt);
    }

    private FullHttpResponseContent render(final RestException error) {
        if (error instanceof PathNotFoundException) {
            return errorHandler.handle((PathNotFoundException) error);
        }
        if (error instanceof MethodNotAllowedException) {
            return errorHandler.handle((MethodNotAllowedException) error);
        }
        if (error instanceof InternalServerErrorException) {
            return errorHandler.handle((InternalServerErrorException) error);
        }
        return errorHandler.handle(new InternalServerErrorException(error));
    }

    private static boolean keepAlive(final ChannelHandlerContext ctx,
                                     final HttpRequest request,
                                     final HttpHeaders headers) {
        final HttpVersion version = request.protocolVersion();
        final boolean keepAlive = HttpUtil.isKeepAlive(request) && ctx.channel().isActive();
        if (keepAlive) {
            if (!version.isKeepAliveDefault()) {
                headers.set(CONNECTION, KEEP_ALIVE);
            }
        } else {
            headers.set(CONNECTION, CLOSE);
        }
        return keepAlive;
    }

    private static void closeQuietly(final ChunkedInput<?> body) {
        try {
            body.close();
        } catch (final Exception ignored) {
            // there is nothing left to report it to: the response has already failed
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
            // the same: this is the path where the response is already lost
        }
    }

    /**
     * How much of the file has reached the peer - {@code FileRegion.transferred()} of the one being sent, or
     * what the pumped body says it has written.
     */
    @FunctionalInterface
    private interface Progress {
        long get();
    }

    /**
     * Gives up on a transfer whose peer has stopped taking it. Nothing is blocked while that happens - the
     * write simply sits in the channel\'s outbound buffer - but it holds an open file until the connection
     * goes, so at some point the connection has to be the thing that goes.
     */
    private static final class StallWatch implements Runnable {
        private final ChannelHandlerContext ctx;
        private final Progress progress;
        private final long timeoutNanos;
        private final long periodMillis;

        private long written;
        private long progressedAt;
        private ScheduledFuture<?> scheduled;

        private StallWatch(final ChannelHandlerContext ctx,
                           final Progress progress,
                           final int timeoutMillis) {
            this.ctx = ctx;
            this.progress = progress;
            this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            this.periodMillis = Math.max(1, timeoutMillis / 2);
        }

        private void start(final ChannelFuture completion) {
            progressedAt = System.nanoTime();
            scheduled = ctx.executor().scheduleWithFixedDelay(
                    this,
                    periodMillis,
                    periodMillis,
                    TimeUnit.MILLISECONDS
            );
            completion.addListener((ChannelFutureListener) done -> cancel());
        }

        @Override
        public void run() {
            final long now = progress.get();
            if (now != written) {
                written = now;
                progressedAt = System.nanoTime();
                return;
            }
            if (System.nanoTime() - progressedAt < timeoutNanos) {
                return;
            }
            cancel();
            ctx.close();
        }

        private void cancel() {
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
        }
    }
}
