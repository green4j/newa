package io.github.green4j.newa.lang;

import io.netty.channel.Channel;

public interface ChannelErrorHandler {

    void onError(Channel channel,
                 Throwable cause);

}
