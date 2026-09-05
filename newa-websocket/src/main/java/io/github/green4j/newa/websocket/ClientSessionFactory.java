/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * An implementation MUST be thread-safe
 */
public interface ClientSessionFactory {

    ClientSession newSession(ClientSessionContext context);

}
