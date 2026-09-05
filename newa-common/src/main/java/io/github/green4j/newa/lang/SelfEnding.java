/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

/**
 * A resource which can end without being closed - a server whose listening channel dies under it, say.
 * {@link Life#run} registers itself with whatever it opens which is one of these, so implementing this is
 * all it takes for such an end to end the {@link Life}, rather than leave the thread in {@link Life#run}
 * parked on an end nobody is left to ask for.
 */
// [try] warns that an inherited close() throws Exception, which an interrupt in a try-with-resources would
// swallow. What closes these is Life, through CloseHelper, and neither of them is a try-with-resources
@SuppressWarnings("try")
public interface SelfEnding extends AutoCloseable {

    /**
     * Tells the ender, once, when this resource has ended - however that came about, a {@link #close()}
     * asked for by its owner included, which is why what is registered here has to be idempotent, as an
     * {@link Ender} is. Registering after the end has already happened is not a race: the ender is told
     * just the same.
     *
     * @param ender to tell. Told on whichever thread the end happened on, which may be one this resource
     *              owns - so an {@link Ender} does no I/O.
     */
    void whenEnded(Ender ender);

}
