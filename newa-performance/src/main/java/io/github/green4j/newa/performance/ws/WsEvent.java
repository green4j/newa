/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.performance.ws;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.green4j.newa.performance.rest.RestPayload;

/**
 * One published event, as an object for Jackson to serialise. This is the work the Spring side does and the
 * newa side does not: the raw handler asks an {@code ObjectMapper} for the text, the broker hands the object
 * to the framework's own converter.
 * <p>
 * The order is pinned rather than left to reflection because it has to match what green-jelly writes -
 * {@code WsPayloadParityTest} compares the three servers byte for byte.
 *
 * @param type    what the object is, so a subscriber can dispatch on it before reading anything else
 * @param seq     publication sequence number, from which the client tells a hole from a repeat
 * @param t       {@code System.nanoTime()} at publication. Both processes are on one host and therefore on
 *                one monotonic clock, so the client subtracts it and has the one-way latency
 * @param channel this event was published into
 * @param symbol  of the instrument
 * @param venue   it traded on
 * @param priceMinor in minor units, the way anything which cares about money carries it
 * @param quantity traded
 * @param timestampMillis of the trade itself, which is not the instant of publication
 * @param firm    whether the price is firm
 * @param pad     whatever it takes to bring the message up to the size the run asked for
 */
@JsonPropertyOrder({
    WsPayload.TYPE,
    WsPayload.SEQ,
    WsPayload.TIME,
    WsPayload.CHANNEL,
    RestPayload.SYMBOL,
    RestPayload.VENUE,
    RestPayload.PRICE_MINOR,
    RestPayload.QUANTITY,
    RestPayload.TIMESTAMP_MILLIS,
    RestPayload.FIRM,
    WsPayload.PAD
})
public record WsEvent(
        String type,
        long seq,
        long t,
        String channel,
        String symbol,
        String venue,
        long priceMinor,
        long quantity,
        long timestampMillis,
        boolean firm,
        String pad) {

    /**
     * Every value follows from the sequence, so a server can neither cache an event nor hoist one out of the
     * measurement.
     *
     * @param channel        index this event belongs to
     * @param sequence       of this publication
     * @param publishedNanos {@code System.nanoTime()} at this publication
     * @param pad            the run's padding, as {@link WsPayload#padding(int)} worked it out
     * @return the event
     */
    public static WsEvent of(final int channel,
                             final long sequence,
                             final long publishedNanos,
                             final String pad) {
        final long key = RestPayload.key(sequence, 0);
        return new WsEvent(
                WsPayload.EVENT,
                sequence,
                publishedNanos,
                WsPayload.channelId(channel),
                RestPayload.symbol(key),
                RestPayload.venue(key),
                RestPayload.priceMinor(key),
                RestPayload.quantity(key),
                RestPayload.timestampMillis(key),
                RestPayload.firm(key),
                pad
        );
    }
}
