package io.github.green4j.newa.text;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * <p>The class parses UTC timestamp from ISO 8601 with optional microseconds.
 * <p>Some examples: "2021-04-23T18:25:43.511Z", "2021-04-23T18:25:43.511456Z"
 * <p>The parser caches previously parsed timestamp to avoid expensive year-month-day related computations.
 * So, a monotonically increasing/decreasing timestamp is the best case for this parser.
 */
public class Iso8601ToUtcParser {
    private static final int[] TENTHS = new int[] {0, 10, 20, 30, 40, 50, 60, 70, 80, 90};
    private static final int[] HUNDREDS = new int[] {0, 100, 200, 300, 400, 500, 600, 700, 800, 900};
    private static final int[] THOUSANDS = new int[] {0, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000};

    private static final long MINUTES_IN_HOUR = 60;
    private static final long SECONDS_IN_MINUTE = 60;

    private static final long MILLIS_IN_SECOND = 1_000;
    private static final long MILLIS_IN_MINUTE = MILLIS_IN_SECOND * SECONDS_IN_MINUTE;
    private static final long MILLIS_IN_HOUR = MILLIS_IN_MINUTE * MINUTES_IN_HOUR;

    private final Calendar lastDayMidnightCalendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    private long lastDayMidnight = Long.MIN_VALUE;
    private long lastDay = Long.MIN_VALUE;
    private int micros;

    public long parse(final CharSequence timestamp) {
        final int year = THOUSANDS[timestamp.charAt(0) - '0']
                + HUNDREDS[timestamp.charAt(1) - '0']
                + TENTHS[timestamp.charAt(2) - '0']
                + (timestamp.charAt(3) - '0');

        final int month = TENTHS[timestamp.charAt(5) - '0']
                + (timestamp.charAt(6) - '0');

        final int day = TENTHS[timestamp.charAt(8) - '0']
                + (timestamp.charAt(9) - '0');

        final long valueDay = year * 10000 + month * 100 + day;

        if (lastDay != valueDay) {
            lastDayMidnightCalendar.set(Calendar.YEAR, year);
            lastDayMidnightCalendar.set(Calendar.MONTH, month - 1);
            lastDayMidnightCalendar.set(Calendar.DAY_OF_MONTH, day);
            lastDayMidnightCalendar.set(Calendar.HOUR_OF_DAY, 0);
            lastDayMidnightCalendar.set(Calendar.MINUTE, 0);
            lastDayMidnightCalendar.set(Calendar.SECOND, 0);
            lastDayMidnightCalendar.set(Calendar.MILLISECOND, 0);

            lastDayMidnight = lastDayMidnightCalendar.getTimeInMillis();

            lastDay = valueDay;
        }

        long result = lastDayMidnight
                + (TENTHS[timestamp.charAt(11) - '0']
                    + (timestamp.charAt(12) - '0')) * MILLIS_IN_HOUR
                + (TENTHS[timestamp.charAt(14) - '0']
                    + (timestamp.charAt(15) - '0')) * MILLIS_IN_MINUTE
                + (TENTHS[timestamp.charAt(17) - '0']
                    + (timestamp.charAt(18) - '0')) * MILLIS_IN_SECOND;

        if (timestamp.length() > 20) { // SSS
            result = result + HUNDREDS[timestamp.charAt(20) - '0']
                    + TENTHS[timestamp.charAt(21) - '0']
                    + (timestamp.charAt(22) - '0');
        }

        if (timestamp.length() > 24) { // micros
            micros = HUNDREDS[timestamp.charAt(23) - '0']
                    + TENTHS[timestamp.charAt(24) - '0']
                    + (timestamp.charAt(25) - '0');
        } else {
            micros = 0;
        }

        return result;
    }

    public int micros() {
        return micros;
    }
}