package io.github.green4j.newa.lang;

/**
 * What ends a running process. Handed to whatever may ask for the end - a {@code /shutdown} endpoint, a
 * signal handler, a supervisor - so that none of them has to know what is actually being closed.
 * <p>
 * {@link Life} is the implementation, and it is valid from the moment it is constructed: that is the point,
 * because an endpoint which ends a server has to be registered before the server exists.
 */
public interface Ender {

    /**
     * Ends it. Safe from any thread and idempotent - the first call decides, the rest do nothing.
     *
     * @param cause why it is ending, for whoever is watching it end.
     */
    void end(String cause);

}
