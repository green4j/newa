/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

class StdErrChannelErrorHandlerTest {
    /**
     * Nothing here needs a channel: the handler prints whatever it is given, and null is what a test can
     * give it without a transport.
     */
    private static final String NO_CHANNEL = "null";

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();
    private PrintStream stdErr;

    @BeforeEach
    public void redirectStdErr() {
        stdErr = System.err;
        System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    public void restoreStdErr() {
        System.setErr(stdErr);
    }

    private String[] lines() {
        final String out = printed.toString(StandardCharsets.UTF_8);
        return out.isEmpty() ? new String[0] : out.split("\\R");
    }

    @Test
    public void aPeerWhichWentAwaySaysNothing() {
        new StdErrChannelErrorHandler().onError(null, new IOException("Connection reset by peer"));

        Assertions.assertEquals(0, lines().length, "printed: " + printed);
    }

    @Test
    public void aCauseWithoutFramesIsOneLine() {
        // an exception raised to say what a peer did carries no stack trace: its frames would name the
        // decoders it passed through, so the line is all there is to print
        final RuntimeException noFrames =
                new RuntimeException("Not a handshake request: GET /nope", null, false, false) {
                    private static final long serialVersionUID = 1L;
                };

        new StdErrChannelErrorHandler().onError(null, noFrames);

        final String[] lines = lines();

        Assertions.assertEquals(1, lines.length, "printed: " + printed);
        Assertions.assertTrue(lines[0].contains(NO_CHANNEL), lines[0]);
        Assertions.assertTrue(lines[0].contains("Not a handshake request: GET /nope"), lines[0]);
    }

    @Test
    public void aFailureKeepsItsStackTrace() {
        new StdErrChannelErrorHandler().onError(null, new IllegalStateException("Broken"));

        final String[] lines = lines();

        Assertions.assertTrue(lines.length > 2, "no stack trace printed: " + printed);
        Assertions.assertTrue(lines[0].contains(NO_CHANNEL), lines[0]);
        // the trace prints its own header, so the line above does not repeat the type and the message
        Assertions.assertFalse(lines[0].contains("Broken"), lines[0]);
        Assertions.assertTrue(lines[1].contains("Broken"), lines[1]);

        boolean framed = false;
        for (int i = 2; i < lines.length; i++) {
            framed |= lines[i].trim().startsWith("at ");
        }
        Assertions.assertTrue(framed, "no frames printed: " + printed);
    }
}
