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

package io.github.green4j.newa.performance.rest.spring;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.github.green4j.newa.performance.rest.RestPayload;

/**
 * One row of the response, as an object for Jackson to serialise. This is the work the Spring side does that
 * the newa side does not: a row exists here before it is a document.
 * <p>
 * The order is pinned rather than left to reflection, because it has to match the order green-jelly writes -
 * the two responses are compared byte for byte, and a benchmark whose two sides answer differently is not
 * measuring anything.
 */
@JsonPropertyOrder({
    RestPayload.ID,
    RestPayload.SYMBOL,
    RestPayload.VENUE,
    RestPayload.PRICE_MINOR,
    RestPayload.QUANTITY,
    RestPayload.TIMESTAMP_MILLIS,
    RestPayload.FIRM,
    RestPayload.STATUS
})
public record Quote(
        long id,
        String symbol,
        String venue,
        long priceMinor,
        long quantity,
        long timestampMillis,
        boolean firm,
        String status) {

    /**
     * @param key the row is derived from
     * @return the row
     */
    public static Quote of(final long key) {
        return new Quote(
                RestPayload.id(key),
                RestPayload.symbol(key),
                RestPayload.venue(key),
                RestPayload.priceMinor(key),
                RestPayload.quantity(key),
                RestPayload.timestampMillis(key),
                RestPayload.firm(key),
                RestPayload.status(key)
        );
    }
}
