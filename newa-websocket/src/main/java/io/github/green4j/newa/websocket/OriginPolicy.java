/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.netty.util.AsciiString;

/**
 * Which origins may complete the handshake. Every one is judged by {@link #sameOrigin()} unless
 * {@link WsServer#withOriginPolicy(OriginPolicy)} says otherwise, so a page served from anywhere else does
 * not open a session here until this server says it may.
 * <p>
 * The header is worth reading about because the same-origin policy does not cover a websocket handshake. A
 * page on any site can open one against this server, and the browser will send the cookies of <b>this</b>
 * origin with it: what a {@code fetch} would have been refused, a {@code new WebSocket(...)} is not. All the
 * browser does is tell the truth about who opened it, in the {@code Origin} header, and a server which
 * authenticates by cookie and does not read that header is a server anyone's page can read through. That is
 * why the default checks rather than not, and why opening it up is a call somebody has to write:
 * <pre>{@code
 * WsServer.of(api)                                                        // the page is served elsewhere
 *         .withOriginPolicy(OriginPolicy.allowing("https://app.example.com"))
 *         .start(9010);
 *
 * WsServer.of(api)
 *         .withOriginPolicy(OriginPolicy.any())    // a gateway in front has already decided this
 *         .start(9010);
 * }</pre>
 * A refused handshake is answered {@code 403} and the connection is closed - see {@link OriginCheckHandler},
 * which is what {@link WsServer} puts in the pipeline.
 * <p>
 * This is not CORS and does not become it: there is no preflight on a handshake and no
 * {@code Access-Control-} header on its response, so there is nothing to negotiate and the answer is
 * yes or no. The rest api answers the browser protocol proper - see {@code RestServer.withCors}.
 */
@FunctionalInterface
public interface OriginPolicy {
    /**
     * @param origin the {@code Origin} header of the handshake request, exactly as it arrived, or null when
     *               the request carries none.
     * @param host   the {@code Host} header of the same request, exactly as it arrived, or null when the
     *               request carries none - which HTTP/1.1 forbids and nothing prevents.
     * @return whether the handshake may go on.
     */
    boolean accepts(CharSequence origin, CharSequence host);

    /**
     * @param other asked whenever this one refuses.
     * @return a policy accepting whatever either of them accepts. {@code sameOrigin().or(allowing(...))} is
     *         how a server keeps what it checks by default and names one more origin besides - the two
     *         {@code allowing} and {@code strictly} replace the default rather than add to it.
     */
    default OriginPolicy or(final OriginPolicy other) {
        return (origin, host) -> accepts(origin, host) || other.accepts(origin, host);
    }

    /**
     * Accepts a handshake sent from this server's own origin, and one which carries no {@code Origin} header
     * at all. This is what a {@link WsServer} checks with until it is given something else.
     * <p>
     * Same means the {@code Origin} names the host the request was addressed to, its {@code Host} header:
     * compared without regard to case, and with the port of the scheme filled in where one side left it out,
     * a browser omitting {@code :443} from {@code https://app.example.com} while the {@code Host} may carry
     * it. The literal {@code "null"} - what a sandboxed iframe and a {@code file://} page send - names no
     * host and is refused.
     * <p>
     * The scheme is <b>not</b> compared, because a server behind a TLS terminator does not know its own:
     * what reaches it is plain HTTP, and the {@code https} of the page which opened the session would then
     * never match. What this closes is a page at another origin using the browser's credentials, which the
     * host alone decides; the channel is TLS's to defend.
     * <p>
     * The missing header is deliberate, and it is what {@link #allowing(String...)} does too. A browser
     * always sends one, so its absence says the caller is not a browser - a service, a load generator, a
     * test - and a caller which is not a browser is not the one this defends against: nothing is attaching
     * anybody's cookies to its request. Use {@link #strictly(String...)} for a server which really is only
     * ever reached by a browser.
     *
     * @return the policy.
     */
    static OriginPolicy sameOrigin() {
        return OriginPolicy::isSameOrigin;
    }

    /**
     * Accepts every handshake, whoever opened it. The way to say out loud that this server does not decide
     * this - a gateway in front of it does - rather than the way to say nothing.
     *
     * @return the policy.
     */
    static OriginPolicy any() {
        return (origin, host) -> true;
    }

    /**
     * Accepts a listed origin, and accepts a request which carries no {@code Origin} header at all. It
     * replaces {@link #sameOrigin()} rather than adding to it - {@code sameOrigin().or(allowing(...))} keeps
     * both.
     * <p>
     * The missing header is deliberate, for the reason {@link #sameOrigin()} gives.
     *
     * @param origins allowed, compared whole and without regard to case. The literal {@code "null"} - what
     *                a sandboxed iframe or a {@code file://} page sends - is an ordinary value here: list it
     *                and it is allowed, leave it out and it is not.
     * @return the policy.
     */
    static OriginPolicy allowing(final String... origins) {
        final String[] allowed = origins.clone();
        return (origin, host) -> origin == null || isListed(allowed, origin);
    }

