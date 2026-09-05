/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest;

/**
 * The document both servers answer with, described once so that neither can quietly answer with something
 * cheaper than the other.
 * <p>
 * It is an array of {@link #ROWS} objects - about half a screen, a couple of kilobytes - whose values all
 * depend on the sequence number the request asked for. That is what keeps the rendering inside the
 * measurement: a server cannot hoist it out, cache it, or precompute it, and yet producing the values costs
 * a handful of arithmetic operations, so what is being timed is the framework and not the arithmetic.
 * <p>
 * Every field is a long, a string or a boolean. There are deliberately no doubles: green-jelly and Jackson
 * format them differently, which would make the two responses impossible to compare byte for byte and would
 * turn the benchmark into a comparison of two number formatters. A price is carried in minor units, the way
 * anything which cares about money carries it anyway.
 */
public final class RestPayload {
    /**
     * Rows per response. Sixteen of them come to roughly two kilobytes - a realistic API answer rather than
     * a ping.
     */
    public static final int ROWS = 16;

    /**
     * Where a server publishes it. The client appends a sequence number, and every number is a different
     * document - which is what keeps the rendering inside the measurement.
     */
    public static final String PATH_PREFIX = "/v1/quotes/";

    public static final String ID = "id";
    public static final String SYMBOL = "symbol";
    public static final String VENUE = "venue";
    public static final String PRICE_MINOR = "priceMinor";
    public static final String QUANTITY = "quantity";
    public static final String TIMESTAMP_MILLIS = "timestampMillis";
    public static final String FIRM = "firm";
    public static final String STATUS = "status";

    private static final String[] SYMBOLS = {
        "EURUSD", "GBPUSD", "USDJPY", "USDCHF",
        "AUDUSD", "USDCAD", "NZDUSD", "EURGBP",
        "EURJPY", "GBPJPY", "AUDJPY", "EURCHF",
        "USDSEK", "USDNOK", "USDMXN", "USDZAR"
    };

    private static final String[] VENUES = {"XLON", "XNYS", "XETR", "XTKS"};

    private static final String[] STATUSES = {"ACTIVE", "PENDING", "FILLED", "EXPIRED"};

    private static final long BASE_MILLIS = 1_700_000_000_000L;

    private RestPayload() {
    }

    /**
     * @param sequence the request asked for
     * @param row index within the response
     * @return the number every field of that row is derived from
     */
    public static long key(final long sequence,
                           final int row) {
        return sequence * 31L + row;
    }

    public static long id(final long key) {
        return key;
    }

    public static String symbol(final long key) {
        return SYMBOLS[(int) (key & 15L)];
    }

    public static String venue(final long key) {
        return VENUES[(int) ((key >> 4) & 3L)];
    }

    public static long priceMinor(final long key) {
        return 1_000_000L + key % 500_000L;
    }

    public static long quantity(final long key) {
        return 1L + key % 1000L * 25L;
    }

    public static long timestampMillis(final long key) {
        return BASE_MILLIS + key;
    }

    public static boolean firm(final long key) {
        return (key & 1L) == 0L;
    }

    public static String status(final long key) {
        return STATUSES[(int) ((key >> 2) & 3L)];
    }
}
