package io.github.green4j.newa.websocket;

/**
 * An implementation MUST be thread-safe
 */
public interface ClientSessionFactory {

    ClientSession newSession(ClientSessionContext context);

}
