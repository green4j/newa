package io.github.green4j.newa.rest.files;

import io.netty.util.AsciiString;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a {@link FileServerHandler} is allowed to answer with: the paths it owns, the roots or files behind
 * them, and the filters a file has to get past.
 * <p>
 * Built once, when the server is assembled, and shared by every channel - it holds nothing which changes:
 * <pre>{@code
 * FileSet files = FileSet.builder()
 *         .serve("/files", Paths.get("/var/www"),
 *                 PathMask.excluding("internal/**"))     // a whole tree, less what the filter keeps out
 *         .file("/download/report.pdf", Paths.get("/var/data/report.pdf"))  // one file, named here
 *         .index("index.html")
 *         .build();
 * }</pre>
 * A request is matched against the paths in one walk of it, longest first: with both {@code /files} and
 * {@code /files/img} served, {@code /files/img/logo.png} is answered from the second. What is left of the
 * path after the one that matched is the file within the root - {@code /download/report.pdf} names a file
 * rather than a root, so nothing may be left of it at all.
 * <p>
 * Paths nothing here owns are not this handler\'s to answer: they go on down the pipeline untouched.
 */
public final class FileSet {
    /**
     * @return a builder of one
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Collects the paths to serve. Not thread safe, and not meant to outlive the assembling of the server.
     */
    public static final class Builder {
        private final Node root = new Node();
        private final List<Mapping> mappings = new ArrayList<>();
        private final Map<String, AsciiString> contentTypes = ContentTypes.defaults();

        private String index;

        private Builder() {
        }

        /**
         * Serves a whole tree: everything under {@code root}.
         *
         * @param prefix of the request path, {@code /files}
         * @param root the rest of the path is resolved against
         * @return this
         */
        public Builder serve(final String prefix,
                             final Path root) {
            return serve(prefix, root, null);
        }

        /**
         * Serves a whole tree, less what the filter keeps out.
         *
         * @param prefix of the request path, {@code /files}
         * @param root the rest of the path is resolved against
         * @param filter a file has to get past, or null to serve the whole tree. Several rules become one
         *               with {@link FileFilter#and(FileFilter)}
         * @return this
         */
        public Builder serve(final String prefix,
                             final Path root,
                             final FileFilter filter) {
            return add(prefix, new Mapping(realPathOf(root), false, null, filter));
        }

        /**
         * Serves one file, named here rather than by the request.
         *
         * @param path of the request, which has to be exactly this
         * @param file to answer it with
         * @return this
         */
        public Builder file(final String path,
                            final Path file) {
            return file(path, file, null);
        }

        /**
         * The same, with the content type said rather than taken from the name.
         *
         * @param path of the request, which has to be exactly this
         * @param file to answer it with
         * @param contentType to answer with, or null to read the name for it
         * @return this
         */
        public Builder file(final String path,
                            final Path file,
                            final AsciiString contentType) {
            return add(path, new Mapping(file.toAbsolutePath().normalize(), true, contentType, null));
        }

        /**
         * Adds to the built-in table of content types, or replaces what it says.
         *
         * @param extension without the dot, lower case
         * @param contentType to answer a file with that extension with
         * @return this
         */
        public Builder contentType(final String extension,
                                   final AsciiString contentType) {
            contentTypes.put(extension, contentType);
            return this;
        }

        /**
         * The file to answer a request for a directory with. Without one, a directory is answered exactly as
         * a missing file is.
         *
         * @param fileName within the directory, {@code index.html}
         * @return this
         */
        public Builder index(final String fileName) {
            if (fileName.indexOf('/') > -1) {
                throw new IllegalArgumentException("An index is a file name, not a path: " + fileName);
            }
            index = fileName;
            return this;
        }

        /**
         * @return the set, which may be handed to as many handlers as there are channels
         */
        public FileSet build() {
            if (mappings.isEmpty()) {
                throw new IllegalStateException("A file set with nothing in it serves nothing");
            }
            return new FileSet(root, mappings, contentTypes, index);
        }

        private Builder add(final String path,
                            final Mapping mapping) {
            Node node = root;
            int at = 0;
            while (at < path.length()) {
                while (at < path.length() && path.charAt(at) == '/') {
                    at++;
                }
                if (at >= path.length()) {
                    break;
                }
                int end = at;
                while (end < path.length() && path.charAt(end) != '/') {
                    end++;
                }
                final String segment = path.substring(at, end);
                node = node.children.computeIfAbsent(segment, name -> new Node());
                at = end;
            }
            if (node.mapping > -1) {
                throw new IllegalArgumentException("Already served: " + path);
            }
            node.mapping = mappings.size();
            mappings.add(mapping);
            return this;
        }

