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
    private static final int STATIC_STATE = 1;
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

                if (!isParameter) { // this is a static
                    if (!o.isParameter) { // o is a static as well
                        return CharSequence.compare(segment, o.segment); // statics are equals by their names
                    }
                    return -1; // a static if preferred over a parameter (the static should be earlier than a parameter)
                }

                // this is a parameter
                if (o.isParameter) { // o also is parameter
                    return 0; // two parameters with any names are equal
                }
                return 1; // a static if preferred (the parameter should be after than a static)
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

        public String[] withPath(final String pathExpression,
                                 final T handler) {
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
                        throw new IllegalStateException();
                    }
                    lastFound.jump = contains.first();
                    if (jump.isParameter) {
                        if (!lastFound.jump.segment.equals(jump.segment)) {
                            throw new IllegalArgumentException("Ambiguous parameter " + jump.segment
                                    + " in the path expression: " + pathExpression);
                        }
                    }
                    jump.from = lastFound.jump.to;
                }
            });

            if (addedJump.jump == null) {
                if (lastFound.jump == null) {
                    throw new IllegalArgumentException("Empty path expression: " + pathExpression);
                }
                addedJump.jump = lastFound.jump;
            }

            // check if there is a jump fo a final state/handler with the same segment
            // already (current new jump is a duplication)
            if (jumps.stream().filter(j ->
                    j.from == addedJump.jump.from
                            && j.isParameter == addedJump.jump.isParameter
                            && j.segment.equals(addedJump.jump.segment)
                            && j.isLeaf()
            ).count() > 0) {
                throw new IllegalArgumentException("Ambiguous path expression: " + pathExpression);
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
        public CharSequence parameterValue(final String name) {
            for (int i = 0; i < numberOfParameters(); i++) { // linear probing is going to be OK
                if (CharSequence.compare(name, parameterName(i)) == 0) {
                    return parameterValue(i);
                }
            }
            return null;
        }

        @Override
        public CharSequence parameterValueRequired(final String name) throws BadRequestException {
            final CharSequence result = parameterValue(name);
            if (result == null) {
                throw new BadRequestException("Parameter '" + name + "' is required");
            }
            return result;
        }

        @Override
        public String parameterValueRequiredString(final String name) throws BadRequestException {
            return parameterValueRequired(name).toString();
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

    // Immutable fields
    private final int[] stateJumps; // Stores a table of state jumps "from" (odd index) -> "to" (even index).
    // For leaf nodes "to" is "-(index of a handler in the 'handlers' array + 1) << 16 | to".
    // It is required to have "to" information stored in addition to a handler's index
    // to continue matching if new segment parsed after a route has been resolved.
    private final String[] staticSegments;
    private final String[] parameterSegments;
    private final T[] handlers;

    // Mutable fields
    private final String[] parameterNames;
    private final StringBuilder[] parameterValues;

    private final Result result = new Result();

    private transient T handler;
    private transient int numberOfParameters;
    private transient int currentState;

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
            if (isRouteIndex(currentState)) { // last segment was a leaf/handler was found, but parsing continues
                currentState = toTo(currentState);
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
            for (int i = currentFromStateIndex; i < staticSegments.length; i++) {
                if (stateJumps[i << 1] != currentState) { // another "from" found
                    break;
                }

                final String jumpSegment = staticSegments[i];

                if (jumpSegment != null) { // try to match a static
                    // we have statics and one parameter ordered, all static first
                    if (jumpSegment.contentEquals(bufferToParseSegment)) {
                        currentState = stateJumps[(i << 1) + 1];
                        return true;
                    }
                } else {
                    // match a parameter
                    // there is only one parameter for a given "from" state
                    parameterNames[numberOfParameters] = parameterSegments[i];
                    assert parameterNames[numberOfParameters] != null;
                    numberOfParameters++;
                    currentState = stateJumps[(i << 1) + 1];
                    return true;
                }
            }
            return false;
        }
    };

    PathMatcher(final PathMatcher<T> from) {
        // immutable fields - copy by ref
        stateJumps = from.stateJumps;
        staticSegments = from.staticSegments;
        parameterSegments = from.parameterSegments;
        handlers = from.handlers;

        // mutable fields - new instances
        parameterNames = new String[from.parameterNames.length];
        parameterValues = new StringBuilder[from.parameterValues.length];
        for (int i = 0; i < parameterValues.length; i++) {
            parameterValues[i] = new StringBuilder();
        }
    }

    @SuppressWarnings("unchecked")
    private PathMatcher(final TreeSet<Builder.Jump> jumps,
                        final List<T> handlers,
                        final int maxNumberOfParametersInPath) {
        stateJumps = new int[jumps.size() << 1];
        staticSegments = new String[jumps.size()];
        parameterSegments = new String[jumps.size()];

        for (int i = 0, len = jumps.size(); i < len; i++) {
            final Builder.Jump jump = jumps.pollFirst();
            stateJumps[i << 1] = jump.from;
            stateJumps[(i << 1) + 1] = jump.isLeaf() ? routeIndexToTo(jump.routeIndex, jump.to) : jump.to;

            if (jump.isParameter) {
                parameterSegments[i] = jump.segment;
            } else {
                staticSegments[i] = jump.segment;
            }
        }

        this.handlers = (T[]) handlers.toArray(new Object[0]); // unchecked

        parameterNames = new String[maxNumberOfParametersInPath];
        parameterValues = new StringBuilder[maxNumberOfParametersInPath + 1]; // to parse static as well
        for (int i = 0; i < parameterValues.length; i++) {
            parameterValues[i] = new StringBuilder();
        }
    }

    public Result match(final CharSequence path) {
        handler = null;
        numberOfParameters = 0;
        currentState = INITIAL_STATE;

        parsePath(path, pathMatchingListener);

        if (!isRouteIndex(currentState)) {
            return null;
        }

        final int index = toRouteIndex(currentState);
        handler = handlers[index];
        return result;
    }

    private static int routeIndexToTo(final int routeIndex,
                                      final int to) {
        assert routeIndex > -1;
        assert to > -1;
        final int r = -(routeIndex + 1); // from -1 to present the 0 index
        return r << 16 | to;
    }

    private static boolean isRouteIndex(final int to) {
        return to < 0;
    }

    private static int toRouteIndex(final int to) {
        assert to < 0;
        return -(to >> 16) - 1;
    }

    private static int toTo(final int to) {
        assert to < 0;
        return to & 0xFFFF;
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
                            if (isStaticNameChar(c)) {
                                name.setLength(0);
                                name.append(c);
                                state = STATIC_STATE;
                                break;
                            }
                            fireUnexpectedCharException(i, c);
                    }
                    break;
                case STATIC_STATE:
                    switch (c) {
                        case '/':
                            listener.onSegment(name.toString(), false);
                            state = INITIAL_STATE;
                            break;
                        default:
                            if (isStaticNameChar(c)) {
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
                            if (isStaticNameChar(c)) {
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
            case STATIC_STATE:
                listener.onSegment(name.toString(), false);
                break;
            case PARAM_STATE:
                throw new IllegalArgumentException("Unexpected end of the parameter '" + name + '\'');
            default:
                break;
        }
    }

    private static void fireUnexpectedCharException(final int i,
                                                    final char c) {
        throw new IllegalArgumentException("Unexpected char '" + c + "' at pos: " + i);
    }

    // TODO: add sub-delims and ':', '@'?
    private static boolean isStaticNameChar(final char c) {
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

    private static void parsePath(final CharSequence path,
                                  final PathParsingListener listener) {
        int state = INITIAL_STATE;
        Appendable currentSegmentAppendable = null;
        for (int i = 0; i < path.length(); i++) {
            final char c = path.charAt(i);

            switch (state) {
                case INITIAL_STATE:
                    if (c == '/') {
                        break;
                    }
                    state = STATIC_STATE;
                    if ((currentSegmentAppendable = listener.onSegmentStarted()) == null) {
                        return;
                    }
                    try {
                        currentSegmentAppendable.append(c);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    break;
                case STATIC_STATE:
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
        if (state == STATIC_STATE) {
            listener.onSegmentFinished();
        }
    }
}