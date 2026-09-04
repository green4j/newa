/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.rest;

/**
 * Produces the {@link HttpObserver} which observes one request. Asked once per request, before anything
 * else about it is known.
 * <p>
 * How much that costs is yours to decide. Return a new observer per request and every stage of that request
 * has somewhere private to keep what it copied; return one shared instance and nothing is allocated at all,
 * at the price of having to work out for yourself which request a call belongs to - across channels, across
 * threads, and across a chunked response which ends long after the requests that followed it. Return null and
 * the request is not observed at all: not even the clock is read for it.
 * <p>
 * Called from event loop threads, so it must not block, and must be safe to call from several at once.
 */
@FunctionalInterface
public interface HttpObserverFactory {
    /**
     * @return an observer for the request about to be read, or null to observe it not at all
     */
    HttpObserver newObserver();
}