    /**
     * Accepts a listed origin and nothing else - a request with no {@code Origin} header included. It
     * replaces {@link #sameOrigin()} rather than adding to it.
     *
     * @param origins allowed, compared whole and without regard to case.
     * @return the policy.
     */
    static OriginPolicy strictly(final String... origins) {
        final String[] allowed = origins.clone();
        return (origin, host) -> origin != null && isListed(allowed, origin);
    }

    /**
     * @param allowed the origins listed, already copied.
     * @param origin  as it arrived, never null.
     * @return whether it is one of them. Compares the {@link CharSequence} where it lies, so a header value
     *         is never turned into a {@link String} to be looked at.
     */
    private static boolean isListed(final String[] allowed, final CharSequence origin) {
        for (int i = 0; i < allowed.length; i++) {
            if (AsciiString.contentEqualsIgnoreCase(allowed[i], origin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rule of {@link #sameOrigin()}, applied where the two headers lie: nothing is copied out of them,
     * and no {@link java.net.URI} is built to answer a question two comparisons answer.
     *
     * @param origin as it arrived, or null when the request carried none.
     * @param host   as it arrived, or null when the request carried none.
     * @return whether the one names the other.
     */
    private static boolean isSameOrigin(final CharSequence origin, final CharSequence host) {
        if (origin == null) {
            return true; // not a browser, so not what this defends against
        }
        if (host == null) {
            return false; // there is nothing to be the same as
        }

        final int schemeEnd = schemeEndOf(origin);
        if (schemeEnd < 0) {
            return false; // no scheme, so it names no host - the literal "null" lands here
        }

        final int originHostFrom = schemeEnd + 3;
        final int originPortAt = portColonOf(origin, originHostFrom);
        final int hostPortAt = portColonOf(host, 0);

        final int originHostTo = originPortAt < 0 ? origin.length() : originPortAt;
        final int hostTo = hostPortAt < 0 ? host.length() : hostPortAt;

        if (originHostTo - originHostFrom != hostTo
                || !AsciiString.regionMatchesAscii(origin, true, originHostFrom, host, 0, hostTo)) {
            return false;
        }

        // the browser leaves the port of the scheme out of an Origin, and the Host header may carry it
        final int defaultPort = defaultPortOf(origin, schemeEnd);

        return (originPortAt < 0 ? defaultPort : portAt(origin, originPortAt + 1))
                == (hostPortAt < 0 ? defaultPort : portAt(host, hostPortAt + 1));
    }

    /**
     * @param origin to look in.
     * @return where the {@code "://"} of it begins, or -1 if it has none.
     */
    private static int schemeEndOf(final CharSequence origin) {
        for (int i = 0; i < origin.length() - 2; i++) {
            if (origin.charAt(i) == ':' && origin.charAt(i + 1) == '/' && origin.charAt(i + 2) == '/') {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param authority to look in - an {@code Origin} from its scheme on, or a whole {@code Host}.
     * @param from      where it begins.
     * @return where the colon of its port is, or -1 if it carries none.
     */
    private static int portColonOf(final CharSequence authority, final int from) {
        for (int i = authority.length() - 1; i >= from; i--) {
            final char c = authority.charAt(i);
            if (c == ':') {
                return i;
            }
            if (c == ']') {
                return -1; // the colons of a bracketed IPv6 literal are the address's, not a port's
            }
        }
        return -1;
    }

    /**
     * @param origin    to read the scheme of.
     * @param schemeEnd where its {@code "://"} begins.
     * @return the port that scheme is served on when none is written out, or -1 when it is a scheme this
     *         knows nothing about, in which case a port left out is filled in with nothing.
     */
    private static int defaultPortOf(final CharSequence origin, final int schemeEnd) {
        if (isScheme(origin, schemeEnd, "https") || isScheme(origin, schemeEnd, "wss")) {
            return 443;
        }
        if (isScheme(origin, schemeEnd, "http") || isScheme(origin, schemeEnd, "ws")) {
            return 80;
        }
        return -1;
    }

    /**
     * @param origin    to read the scheme of.
     * @param schemeEnd where its {@code "://"} begins.
     * @param scheme    to compare it with, without regard to case.
     * @return whether that is the scheme.
     */
    private static boolean isScheme(final CharSequence origin, final int schemeEnd, final String scheme) {
        return schemeEnd == scheme.length()
                && AsciiString.regionMatchesAscii(origin, true, 0, scheme, 0, schemeEnd);
    }

    /**
     * @param authority to read the port of.
     * @param from      the first digit of it, past the colon.
     * @return the port, or -2 for anything which is not one - a value no default is, so a port which cannot
     *         be read matches nothing but the very same text.
     */
    private static int portAt(final CharSequence authority, final int from) {
        if (from >= authority.length()) {
            return -2;
        }
        int port = 0;
        for (int i = from; i < authority.length(); i++) {
            final char digit = authority.charAt(i);
            if (digit < '0' || digit > '9') {
                return -2;
            }
            port = port * 10 + (digit - '0');
            if (port > 65535) {
                return -2;
            }
        }
        return port;
    }
}
