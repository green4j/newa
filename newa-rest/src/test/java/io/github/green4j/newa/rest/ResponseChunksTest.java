/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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
