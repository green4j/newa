/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * Builds the handle which describes an api out of what was registered on it. Handed to
 * {@code RestApiBuilder.buildWithHelp(...)}, which mounts what it returns on the api's help path -
 * {@link io.github.green4j.newa.rest.handles.JsonHelp#factory()} is the ready one.
 */
public interface RestApiHelpFactory {
    RestHandle buildHelp(RestApiParameters forRestApiParameters);
}
