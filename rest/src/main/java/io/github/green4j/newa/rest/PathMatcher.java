package io.github.green4j.newa.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class PathMatcher<T> {
    private static final int INITIAL_STATE = 0;
    private static final int FOLDER_STATE = 1;
    private static final int PARAM_STATE = 2;

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {
        private static class Jump implements Comparable<Jump> {
            private int from = INITIAL_STATE;
            private int to = INITIAL_STATE;
            private String segment;
            private boolean isParameter;

            private int routeIndex = -1;

            private boolean isLeaf() {
                return routeIndex > -1;
            }

            @Override
            public Jump clone() {
                final Jump result = new Jump();
                result.from = this.from;
                result.to = this.to;
                result.segment = this.segment;
                result.isParameter = this.isParameter;
                result.routeIndex = this.routeIndex;
                return result;
            }

            @Override
            public int compareTo(final Jump o) {
                final int stateCmp = Integer.compare(from, o.from);
                if (stateCmp != 0) {
                    return stateCmp;
                }
                if (isParameter || o.isParameter) { // a parameter is equal to any other segment, see equals()
                    return 0;
                }
                return CharSequence.compare(segment, o.segment);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean equals(final Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                final Jump jump = (Jump) o; // unchecked
                return from == jump.from
                        && (isParameter || jump.isParameter ? true : segment.equals(jump.segment));
            }

            @Override
            public int hashCode() {
                return Objects.hash(from, segment);
            }

            @Override
            public String toString() {
                return "[" + from + "->" + (isParameter ? "{" + segment + '}' : segment) + "->" + to + ']'
                        + (isLeaf() ? " (route " + routeIndex + ')' : "");
            }
        }

        private final TreeSet<Jump> jumps = new TreeSet<>();
        private final List<T> handlers = new ArrayList<>();

        private int maxNumberOfParamsInPath = 0;
        private int currentState = INITIAL_STATE;

        private Builder() {
        }

        public String[] withPath(final String pathExpression, final T handler) {
            final Jump jump = new Jump();
            final var addedJump = new Object() {
                Jump jump;
            };
            final var lastFound = new Object() {
                Jump jump;
            };
            final List<String> parameters = new ArrayList<>();

            parsePathExpression(pathExpression, (name, isParameter) -> {
                jump.segment = name;
                jump.isParameter = isParameter;

                if (isParameter) {
                    parameters.add(jump.segment);
                }

                final SortedSet<Jump> contains = jumps.subSet(jump, true, jump, true);
                if (contains.isEmpty()) {
                    jump.to = ++currentState;
                    final Jump jumpToStore = jump.clone();
                    addedJump.jump = jumpToStore;
                    jumps.add(jumpToStore);
                    jump.from = jump.to;
                } else {
                    if (contains.size() > 1) {
                        throw new IllegalArgumentException("Ambiguous pathExpression: " + pathExpression
                                + " because of the parameter: " + jump.segment);
                    }
                    lastFound.jump = contains.first();
                    if (lastFound.jump.isLeaf()) {
                        throw new IllegalArgumentException("Ambiguous pathExpression: " + pathExpression
                                + " comparing to a previous one");
                    }
                    jump.from = lastFound.jump.to;
                }
            });

            if (addedJump.jump == null) {
                if (lastFound.jump == null) {
                    throw new IllegalArgumentException("Empty pathExpression: " + pathExpression);
                }
                addedJump.jump = lastFound.jump;
            }
            addedJump.jump.routeIndex = handlers.size();
            handlers.add(handler);

            if (parameters.size() > maxNumberOfParamsInPath) {
                maxNumberOfParamsInPath = parameters.size();
            }
            return parameters.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return jumps.stream()
                    .map(Objects::toString)
                    .collect(Collectors.joining("\n"));
        }

        public PathMatcher<T> build() {
            return new PathMatcher<>(jumps, handlers, maxNumberOfParamsInPath);
        }
    }

    public final class Result implements PathParameters {

        private Result() {
        }

        public T handler() {
            return handler;
        }

        @Override
        public int numberOfParameters() {
            return numberOfParameters;
        }

        @Override
        public String parameterName(final int idx) {
            return parameterNames[idx];
        }

        @Override
        public CharSequence parameterValue(final int idx) {
            return parameterValues[idx];
        }

        @Override
        public String toString() {
            final StringBuilder result = new StringBuilder();
            result.append("handler: ").append(handler());
            result.append(", parameters: [");
            for (int i = 0; i < numberOfParameters(); i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(parameterName(i))
                        .append("=")
                        .append(parameterValue(i));
            }
            result.append(']');
            return result.toString();
        }
    }

    private final int[] stateJumps; // stores a table of state jumps 'from' (odd index) -> 'to' (even index).
    // For leaf nodes 'to' is -(index of a handler in the 'handlers' array + 1)
    private final String[] folderSegments;
    private final String[] parameterSegments;
    private final T[] handlers;
    private final String[] parameterNames;
    private final StringBuilder[] parameterValues;
    private final Result result = new Result();

    private T handler;
    private int numberOfParameters;
    private int currentState;

    private int findFirstFromIndex(final int from) {
        int low = 0;
        int high = (stateJumps.length >>> 1) - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            final int midVal = stateJumps[mid << 1];
            final int cmp = Integer.compare(midVal, from);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                // found, go to start
                while (mid > 0) {
                    final int prevVal = stateJumps[(mid - 1) << 1];
                    if (prevVal != midVal) {
                        break;
                    }
                    mid--;
                }
                return mid;
            }
        }
        return -1;
    }

    private final PathParsingListener pathMatchingListener = new PathParsingListener() {
        private int currentFromStateIndex;

        @Override
        public Appendable onSegmentStarted() {
            if (isIndex(currentState)) { // last segment was a leaf, but parsing continues
                currentState = INITIAL_STATE;
                return null;
            }
            currentFromStateIndex = findFirstFromIndex(currentState);
            if (currentFromStateIndex < 0) {
                return null;
            }
            final StringBuilder bufferToParseSegment = parameterValues[numberOfParameters];
            bufferToParseSegment.setLength(0);
            return bufferToParseSegment;
        }

        @Override
        public boolean onSegmentFinished() {
            final StringBuilder bufferToParseSegment = parameterValues[numberOfParameters];
            for (int i = currentFromStateIndex; i < folderSegments.length; i++) {
                if (stateJumps[i << 1] != currentState) {
                    break;
                }

                final String jumpSegment = folderSegments[i];

                if (jumpSegment == null) { // a parameter
                    parameterNames[numberOfParameters] = parameterSegments[i];
                    assert parameterNames[numberOfParameters] != null;
                    numberOfParameters++;
                    currentState = stateJumps[(i << 1) + 1];
                    return true;
                }

                if (jumpSegment.contentEquals(bufferToParseSegment)) { // a folder
                    currentState = stateJumps[(i << 1) + 1];
                    return true;
                }
            }
            return false;
        }
    };

    @SuppressWarnings("unchecked")
    private PathMatcher(final TreeSet<Builder.Jump> jumps,
                        final List<T> handlers,
                        final int maxNumberOfParametersInPath) {
        stateJumps = new int[jumps.size() << 1];
        folderSegments = new String[jumps.size()];
        parameterSegments = new String[jumps.size()];

        for (int i = 0, len = jumps.size(); i < len; i++) {
            final Builder.Jump jump = jumps.pollFirst();
            stateJumps[i << 1] = jump.from;
            stateJumps[(i << 1) + 1] = jump.isLeaf() ? indexToTo(jump.routeIndex) : jump.to;

            if (jump.isParameter) {
                folderSegments[i] = null;
                parameterSegments[i] = jump.segment;
            } else {
                folderSegments[i] = jump.segment;
            }
        }

        this.handlers = (T[]) handlers.toArray(new Object[0]); // unchecked

        parameterNames = new String[maxNumberOfParametersInPath];
        parameterValues = new StringBuilder[maxNumberOfParametersInPath + 1]; // to parse folder segment as well
        for (int i = 0; i < parameterValues.length; i++) {
            parameterValues[i] = new StringBuilder();
        }
    }

    public Result match(final CharSequence path) {
        handler = null;
        numberOfParameters = 0;
        currentState = INITIAL_STATE;

        parsePath(path, pathMatchingListener);

        if (!isIndex(currentState)) {
            return null;
        }

        final int index = toToIndex(currentState);
        handler = handlers[index];
        return result;
    }

    private static int indexToTo(final int routeIndex) {
        return -(routeIndex + 1); // from -1
    }

    private static boolean isIndex(final int to) {
        return to < 0;
    }

    private static int toToIndex(final int to) {
        assert to < 0;
        return -to - 1;
    }

    private interface PathExpressionParsingListener {
        void onSegment(String name, boolean isParameter);
    }

    private static void parsePathExpression(final String path,
                                            final PathExpressionParsingListener listener) {
        final StringBuilder name = new StringBuilder();
        int state = INITIAL_STATE;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);

            switch (state) {
                case INITIAL_STATE:
                    switch (c) {
                        case '/':
                            break;
                        case '{':
                            name.setLength(0);
                            state = PARAM_STATE;
                            break;
                        default:
                            if (isFolderNameChar(c)) {
                                name.setLength(0);
                                name.append(c);
                                state = FOLDER_STATE;
                                break;
                            }
                            fireUnexpectedCharException(i, c);
                    }
                    break;
                case FOLDER_STATE:
                    switch (c) {
                        case '/':
                            listener.onSegment(name.toString(), false);
                            state = INITIAL_STATE;
                            break;
                        default:
                            if (isFolderNameChar(c)) {
                                name.append(c);
                                break;
                            }
                            fireUnexpectedCharException(i, c);
                    }
                    break;
                case PARAM_STATE:
                    switch (c) {
                        case '}':
                            listener.onSegment(name.toString(), true);
                            state = INITIAL_STATE;
                            break;
                        default:
                            if (isFolderNameChar(c)) {
                                name.append(c);
                                break;
                            }
                            fireUnexpectedCharException(i, c);
                    }
                    break;
            }
        }
        // check and process EOL
        switch (state) {
            case FOLDER_STATE:
                listener.onSegment(name.toString(), false);
                break;
            case PARAM_STATE:
                throw new IllegalArgumentException("Unexpected end of the parameter '" + name + '\'');
            default:
                break;
        }
    }

    private static void fireUnexpectedCharException(final int i, final char c) {
        throw new IllegalArgumentException("Unexpected char '" + c + "' at pos: " + i);
    }

    // TODO: add sub-delims and ':', '@'?
    private static boolean isFolderNameChar(final char c) {
        if (Character.isLetterOrDigit(c)) {
            return true;
        }
        switch (c) {
            case '-':
            case '.':
            case '_':
            case '~':
                return true;
            default:
                break;
        }
        return false;
    }

    private interface PathParsingListener {

        Appendable onSegmentStarted();

        boolean onSegmentFinished();

    }

    private static void parsePath(final CharSequence path, final PathParsingListener listener) {
        int state = INITIAL_STATE;
        Appendable currentSegmentAppendable = null;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);

            switch (state) {
                case INITIAL_STATE:
                    if (c == '/') {
                        break;
                    }
                    state = FOLDER_STATE;
                    if ((currentSegmentAppendable = listener.onSegmentStarted()) == null) {
                        return;
                    }
                    try {
                        currentSegmentAppendable.append(c);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    break;
                case FOLDER_STATE:
                    if (c == '/') {
                        state = INITIAL_STATE;
                        if (!listener.onSegmentFinished()) {
                            return;
                        }
                        break;
                    }
                    try {
                        currentSegmentAppendable.append(c);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
        if (state == FOLDER_STATE) {
            listener.onSegmentFinished();
        }
    }
}