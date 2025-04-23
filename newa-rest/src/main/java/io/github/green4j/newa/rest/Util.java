package io.github.green4j.newa.rest;


import io.github.green4j.newa.text.UtcToIso8601Formatter;

import java.util.concurrent.TimeUnit;

public abstract class Util {
    public static final ThreadLocal<UtcToIso8601Formatter> UTC_TO_ISO_8601_FORMATTER_THREAD_LOCAL =
            ThreadLocal.withInitial(UtcToIso8601Formatter::new);

    public static CharSequence formatUtcToIso8601(final long millis) {
        final UtcToIso8601Formatter utcFormatter =
                UTC_TO_ISO_8601_FORMATTER_THREAD_LOCAL.get();
        return utcFormatter.format(millis, 0);
    }

    public static String toMemorySize(final long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        final int m = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format("%.1f%sB",
                (double) bytes / (1L << (m * 10)),
                " KMGTPE".charAt(m));
    }

    public static String toDuration(final long millis) {
        long ms = millis;
        final long days = TimeUnit.MILLISECONDS.toDays(ms);
        ms -= TimeUnit.DAYS.toMillis(days);
        final long hours = TimeUnit.MILLISECONDS.toHours(ms);
        ms -= TimeUnit.HOURS.toMillis(hours);
        final long minutes = TimeUnit.MILLISECONDS.toMinutes(ms);
        ms -= TimeUnit.MINUTES.toMillis(minutes);
        final long seconds = TimeUnit.MILLISECONDS.toSeconds(ms);
        ms -= TimeUnit.SECONDS.toMillis(seconds);
        final StringBuilder result = new StringBuilder();
        if (days > 0) {
            result.append(days).append('d');
        }
        if (hours > 0) {
            result.append(hours).append('h');
        }
        if (minutes > 0) {
            result.append(minutes).append('m');
        }
        result.append(seconds).append('s');
        result.append(ms);
        return result.toString();
    }

    public static boolean inSleep(final StackTraceElement ste) {
        return "java.lang.Thread".equals(ste.getClassName())
                && "sleep".equals(ste.getMethodName());
    }

    private Util() {
    }
}
