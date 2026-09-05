/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import java.nio.file.Path;

/**
 * Decides whether a file under a served root may be answered with at all - any rule about the file, not only
 * about how it was named. It is given both: the path relative to the root - {@code img/logo.png}, never a
 * leading slash and never a {@code ..} - already percent-decoded and always separated by {@code /}, whatever
 * the file system separates by, and the file itself, as the file system knows it, with every link on the way
 * to it already followed. So a rule may be about the name, or about the size, the owner, the age, or
 * anything else a {@link java.nio.file.Path} can be asked.
 * <p>
 * A root with no filters serves everything under it, which is rarely what a root holding more than the
 * downloads is meant to do. {@link PathMask} is the ready one:
 * <pre>{@code
 * FileSet.builder()
 *         .serve("/files", root, PathMask.including("img/**", "*.css"))
 *         .build();
 * }</pre>
 * A root takes one filter, or none at all; two rules become one with {@link #and(FileFilter)}. A file which
 * does not get past is answered exactly as a missing one is, so that asking cannot tell the two apart.
 * <p>
 * Called on event loop threads, once per request, so it must not block, and must be safe to call from several
 * at once.
 */
@FunctionalInterface
public interface FileFilter {
    /**
     * @param file the request resolved to, the real path of it
     * @param relativePath of the file within its root, {@code /}-separated and decoded
     * @return true to let it be served
     */
    boolean accepts(Path file, CharSequence relativePath);

    /**
     * Two rules as one, the second asked only when the first let the file through. A root takes one filter,
     * and this is how it takes more than one:
     * <pre>{@code
     * .serve("/files", root, PathMask.including("img/**").and(PathMask.excluding("internal/**")))
     * }</pre>
     *
     * @param next to ask as well, or null to leave this one as it is
     * @return the two of them, and-ed
     */
    default FileFilter and(final FileFilter next) {
        if (next == null) {
            return this;
        }
        return (file, relativePath) -> accepts(file, relativePath)
                && next.accepts(file, relativePath);
    }
}
