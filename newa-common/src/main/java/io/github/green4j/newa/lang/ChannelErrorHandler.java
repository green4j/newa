/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import io.netty.channel.Channel;

/**
 * Where a failure of the connection itself is reported - a write which could not be made, bytes a decoder
 * refused, a request nothing in the pipeline answered. There is no response left to render by then, so this
 * only reports: the connection is closed by whoever called it, whether this returns or throws.
 * <p>
 * Called on the event loop of the channel, so it must not block. {@link StdErrChannelErrorHandler} is what
 * the server helpers use until they are given one.
 */
public interface ChannelErrorHandler {

    void onError(Channel channel,
                 Throwable cause);

}
