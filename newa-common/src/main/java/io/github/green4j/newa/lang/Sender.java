/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

/**
 * Anything text can be sent to, with nothing said about how the message is framed or when it leaves. A
 * websocket session is one.
 */
public interface Sender {

    void send(CharSequence message);

}
