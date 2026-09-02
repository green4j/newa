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
