package io.github.green4j.newa.lang;

import io.netty.channel.ChannelFuture;

import java.io.Closeable;

public interface Work extends Closeable {

    /**
     * Starts a process returning ChannelFuture to wait until the process
     * has been finished
     * @return ChannelFuture to wait until the process finished
     * @throws Exception if a problem happened while doing the work
     */
    ChannelFuture doWork() throws Exception;

}
