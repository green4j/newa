package io.github.green4j.newa.rest;

public interface RestApiParameters {

    String name();

    String description();

    int version();

    String fullVersion();

    String buildVersion();

    Endpoint[] endpoints();

    Method[] methods();

}
