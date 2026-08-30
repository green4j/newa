package io.github.green4j.newa.rest;

/**
 * A {@link HttpApiObserverFactory} whose observers also see the stages after routing. Hand one of these to
 * {@link RestApiHandler} and every request is observed by a {@link RestApiObserver} rather than a plain
 * {@link HttpApiObserver}.
 */
@FunctionalInterface
public interface RestApiObserverFactory extends HttpApiObserverFactory {
    @Override
    RestApiObserver newObserver();
}
