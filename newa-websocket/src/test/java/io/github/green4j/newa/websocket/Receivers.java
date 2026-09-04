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


package io.github.green4j.newa.websocket;

import java.util.function.BiConsumer;

/**
 * The receivers the tests hand to an api. {@link Receiver} takes what it overrides and refuses the rest, so
 * one written for a test is a class rather than a lambda - these keep that out of the tests themselves.
 */
final class Receivers {

    /**
     * @return a receiver which sends every text message back and takes no binary.
     */
    static Receiver echo() {
        return ofText((session, message) -> session.sendText(message));
    }

    /**
     * @param handler what every text message goes to.
     * @return a receiver which takes text and refuses binary with a 1003.
     */
    static Receiver ofText(final BiConsumer<ClientSession, CharSequence> handler) {
        return new Receiver() {
            @Override
            public void text(final ClientSession session,
                             final CharSequence message,
                             final boolean last) {
                handler.accept(session, message);
            }
        };
    }

    private Receivers() {
    }
}
