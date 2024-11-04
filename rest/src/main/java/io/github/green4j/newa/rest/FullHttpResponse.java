package io.github.green4j.newa.rest;

import io.netty.util.AsciiString;

public interface FullHttpResponse {

    void setContent(AsciiString contentType,
                    byte[] content,
                    int offset,
                    int length);

}
