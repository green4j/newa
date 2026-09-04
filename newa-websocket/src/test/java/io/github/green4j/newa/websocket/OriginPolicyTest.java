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


package io.github.green4j.newa.websocket;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OriginPolicyTest {
    private static final String HOST = "app.example.com";

    @Test
    public void aListedOriginIsAllowed() {
        final OriginPolicy policy = OriginPolicy.allowing("https://app.example.com");

        Assertions.assertTrue(policy.accepts("https://app.example.com", HOST));
    }

    @Test
    public void anUnlistedOriginIsNot() {
        final OriginPolicy policy = OriginPolicy.allowing("https://app.example.com");

        Assertions.assertFalse(policy.accepts("https://evil.example", HOST));
        // a prefix of an allowed one is not an allowed one: the whole value is compared
        Assertions.assertFalse(policy.accepts("https://app.example.com.evil.example", HOST));
        Assertions.assertFalse(policy.accepts("https://app.example.co", HOST));
    }

    @Test
    public void theCaseOfTheSchemeAndTheHostDoesNotDecideIt() {
        final OriginPolicy policy = OriginPolicy.allowing("https://App.Example.com");

        Assertions.assertTrue(policy.accepts("HTTPS://app.EXAMPLE.COM", HOST));
    }

    @Test
    public void whatIsNotABrowserIsLetThrough() {
        final OriginPolicy policy = OriginPolicy.allowing("https://app.example.com");

        // no Origin header at all: a service, a load generator, a test - none of them is the thing this
        // defends against, because nothing is attaching anybody's cookies to their request
        Assertions.assertTrue(policy.accepts(null, HOST));
    }

    @Test
    public void strictlyWantsToBeToldWhoIsAsking() {
        final OriginPolicy policy = OriginPolicy.strictly("https://app.example.com");

        Assertions.assertTrue(policy.accepts("https://app.example.com", HOST));
        Assertions.assertFalse(policy.accepts("https://evil.example", HOST));
        Assertions.assertFalse(policy.accepts(null, HOST));
    }

    @Test
    public void theWordNullIsAnOriginLikeAnyOther() {
        // what a sandboxed iframe and a file:// page send, and it is not the missing header
        Assertions.assertFalse(OriginPolicy.allowing("https://app.example.com").accepts("null", HOST));
        Assertions.assertTrue(OriginPolicy.allowing("null").accepts("null", HOST));
        Assertions.assertTrue(OriginPolicy.strictly("null").accepts("null", HOST));
    }

    @Test
    public void nothingListedAllowsNothingButTheMissingHeader() {
        Assertions.assertTrue(OriginPolicy.allowing().accepts(null, HOST));
        Assertions.assertFalse(OriginPolicy.allowing().accepts("https://app.example.com", HOST));
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

    @Test
    public void ourOwnPageIsTheOneLetIn() {
        final OriginPolicy policy = OriginPolicy.sameOrigin();

        Assertions.assertTrue(policy.accepts("https://app.example.com", "app.example.com"));
        Assertions.assertTrue(policy.accepts("http://app.example.com:9010", "app.example.com:9010"));
        Assertions.assertFalse(policy.accepts("https://evil.example", "app.example.com"));
        // the host it names is a longer one, and a prefix is not a match
        Assertions.assertFalse(policy.accepts("https://app.example.com.evil.example", "app.example.com"));
    }

    @Test
    public void thePortOfTheSchemeIsFilledInWhereItWasLeftOut() {
        final OriginPolicy policy = OriginPolicy.sameOrigin();

        // a browser leaves the default port out of an Origin; a Host header may carry it
        Assertions.assertTrue(policy.accepts("https://app.example.com", "app.example.com:443"));
        Assertions.assertTrue(policy.accepts("http://app.example.com", "app.example.com:80"));
        Assertions.assertTrue(policy.accepts("wss://app.example.com", "app.example.com:443"));
        // and it is the scheme's port which is filled in, not any port
        Assertions.assertFalse(policy.accepts("https://app.example.com", "app.example.com:80"));
        Assertions.assertFalse(policy.accepts("http://app.example.com:9010", "app.example.com:9011"));
        Assertions.assertFalse(policy.accepts("http://app.example.com:9010", "app.example.com"));
    }

    @Test
    public void theSchemeIsNotWhatItCompares() {
        // behind a TLS terminator this server sees plain HTTP and does not know its own scheme; what the
        // check closes is a page at another origin, which the host alone decides
        Assertions.assertTrue(
                OriginPolicy.sameOrigin().accepts("https://app.example.com:9010", "app.example.com:9010"));
        Assertions.assertTrue(
                OriginPolicy.sameOrigin().accepts("http://app.example.com:9010", "app.example.com:9010"));
    }

    @Test
    public void theCaseOfNeitherHeaderDecidesIt() {
        Assertions.assertTrue(OriginPolicy.sameOrigin().accepts("HTTPS://App.Example.COM", "app.EXAMPLE.com"));
    }

    @Test
    public void anAddressIsComparedLikeAName() {
        final OriginPolicy policy = OriginPolicy.sameOrigin();

        Assertions.assertTrue(policy.accepts("http://127.0.0.1:9010", "127.0.0.1:9010"));
        // the colons of a bracketed IPv6 literal are the address's, not a port's
        Assertions.assertTrue(policy.accepts("http://[::1]:9010", "[::1]:9010"));
        Assertions.assertTrue(policy.accepts("http://[::1]", "[::1]:80"));
        Assertions.assertFalse(policy.accepts("http://[::1]:9010", "[::1]:9011"));
        Assertions.assertFalse(policy.accepts("http://[::2]:9010", "[::1]:9010"));
    }

    @Test
    public void whatNamesNoHostIsRefused() {
        final OriginPolicy policy = OriginPolicy.sameOrigin();

        // the literal a sandboxed iframe and a file:// page send: it carries no host to be the same as
        Assertions.assertFalse(policy.accepts("null", "app.example.com"));
        Assertions.assertFalse(policy.accepts("app.example.com", "app.example.com"));
        Assertions.assertFalse(policy.accepts("", "app.example.com"));
        // and a port which is not a number is not the port of anything
        Assertions.assertFalse(policy.accepts("http://app.example.com:", "app.example.com:80"));
        Assertions.assertFalse(policy.accepts("http://app.example.com:80x", "app.example.com:80"));
    }

    @Test
    public void withoutAHostThereIsNothingToBeTheSameAs() {
        // HTTP/1.1 requires the header; a request which sent none has said nothing to be judged against
        Assertions.assertFalse(OriginPolicy.sameOrigin().accepts("https://app.example.com", null));
        // but a caller which is not a browser is still let through
        Assertions.assertTrue(OriginPolicy.sameOrigin().accepts(null, null));
        Assertions.assertTrue(OriginPolicy.sameOrigin().accepts(null, "app.example.com"));
    }

    @Test
    public void anyLetsEverythingIn() {
        Assertions.assertTrue(OriginPolicy.any().accepts("https://evil.example", "app.example.com"));
        Assertions.assertTrue(OriginPolicy.any().accepts("null", null));
        Assertions.assertTrue(OriginPolicy.any().accepts(null, null));
    }

    @Test
    public void orKeepsWhatEitherOfThemKeeps() {
        final OriginPolicy policy = OriginPolicy.sameOrigin().or(OriginPolicy.allowing("https://ops.example"));

        Assertions.assertTrue(policy.accepts("https://app.example.com", "app.example.com"));
        Assertions.assertTrue(policy.accepts("https://ops.example", "app.example.com"));
        Assertions.assertFalse(policy.accepts("https://evil.example", "app.example.com"));
    }
}
