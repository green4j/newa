package io.github.green4j.newa.rest.files;

import java.nio.file.Path;

/**
 * A {@link FileFilter} built from wildcard masks, matched against the path relative to the root:
 * <ul>
 * <li>{@code ?} - any one character, except the separator</li>
 * <li>{@code *} - any characters within one segment, the separator excluded</li>
 * <li>{@code **} - a whole segment and any number of segments, none included, so {@code **}{@code /*.png}
 *     matches {@code logo.png} as well as {@code img/icons/logo.png}</li>
 * </ul>
 * Anything else is literal, and matching is case sensitive - the file names are the file system\'s, and this
 * is not the place to guess how it compares them.
 * <p>
 * Matching walks the path once per mask and allocates nothing, so a root may carry a handful of them without
 * the request paying for it.
 */
public final class PathMask implements FileFilter {
    private static final String ANY_SEGMENTS = "**";

    /**
     * @param masks any one of which lets a file through
     * @return the filter
     */
    public static FileFilter including(final String... masks) {
        return new AnyOf(compile(masks), true);
    }

    /**
     * @param masks any one of which keeps a file out
     * @return the filter
     */
    public static FileFilter excluding(final String... masks) {
        return new AnyOf(compile(masks), false);
    }

    private static PathMask[] compile(final String[] masks) {
        if (masks.length == 0) {
            throw new IllegalArgumentException("At least one mask is required");
        }
        final PathMask[] result = new PathMask[masks.length];
        for (int i = 0; i < masks.length; i++) {
            result[i] = new PathMask(masks[i]);
        }
        return result;
    }

    private final String mask;
    private final String[] segments;

    /**
     * @param mask to match paths against
     */
    public PathMask(final String mask) {
        if (mask.isEmpty()) {
            throw new IllegalArgumentException("A mask cannot be empty");
        }
        this.mask = mask;
        this.segments = split(mask);
    }

    @Override
    public boolean accepts(final Path file,
                           final CharSequence relativePath) {
        return matches(relativePath);
    }

    /**
     * @param path relative to the root, {@code /}-separated
     * @return whether this mask matches it
     */
    public boolean matches(final CharSequence path) {
        final int length = path.length();

        int segment = 0;        // which segment of the mask is being matched
        int at = 0;             // where the path segment being matched starts
        int anyFrom = -1;       // the segment of the mask right after the last '**' seen
        int anyAt = -1;         // where that '**' started matching

        while (at < length) {
            if (segment < segments.length && ANY_SEGMENTS.equals(segments[segment])) {
                // it matches no segments at all first, and swallows one more every time what follows fails
                segment++;
                anyFrom = segment;
                anyAt = at;
                continue;
            }

            int end = at;
            while (end < length && path.charAt(end) != '/') {
                end++;
            }

            if (segment < segments.length && matches(segments[segment], path, at, end)) {
                segment++;
                at = end + 1; // past the separator, or past the end of the path
                continue;
            }

            if (anyFrom < 0) {
                return false;
            }
            if (segment >= segments.length && anyFrom == segments.length) {
                return true; // the mask ended with a '**', and the rest of the path is what it is for
            }

            int next = anyAt;
            while (next < length && path.charAt(next) != '/') {
                next++;
            }
            if (next >= length) {
                return false; // there is no further segment for the '**' to take
            }
            anyAt = next + 1;
            at = anyAt;
            segment = anyFrom;
        }

        while (segment < segments.length && ANY_SEGMENTS.equals(segments[segment])) {
            segment++; // a trailing '**' is allowed to have matched nothing
        }
        return segment == segments.length;
    }

    /**
     * Matches one segment of the mask against one segment of the path, {@code *} and {@code ?} included.
     * A {@code *} takes as much as it can and gives a character back whenever the rest of the segment does
     * not fit, which is all the backtracking a single segment can need.
     *
     * @param pattern segment of the mask
     * @param path being matched
     * @param from index of the first character of the path segment
     * @param to index past its last character
     * @return whether they match
     */
    private static boolean matches(final String pattern,
                                   final CharSequence path,
                                   final int from,
                                   final int to) {
        final int patternLength = pattern.length();

        int at = 0;
        int position = from;
        int starAt = -1;
        int starPosition = -1;

        while (position < to) {
            if (at < patternLength) {
                final char expected = pattern.charAt(at);
                if (expected == '*') {
                    at++;
                    starAt = at;
                    starPosition = position;
                    continue;
                }
                if (expected == '?' || expected == path.charAt(position)) {
                    at++;
                    position++;
                    continue;
                }
            }
            if (starAt < 0) {
                return false;
            }
            starPosition++;
            position = starPosition;
            at = starAt;
        }

        while (at < patternLength && pattern.charAt(at) == '*') {
            at++;
        }
        return at == patternLength;
    }

    private static String[] split(final String mask) {
        int count = 1;
        for (int i = 0; i < mask.length(); i++) {
            if (mask.charAt(i) == '/') {
                count++;
            }
        }
        final String[] result = new String[count];
        int index = 0;
        int from = 0;
        for (int i = 0; i <= mask.length(); i++) {
            if (i == mask.length() || mask.charAt(i) == '/') {
                result[index++] = mask.substring(from, i);
                from = i + 1;
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return mask;
    }

    private static final class AnyOf implements FileFilter {
        private final PathMask[] masks;
        private final boolean matched;

        private AnyOf(final PathMask[] masks,
                      final boolean matched) {
            this.masks = masks;
            this.matched = matched;
        }

        @Override
        public boolean accepts(final Path file,
                               final CharSequence relativePath) {
            for (int i = 0; i < masks.length; i++) {
                if (masks[i].matches(relativePath)) {
                    return matched;
                }
            }
            return !matched;
        }

        @Override
        public String toString() {
            final StringBuilder result = new StringBuilder(matched ? "including [" : "excluding [");
            for (int i = 0; i < masks.length; i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(masks[i]);
            }
            return result.append(']').toString();
        }
    }
}
