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