        private static Path realPathOf(final Path root) {
            try {
                return root.toRealPath();
            } catch (final IOException notThereYet) {
                // a root which is not there when the server is assembled is one every request will miss on,
                // which is the answer anyway - and it may well be there by the time one arrives
                return root.toAbsolutePath().normalize();
            }
        }
    }

    private static final Path[] NOTHING_NESTED = new Path[0];

    private static final class Node {
        private final TreeMap<String, Node> children = new TreeMap<>();
        private int mapping = -1;
        private int state;
    }

    /**
     * One served path and what is behind it.
     */
    static final class Mapping {
        private final Path target;
        private final boolean exact;
        private final AsciiString contentType;
        private final FileFilter filter;

        // the roots of the mappings which serve part of this one, so a path which belongs to a more specific
        // mapping is not answered through this one
        private Path[] nested = NOTHING_NESTED;

        private Mapping(final Path target,
                        final boolean exact,
                        final AsciiString contentType,
                        final FileFilter filter) {
            this.target = target;
            this.exact = exact;
            this.contentType = contentType;
            this.filter = filter;
        }

        /**
         * @return the root the rest of the path is resolved against, or the file itself when {@link #exact()}
         */
        Path target() {
            return target;
        }

        /**
         * @return whether the path named a file rather than a root
         */
        boolean exact() {
            return exact;
        }

        /**
         * @return the content type to answer with, or null to read the file name for it
         */
        AsciiString contentType() {
            return contentType;
        }

        /**
         * @return whether anything is asked before a file of this root is served
         */
        boolean filtered() {
            return filter != null;
        }

        /**
         * @param file the request resolved to
         * @param relativePath of the file within the root
         * @return whether the filter of this root, if it has one, lets it through
         */
        boolean accepts(final Path file,
                        final CharSequence relativePath) {
            return filter == null || filter.accepts(file, relativePath);
        }

