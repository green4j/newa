/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which page is let in. Every case is one call of {@code accepts(origin, host)}, so they are written as
 * tables of it - one per family of policy. A null in either column is the header not being sent at all,
 * which is not the same as the word "null" a sandboxed iframe sends.
 */
class OriginPolicyTest {
    private static final String HOST = "app.example.com";
    private static final String ALLOWED = "https://app.example.com";

    @ParameterizedTest(name = "allowing: [{0}] -> {1}")
    @CsvSource(nullValues = "NONE", value = {
        "https://app.example.com,            true",
        "https://evil.example,               false",
        // a prefix of an allowed one is not an allowed one: the whole value is compared
        "https://app.example.com.evil.example, false",
        "https://app.example.co,             false",
        // neither the scheme nor the host is compared by case
        "HTTPS://app.EXAMPLE.COM,            true",
        // no Origin header at all: a service, a load generator, a test - none of them is the thing this
        // defends against, because nothing is attaching anybody's cookies to their request
        "NONE,                               true",
        // what a sandboxed iframe and a file:// page send, and it is not the missing header
        "null,                               false"
    })
    public void allowing(final String origin,
                         final boolean expected) {
        Assertions.assertEquals(expected,
                OriginPolicy.allowing("https://App.Example.com").accepts(origin, HOST),
                String.valueOf(origin));
    }

    @ParameterizedTest(name = "strictly: [{0}] -> {1}")
    @CsvSource(nullValues = "NONE", value = {
        "https://app.example.com, true",
        "https://evil.example,    false",
        // the difference from allowing: a caller which says nothing is not let in either
        "NONE,                    false"
    })
    public void strictlyWantsToBeToldWhoIsAsking(final String origin,
                                                 final boolean expected) {
        Assertions.assertEquals(expected, OriginPolicy.strictly(ALLOWED).accepts(origin, HOST),
                String.valueOf(origin));
    }

    @Test
    public void theWordNullIsAnOriginLikeAnyOther() {
        Assertions.assertTrue(OriginPolicy.allowing("null").accepts("null", HOST));
        Assertions.assertTrue(OriginPolicy.strictly("null").accepts("null", HOST));
    }

    @Test
    public void nothingListedAllowsNothingButTheMissingHeader() {
        Assertions.assertTrue(OriginPolicy.allowing().accepts(null, HOST));
        Assertions.assertFalse(OriginPolicy.allowing().accepts(ALLOWED, HOST));
        Assertions.assertFalse(OriginPolicy.strictly().accepts(null, HOST));
    }

    @Test
    public void whatWasListedIsNotWhatTheCallerKeeps() {
        final String[] origins = {"https://app.example.com"};
        final OriginPolicy policy = OriginPolicy.allowing(origins);

        origins[0] = "https://evil.example";

        Assertions.assertTrue(policy.accepts("https://app.example.com", HOST));
        Assertions.assertFalse(policy.accepts("https://evil.example", HOST));
    }

    @ParameterizedTest(name = "sameOrigin: [{0}] against [{1}] -> {2}")
    @CsvSource(nullValues = "NONE", value = {
        // our own page is the one let in
        "https://app.example.com,              app.example.com,      true",
        "http://app.example.com:9010,          app.example.com:9010, true",
        "https://evil.example,                 app.example.com,      false",
        // the host it names is a longer one, and a prefix is not a match
        "https://app.example.com.evil.example, app.example.com,      false",
        // a browser leaves the default port out of an Origin; a Host header may carry it
        "https://app.example.com,              app.example.com:443,  true",
        "http://app.example.com,               app.example.com:80,   true",
        "wss://app.example.com,                app.example.com:443,  true",
        // and it is the scheme's port which is filled in, not any port
        "https://app.example.com,              app.example.com:80,   false",
        "http://app.example.com:9010,          app.example.com:9011, false",
        "http://app.example.com:9010,          app.example.com,      false",
        // behind a TLS terminator this server sees plain HTTP and does not know its own scheme; what the
        // check closes is a page at another origin, which the host alone decides
        "https://app.example.com:9010,         app.example.com:9010, true",
        "http://app.example.com:9010,          app.example.com:9010, true",
        // and the case of neither header decides it
        "HTTPS://App.Example.COM,              app.EXAMPLE.com,      true",
        // an address is compared like a name, and the colons of a bracketed IPv6 literal are the
        // address's, not a port's
        "http://127.0.0.1:9010,                127.0.0.1:9010,       true",
        "http://[::1]:9010,                    '[::1]:9010',         true",
        "http://[::1],                         '[::1]:80',           true",
        "http://[::1]:9010,                    '[::1]:9011',         false",
        "http://[::2]:9010,                    '[::1]:9010',         false",
        // what names no host has nothing to be the same as
        "null,                                 app.example.com,      false",
        "app.example.com,                      app.example.com,      false",
        "'',                                   app.example.com,      false",
        // and a port which is not a number is not the port of anything
        "http://app.example.com:,              app.example.com:80,   false",
        "http://app.example.com:80x,           app.example.com:80,   false",
        // HTTP/1.1 requires the Host header; a request which sent none has said nothing to be judged
        // against, while a caller which is not a browser is still let through
        "https://app.example.com,              NONE,                 false",
        "NONE,                                 NONE,                 true",
        "NONE,                                 app.example.com,      true"
    })
    public void sameOrigin(final String origin,
                           final String host,
                           final boolean expected) {
        Assertions.assertEquals(expected, OriginPolicy.sameOrigin().accepts(origin, host),
                origin + " against " + host);
    }

    @ParameterizedTest(name = "any: [{0}] against [{1}]")
    @CsvSource(nullValues = "NONE", value = {
        "https://evil.example, app.example.com",
        "null,                 NONE",
        "NONE,                 NONE"
    })
    public void anyLetsEverythingIn(final String origin,
                                    final String host) {
        Assertions.assertTrue(OriginPolicy.any().accepts(origin, host), origin + " against " + host);
    }

    @Test
    public void orKeepsWhatEitherOfThemKeeps() {
        final OriginPolicy policy = OriginPolicy.sameOrigin().or(OriginPolicy.allowing("https://ops.example"));

        Assertions.assertTrue(policy.accepts("https://app.example.com", "app.example.com"));
        Assertions.assertTrue(policy.accepts("https://ops.example", "app.example.com"));
        Assertions.assertFalse(policy.accepts("https://evil.example", "app.example.com"));
    }
}
