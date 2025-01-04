package io.github.green4j.newa.rest;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

public interface RestHandle {
    interface Result {
        void ok();

        void okAndClose();

        void error(Exception error);

        void errorAndClose(Exception error);
    }

    void handle(ChannelHandlerContext ctx,
                FullHttpRequest request,
                PathParameters pathParameters,
                FullHttpResponseContent responseContent,
                Result result) throws
            PathNotFoundException,
            InternalServerErrorException;

}
