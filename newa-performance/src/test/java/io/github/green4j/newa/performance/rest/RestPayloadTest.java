package io.github.green4j.newa.performance.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestPayloadTest {

    @Test
    public void rowsOfOneResponseDiffer() {
        final long first = RestPayload.key(7, 0);
        final long second = RestPayload.key(7, 1);
        assertNotEquals(first, second);
        assertNotEquals(RestPayload.id(first), RestPayload.id(second));
    }

    @Test
    public void differentSequencesGiveDifferentRows() {
        for (int row = 0; row < RestPayload.ROWS; row++) {
            assertNotEquals(RestPayload.key(1, row), RestPayload.key(2, row));
        }
    }

    @Test
    public void everyFieldIsProducedForEverySequenceTheClientUses() {
        for (long sequence = 0; sequence < 1024; sequence++) {
            for (int row = 0; row < RestPayload.ROWS; row++) {
                final long key = RestPayload.key(sequence, row);
                assertNotNull(RestPayload.symbol(key));
                assertNotNull(RestPayload.venue(key));
                assertNotNull(RestPayload.status(key));
                assertTrue(RestPayload.priceMinor(key) > 0);
                assertTrue(RestPayload.quantity(key) > 0);
                assertTrue(RestPayload.timestampMillis(key) > 0);
            }
        }
    }
}
