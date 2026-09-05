/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * What an api says about itself - the name, the version, and the endpoints registered on it. A help handle
 * is built from one of these and reads nothing else.
 */
public interface RestApiParameters {

    String name();

    String description();

    int version();

    String fullVersion();

    String buildVersion();

    Endpoint[] endpoints();

    Method[] methods();

}
