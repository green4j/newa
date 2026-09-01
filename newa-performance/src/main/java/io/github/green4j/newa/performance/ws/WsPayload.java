package io.github.green4j.newa.performance.ws;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.performance.rest.RestPayload;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * The message every server publishes: one JSON event whose every value is derived from the publication
 * sequence number, so a server can neither cache it nor hoist it out of the run. The derivations are
 * {@link RestPayload}'s.
 * <p>
 * <b>Each server serialises it the way its own framework is written</b> - newa straight into a reused buffer
 * with green-jelly, Spring by handing a {@link WsEvent} to Jackson - which is the thing being measured. They
 * are therefore held to producing the same bytes rather than to sharing the code; {@code WsPayloadParityTest}
 * fails the build if they diverge.
 * <p>
 * {@link #SEQ} is what the client tells a hole from a repeat by, and {@link #TIME} is {@code System.nanoTime}
 * at publication: one host, one monotonic clock, so the client subtracts it for the one-way latency. Both are
 * found by name rather than by offset, because a number in ordinary JSON is as wide as it needs to be.
 */
public final class WsPayload {
    /**
     * Where every server accepts subscriptions. It is newa's {@code pathPrefix} and {@code version} spelled
     * out, and the other two are configured to match it.
     */
    public static final String PATH = "/ws/v1";

    /**
     * What a client sends to subscribe, followed by a channel id. One command per channel: a subscriber
     * holds one connection and takes every channel on it, and this is the only shape STOMP can express too.
     */
    public static final String SUBSCRIBE = "SUB:";

    /**
     * Where the STOMP broker publishes. A channel is this plus its id. It is protocol rather than
     * configuration - the client has to name the same destination the server sends to.
     */
    public static final String TOPIC = "/topic/";

    public static final String TYPE = "type";
    public static final String SEQ = "seq";
    public static final String TIME = "t";
    public static final String CHANNEL = "channel";
    public static final String PAD = "pad";

    /**
     * What every message says it is, which a consumer dispatches on before reading anything else.
     */
    public static final String EVENT = "event";

    /**
     * The sequence and instant the nominal size is worked out at, as wide as the numbers a run carries. A
     * message is within a byte or two of the size asked for rather than exactly it: real JSON writes a
     * number in as many digits as it has.
     */
    private static final long NOMINAL_SEQUENCE = 1_000_000L;
    private static final long NOMINAL_NANOS = 100_000_000_000_000L;

    /**
     * What the client scans a frame for, built once: a pattern per frame would be three allocations per
     * message measured.
     */
    private static final byte[] SEQ_PATTERN = pattern(SEQ);
    private static final byte[] TIME_PATTERN = pattern(TIME);
    private static final byte[] CHANNEL_PATTERN = pattern(CHANNEL);

    /**
     * Every field and no padding. A run may ask for a bigger message and gets the difference as padding.
     */
    public static final int MIN_SIZE = render(0, NOMINAL_SEQUENCE, NOMINAL_NANOS, "").length;

    /**
     * What a run publishes unless it says otherwise. A fan-out is limited by how many messages it pushes
     * rather than by how big they are, so an event this size keeps the count the thing being measured.
     */
    public static final int DEFAULT_SIZE = 224;

    private WsPayload() {
    }

    /**
     * @param name of a field
     * @return the bytes which precede its value in a message
     */
    private static byte[] pattern(final String name) {
        return ('"' + name + "\":").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @param channel index, from zero
     * @return the id that channel is published and subscribed under
     */
    public static String channelId(final int channel) {
        if (channel < 0 || channel > 99) {
            throw new IllegalArgumentException("channel must be 0..99, got " + channel);
        }
        return "c" + (char) ('0' + channel / 10) + (char) ('0' + channel % 10);
    }

    /**
     * The padding every message of a run carries, so the size is a property of the run and not of a server.
     *
     * @param size a message is to be, at least {@link #MIN_SIZE}
     * @return the padding to put in the last field
     */
    public static String padding(final int size) {
        if (size < MIN_SIZE) {
            throw new IllegalArgumentException("A message must be at least " + MIN_SIZE
                    + " bytes for its fields to fit, got " + size);
        }
        return "-".repeat(size - MIN_SIZE);
    }

    /**
     * Writes one event straight into whatever the generator writes to, with nothing built to hold it first.
     * This is newa's path.
     *
     * @param output         to write into
     * @param channel        index this event belongs to
     * @param sequence       of this publication
     * @param publishedNanos {@code System.nanoTime()} at this publication
     * @param pad            the run's padding, as {@link #padding(int)} worked it out
     */
    public static void render(final JsonGenerator output,
                              final int channel,
                              final long sequence,
                              final long publishedNanos,
                              final String pad) {
        final long key = RestPayload.key(sequence, 0);

        output.startObject();
        output.objectMember(TYPE);
        output.stringValue(EVENT);
        output.objectMember(SEQ);
        output.numberValue(sequence);
        output.objectMember(TIME);
        output.numberValue(publishedNanos);
        output.objectMember(CHANNEL);
        output.stringValue(channelId(channel));
        output.objectMember(RestPayload.SYMBOL);
        output.stringValue(RestPayload.symbol(key));
        output.objectMember(RestPayload.VENUE);
        output.stringValue(RestPayload.venue(key));
        output.objectMember(RestPayload.PRICE_MINOR);
        output.numberValue(RestPayload.priceMinor(key));
        output.objectMember(RestPayload.QUANTITY);
        output.numberValue(RestPayload.quantity(key));
        output.objectMember(RestPayload.TIMESTAMP_MILLIS);
        output.numberValue(RestPayload.timestampMillis(key));
        output.objectMember(RestPayload.FIRM);
        if (RestPayload.firm(key)) {
            output.trueValue();
        } else {
            output.falseValue();
        }
        output.objectMember(PAD);
        output.stringValue(pad);
        output.endObject();
    }

    /**
     * The same event in a buffer of its own. It allocates, so it is only for what is not measured: sizing the
     * padding, the snapshot a subscription opens with, and the tests.
     *
     * @param channel        index this event belongs to
     * @param sequence       of this publication
     * @param publishedNanos {@code System.nanoTime()} at this publication
     * @param pad            the run's padding
     * @return the message
     */
    public static byte[] render(final int channel,
                                final long sequence,
                                final long publishedNanos,
                                final String pad) {
        final AsciiByteArrayWriter writer = new AsciiByteArrayWriter(256);
        final JsonGenerator generator = new JsonGenerator(false);
        generator.setOutput(writer);
        render(generator, channel, sequence, publishedNanos, pad);
        generator.eoj();

        final ByteArray written = writer;
        final byte[] message = new byte[written.length()];
        System.arraycopy(written.array(), written.start(), message, 0, message.length);
        return message;
    }

    /**
     * Finds where the JSON starts inside a frame as it came off the wire. It is the first byte for a server
     * which sends the message and nothing else; a STOMP server puts a whole frame in front of it, with a
     * message id whose width grows during a run, so the body cannot be found by counting.
     *
     * @param frame as received
     * @return absolute index of the opening brace, or -1 if the frame carries no message at all
     */
    public static int bodyStart(final ByteBuf frame) {
        final int from = frame.readerIndex();
        final int to = from + frame.readableBytes();
        for (int i = from; i < to; i++) {
            if (frame.getByte(i) == '{') {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param frame as received
     * @param from  absolute index to start looking at, as {@link #bodyStart} found it
     * @return the publication sequence number this message carries
     */
    public static long readSequence(final ByteBuf frame,
                                    final int from) {
        return readLong(frame, from, SEQ_PATTERN, SEQ);
    }

    /**
     * @param frame as received
     * @param from  absolute index to start looking at
     * @return {@code System.nanoTime()} as it stood when this message was published
     */
    public static long readPublishedNanos(final ByteBuf frame,
                                          final int from) {
        return readLong(frame, from, TIME_PATTERN, TIME);
    }

    /**
     * Reads one number out of a frame by name, without decoding it and without allocating: the digits after
     * {@code "name":} are accumulated where they lie.
     *
     * @param frame   as received
     * @param from    absolute index to start looking at
     * @param pattern to scan for
     * @param name    of the field, for what a broken message has to say for itself
     * @return the value
     */
    private static long readLong(final ByteBuf frame,
                                 final int from,
                                 final byte[] pattern,
                                 final String name) {
        int at = valueAt(frame, from, pattern, name);
        final int end = frame.readerIndex() + frame.readableBytes();
        long value = 0;
        int digits = 0;
        while (at < end) {
            final int digit = frame.getByte(at) - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            value = value * 10 + digit;
            digits++;
            at++;
        }
        if (digits == 0) {
            throw new IllegalStateException("The '" + name + "' field of a message was not a number");
        }
        return value;
    }

    /**
     * @param frame as received
     * @param from  absolute index to start looking at
     * @return index of the channel this message was published into, read out of {@link #CHANNEL}
     */
    public static int readChannel(final ByteBuf frame,
                                  final int from) {
        // past the opening quote and the 'c'
        final int at = valueAt(frame, from, CHANNEL_PATTERN, CHANNEL) + 2;
        return (frame.getByte(at) - '0') * 10 + (frame.getByte(at + 1) - '0');
    }

    /**
     * @param frame   as received
     * @param from    absolute index to start looking at
     * @param pattern to scan for
     * @param name    of the field, for what a broken message has to say for itself
     * @return absolute index of the first byte of that field's value
     */
    private static int valueAt(final ByteBuf frame,
                               final int from,
                               final byte[] pattern,
                               final String name) {
        final int end = frame.readerIndex() + frame.readableBytes();
        for (int i = from; i <= end - pattern.length; i++) {
            int j = 0;
            while (j < pattern.length && frame.getByte(i + j) == pattern[j]) {
                j++;
            }
            if (j == pattern.length) {
                return i + pattern.length;
            }
        }
        throw new IllegalStateException("A message arrived without a '" + name + "' field");
    }
}
