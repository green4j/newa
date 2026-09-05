/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

/**
 * The receivers the tests hand to an api.
 */
final class Receivers {

    /**
     * @return a receiver which sends every text message back.
     */
    static Receiver.Text echo() {
        return (session, message, last) -> session.sendText(message);
    }

    private Receivers() {
    }
}
