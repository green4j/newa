package io.github.green4j.newa.rest.handles;


import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.text.LineAppendable;
import io.github.green4j.newa.text.UtcToIso8601Formatter;

import java.util.concurrent.TimeUnit;

abstract class Util {
    static final ThreadLocal<UtcToIso8601Formatter> UTC_TO_ISO_8601_FORMATTER_THREAD_LOCAL =
            ThreadLocal.withInitial(UtcToIso8601Formatter::new);

    static CharSequence formatUtcToIso8601(final long millis) {
        final UtcToIso8601Formatter utcFormatter =
                UTC_TO_ISO_8601_FORMATTER_THREAD_LOCAL.get();
        return utcFormatter.format(millis, 0);
    }

    static String toMemorySize(final long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        final int m = (63 - Long.numberOfLeadingZeros(bytes)) / 10;
        return String.format("%.1f%sB",
                (double) bytes / (1L << (m * 10)),
                " KMGTPE".charAt(m));
    }

    static String toDuration(final long millis) {
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

    static boolean inSleep(final StackTraceElement ste) {
        return "java.lang.Thread".equals(ste.getClassName())
                && "sleep".equals(ste.getMethodName());
    }

    static boolean objectMemberNotNullable(final JsonGenerator to,
                                           final String name,
                                           final String value) {
        if (value == null) {
            return false;
        }
        to.objectMember(name);
        to.stringValue(value, true);
        return true;
    }

    static boolean appendlnNotNullable(final LineAppendable to,
                                       final String name,
                                       final String value) {
        if (value == null) {
            return false;
        }
        to.append(name);
        to.append(": ");
        to.appendln(value);
        return true;
    }

    private Util() {
    }
}
