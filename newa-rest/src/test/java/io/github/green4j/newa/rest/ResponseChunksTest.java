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

package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The settings a server is built with, and the cursor accounting that goes with them.
 */
class ResponseChunksTest {
    @Test
    public void testDefaultsAreTheDocumentedOnes() {
        final ResponseChunks chunks = ResponseChunks.defaults();

        Assertions.assertEquals(ResponseChunks.DEFAULT_SIZE, chunks.size());
        Assertions.assertEquals(ResponseChunks.UNLIMITED_OPEN_CURSORS, chunks.maxOpenCursors());
    }

    @Test
    public void testValuesComeFromTheBuilder() {
        final ResponseChunks chunks = ResponseChunks.builder()
                .size(128 * 1024)
                .maxOpenCursors(7)
                .build();

        Assertions.assertEquals(128 * 1024, chunks.size());
        Assertions.assertEquals(7, chunks.maxOpenCursors());
    }

    @Test
    public void testNonsensicalValuesAreBroughtBackIntoRange() {
        final ResponseChunks chunks = ResponseChunks.builder()
                .size(1)
                .maxOpenCursors(-1)
                .build();

        Assertions.assertTrue(chunks.size() >= 256);
        Assertions.assertEquals(ResponseChunks.UNLIMITED_OPEN_CURSORS, chunks.maxOpenCursors());
    }

    @Test
    public void testCursorsAreHandedOutUpToTheLimit() {
        final ResponseChunks chunks = ResponseChunks.builder().maxOpenCursors(2).build();

        Assertions.assertTrue(chunks.tryOpenCursor());
        Assertions.assertTrue(chunks.tryOpenCursor());
        Assertions.assertFalse(chunks.tryOpenCursor());
        Assertions.assertEquals(2, chunks.openCursors());

        Assertions.assertEquals(1, chunks.cursorClosed());
        Assertions.assertTrue(chunks.tryOpenCursor());
    }

    @Test
    public void testCursorsAreCountedButNeverRefusedWhenUnlimited() {
        final ResponseChunks chunks = ResponseChunks.defaults();

        for (int i = 0; i < 1000; i++) {
            Assertions.assertTrue(chunks.tryOpenCursor());
        }
        Assertions.assertEquals(1000, chunks.openCursors());

        for (int i = 0; i < 1000; i++) {
            chunks.cursorClosed();
        }
        Assertions.assertEquals(0, chunks.openCursors());
    }
}
