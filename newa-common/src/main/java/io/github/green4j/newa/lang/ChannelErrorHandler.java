package io.github.green4j.newa.lang;

import io.netty.channel.Channel;

import java.io.IOException;

public interface ChannelErrorHandler {

    /**
     * Ignores a peer which went away and prints everything else to stderr. A connection reset or a half
     * closed socket is what the end of every client looks like, and reporting it says nothing about this
     * server; anything else is worth seeing, and worth the stack trace.
     *
     * <p>What the server helpers use when none was given. Pass one of your own to reach a logger.
     *
     * @return a handler which prints what is not an {@link IOException}.
     */
    static ChannelErrorHandler printingToStdErr() {
        return (channel, cause) -> {
            if (cause instanceof IOException) {
                return;
            }
            System.err.printf(
                    "An error %s in the channel: %s%n",
                    cause.getMessage(),
                    channel
            );
            cause.printStackTrace(System.err);
        };
    }

    void onError(Channel channel,
                 Throwable cause);

}
