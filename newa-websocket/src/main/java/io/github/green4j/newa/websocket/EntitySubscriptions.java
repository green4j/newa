package io.github.green4j.newa.websocket;

public interface EntitySubscriptions extends Entity {

    boolean add(ClientSession session);

    boolean remove(ClientSession session);

}
