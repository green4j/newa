package io.github.green4j.newa.text;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * <p>The class formats UTC timestamp to ISO 8601 with optional microseconds.
 * <p>Some examples: "2021-04-23T18:25:43.511Z", "2021-04-23T18:25:43.511456Z"
 * <p>The formatter caches previously formatted timestamp to avoid expensive year-month-day related computations.
 * So, a monotonically increasing/decreasing timestamp is the best case for this formatter.
 */
public class UtcToIso8601Formatter {
    private static final long HOURS_IN_DAY = 24;
    private static final long MINUTES_IN_HOUR = 60;
    private static final long SECONDS_IN_MINUTE = 60;

    private static final long MILLIS_IN_SECOND = 1_000;
    private static final long MILLIS_IN_MINUTE = MILLIS_IN_SECOND * SECONDS_IN_MINUTE;
    private static final long MILLIS_IN_HOUR = MILLIS_IN_MINUTE * MINUTES_IN_HOUR;
    private static final long MILLIS_IN_DAY = MILLIS_IN_HOUR * HOURS_IN_DAY;

    private final Calendar lastValueDayMidnightCalendar = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
    private final StringBuilder lastTextTime = new StringBuilder();
    private long lastValueDayMidnight = Long.MIN_VALUE;
    private long lastValueDay = Long.MIN_VALUE;

    public CharSequence format(final long timeMillis) {
        return format(timeMillis, -1);
    }

    /**
     * Formats time in millis with optional microseconds.
     * @param timeMillis time in millis
     * @param microsToAdd microseconds to be added at the end of the formatted value. -1 - no micros
     * @return formatted timestamp
     */
    public CharSequence format(final long timeMillis,
                               final int microsToAdd) {
        final long d = timeMillis - lastValueDayMidnight;

        if (d > MILLIS_IN_DAY || d < 0) {

            lastValueDayMidnightCalendar.setTimeInMillis(timeMillis);

            lastValueDayMidnightCalendar.set(Calendar.HOUR_OF_DAY, 0);
            lastValueDayMidnightCalendar.set(Calendar.MINUTE, 0);
            lastValueDayMidnightCalendar.set(Calendar.SECOND, 0);
            lastValueDayMidnightCalendar.set(Calendar.MILLISECOND, 0);

            lastValueDayMidnight = lastValueDayMidnightCalendar.getTimeInMillis();

            lastTextTime.setLength(0);

            final int year = lastValueDayMidnightCalendar.get(Calendar.YEAR);
            lastValueDay = year;
            lastTextTime.append(year);
            lastTextTime.append('-');

            final int month = lastValueDayMidnightCalendar.get(Calendar.MONTH) + 1;
            if (month < 10) {
                lastTextTime.append('0');
                lastValueDay = lastValueDay * 10;
            }
            lastTextTime.append(month);
            lastValueDay = lastValueDay * 10;
            lastValueDay = lastValueDay + month;
            lastTextTime.append('-');

            final int day = lastValueDayMidnightCalendar.get(Calendar.DAY_OF_MONTH);
            if (day < 10) {
                lastTextTime.append('0');
                lastValueDay = lastValueDay * 10;
            }
            lastTextTime.append(day);
            lastValueDay = lastValueDay * 10;
            lastValueDay = lastValueDay + day;

            lastTextTime.append('T');
        } else {
            if (lastTextTime.length() == 0) {

                lastTextTime.append(lastValueDayMidnightCalendar.get(Calendar.YEAR));
                lastTextTime.append('-');

                final int month = lastValueDayMidnightCalendar.get(Calendar.MONTH) + 1;
                if (month < 10) {
                    lastTextTime.append('0');
                }
                lastTextTime.append(month);
                lastTextTime.append('-');

                final int day = lastValueDayMidnightCalendar.get(Calendar.DAY_OF_MONTH);
                if (day < 10) {
                    lastTextTime.append('0');
                }
                lastTextTime.append(day);

                lastTextTime.append('T');
            } else {
                lastTextTime.setLength(11);
            }
        }

        final long hours = timeMillis / MILLIS_IN_HOUR;
        final long minutes = timeMillis / MILLIS_IN_MINUTE;
        final long seconds = timeMillis / MILLIS_IN_SECOND;

        final long hh = hours - (timeMillis / MILLIS_IN_DAY * HOURS_IN_DAY);
        if (hh < 10) {
            lastTextTime.append('0');
        }
        lastTextTime.append(hh);
        lastTextTime.append(':');

        final long mm = minutes - hours * MINUTES_IN_HOUR;
        if (mm < 10) {
            lastTextTime.append('0');
        }
        lastTextTime.append(mm);
        lastTextTime.append(':');

        final long ss = seconds - minutes * SECONDS_IN_MINUTE;
        if (ss < 10) {
            lastTextTime.append('0');
        }
        lastTextTime.append(ss);
        lastTextTime.append('.');

        final long millis = timeMillis - seconds * MILLIS_IN_SECOND;
        if (millis < 100) {
            lastTextTime.append('0');
            if (millis < 10) {
                lastTextTime.append('0');
            }
        }
        lastTextTime.append(millis);

        if (microsToAdd > -1) {
            if (microsToAdd < 100) {
                lastTextTime.append('0');
                if (microsToAdd < 10) {
                    lastTextTime.append('0');
                }
            }
            lastTextTime.append(microsToAdd);
        }

        lastTextTime.append('Z');

        return lastTextTime;
    }
}