        /**
         * A file which another mapping serves has to be asked for by that mapping's path, or the rules of
         * this one would answer for it - and on a file system which does not tell {@code img} from
         * {@code IMG}, which mapping a request lands on is not decided by the request alone.
         *
         * @param file resolved, as the file system knows it
         * @return whether a more specific mapping owns it
         */
        boolean nestedOwns(final Path file) {
            for (int i = 0; i < nested.length; i++) {
                if (file.startsWith(nested[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * What a path matched, and what is left of it. One of these belongs to one handler and is written over by
     * every request it takes, so nothing is allocated to answer the question.
     */
    static final class Match {
        private CharSequence path;
        private Mapping mapping;
        private int tailStart;
        private int tailEnd;

        private void set(final CharSequence path,
                         final Mapping mapping,
                         final int tailStart,
                         final int tailEnd) {
            this.path = path;
            this.mapping = mapping;
            this.tailStart = tailStart;
            this.tailEnd = tailEnd;
        }

        Mapping mapping() {
            return mapping;
        }

        int tailStart() {
            return tailStart;
        }

        int tailEnd() {
            return tailEnd;
        }

        CharSequence path() {
            return path;
        }

        /**
         * @return whether the matched path had nothing after the prefix - which is all an exactly named file
         *         may be asked for, and what asks a root for its index
         */
        boolean tailIsEmpty() {
            for (int i = tailStart; i < tailEnd; i++) {
                if (path.charAt(i) != '/') {
                    return false;
                }
            }
            return true;
        }
    }

    // jumps of the segment state machine, ordered by state and then by segment, so the jumps of one state are
    // found with a binary search and then walked
    private final int[] jumpFrom;
    private final String[] jumpSegment;
    private final int[] jumpTo;
    private final int[] jumpMapping;

    private final Mapping[] mappings;
    private final Mapping rootMapping;
    private final Map<String, AsciiString> contentTypes;
    private final String index;

    private FileSet(final Node root,
                    final List<Mapping> mappings,
                    final Map<String, AsciiString> contentTypes,
                    final String index) {
        this.mappings = mappings.toArray(new Mapping[0]);
        linkNested(this.mappings);
        this.rootMapping = root.mapping > -1 ? this.mappings[root.mapping] : null;
        this.contentTypes = contentTypes;
        this.index = index;

        final int jumps = count(root) - 1;
        jumpFrom = new int[jumps];
        jumpSegment = new String[jumps];
        jumpTo = new int[jumps];
        jumpMapping = new int[jumps];

        final List<Node> queue = new ArrayList<>();
        root.state = 0;
        queue.add(root);

        int states = 1;
        int written = 0;
        for (int i = 0; i < queue.size(); i++) { // states are handed out in the order they are queued in, so
            final Node node = queue.get(i);      // the jumps come out ordered by the state they leave
            for (final Map.Entry<String, Node> child : node.children.entrySet()) {
                final Node to = child.getValue();
                to.state = states++;
                jumpFrom[written] = node.state;
                jumpSegment[written] = child.getKey();
                jumpTo[written] = to.state;
                jumpMapping[written] = to.mapping;
                written++;
                queue.add(to);
            }
        }
    }

    /**
     * Works out, once, which mappings serve part of which other ones. A tree served by two mappings with
     * different rules is the one place where which of them answers has to be decided by more than the text of
     * the request.
     *
     * @param mappings of this set
     */
    private static void linkNested(final Mapping[] mappings) {
        for (int i = 0; i < mappings.length; i++) {
            final Mapping mapping = mappings[i];
            if (mapping.exact()) {
                continue;
            }
            final List<Path> nested = new ArrayList<>();
            for (int j = 0; j < mappings.length; j++) {
                final Mapping other = mappings[j];
                if (i == j || other.exact()) {
                    continue;
                }
                if (other.target().startsWith(mapping.target())
                        && !other.target().equals(mapping.target())) {
                    nested.add(other.target());
                }
            }
            if (!nested.isEmpty()) {
                mapping.nested = nested.toArray(new Path[0]);
            }
        }
    }

    private static int count(final Node node) {
        int result = 1;
        for (final Map.Entry<String, Node> child : node.children.entrySet()) {
            result += count(child.getValue());
        }
        return result;
    }

    /**
     * Walks {@code path} once, taking the longest served prefix of it: every step which lands on a served
     * path is remembered, and the last one remembered is the answer, so nothing is compared twice and nothing
     * is copied out of the path to compare at all.
     *
     * @param path the request came in on, query string and all
     * @param into to write what was matched into
     * @return whether anything here owns the path
     */
    boolean match(final CharSequence path,
                  final Match into) {
        int end = path.length();
        for (int i = 0; i < end; i++) {
            final char c = path.charAt(i);
            if (c == '?' || c == '#') {
                end = i; // the query is not part of what is being asked for
                break;
            }
        }

        Mapping best = rootMapping;
        int tail = 0;
        int state = 0;
        int at = 0;

        while (at < end) {
            while (at < end && path.charAt(at) == '/') {
                at++;
            }
            if (at >= end) {
                break;
            }
            int segmentEnd = at;
            while (segmentEnd < end && path.charAt(segmentEnd) != '/') {
                segmentEnd++;
            }

            final int jump = findJump(state, path, at, segmentEnd);
            if (jump < 0) {
                break; // whatever is served was served by a shorter prefix, and the rest is the file
            }

            state = jumpTo[jump];
            if (jumpMapping[jump] > -1) {
                best = mappings[jumpMapping[jump]];
                tail = segmentEnd;
            }
            at = segmentEnd;
        }

        if (best == null) {
            return false;
        }
        into.set(path, best, tail, end);
        return true;
    }

    private int findJump(final int state,
                         final CharSequence path,
                         final int from,
                         final int to) {
        int low = 0;
        int high = jumpFrom.length - 1;
        int found = -1;
        while (low <= high) {
            final int middle = (low + high) >>> 1;
            final int value = jumpFrom[middle];
            if (value < state) {
                low = middle + 1;
            } else if (value > state) {
                high = middle - 1;
            } else {
                found = middle;
                high = middle - 1; // there may be an earlier jump of the same state
            }
        }
        if (found < 0) {
            return -1;
        }
        for (int i = found; i < jumpFrom.length && jumpFrom[i] == state; i++) {
            if (segmentEquals(jumpSegment[i], path, from, to)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean segmentEquals(final String segment,
                                         final CharSequence path,
                                         final int from,
                                         final int to) {
        if (segment.length() != to - from) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) != path.charAt(from + i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the file a directory is answered with, or null when a directory is not answered
     */
    String index() {
        return index;
    }

    /**
     * @return the extension table, to be copied by each handler into a lookup of its own
     */
    Map<String, AsciiString> contentTypes() {
        return contentTypes;
    }
}
