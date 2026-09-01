package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.collections.CharSequenceToObjectMap;
import io.netty.util.AsciiString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a file\'s name says its content type is. The table is built once with the {@link FileSet}; a view of it
 * belongs to one handler, which is one channel, which is one event loop thread - the lookup writes into a
 * buffer of its own and could not be shared.
 */
final class ContentTypes {
    static final AsciiString DEFAULT = AsciiString.cached("application/octet-stream");

    /**
     * @return the built-in table, to be added to by {@link FileSet.Builder#contentType}
     */
    static Map<String, AsciiString> defaults() {
        final Map<String, AsciiString> result = new LinkedHashMap<>();
        put(result, "html", "text/html");
        put(result, "htm", "text/html");
        put(result, "css", "text/css");
        put(result, "js", "text/javascript");
        put(result, "mjs", "text/javascript");
        put(result, "json", "application/json");
        put(result, "txt", "text/plain");
        put(result, "md", "text/markdown");
        put(result, "csv", "text/csv");
        put(result, "xml", "application/xml");
        put(result, "svg", "image/svg+xml");
        put(result, "png", "image/png");
        put(result, "jpg", "image/jpeg");
        put(result, "jpeg", "image/jpeg");
        put(result, "gif", "image/gif");
        put(result, "webp", "image/webp");
        put(result, "ico", "image/x-icon");
        put(result, "pdf", "application/pdf");
        put(result, "zip", "application/zip");
        put(result, "gz", "application/gzip");
        put(result, "wasm", "application/wasm");
        put(result, "woff", "font/woff");
        put(result, "woff2", "font/woff2");
        put(result, "mp4", "video/mp4");
        put(result, "webm", "video/webm");
        put(result, "mp3", "audio/mpeg");
        return result;
    }

    private static void put(final Map<String, AsciiString> into,
                            final String extension,
                            final String contentType) {
        into.put(extension, AsciiString.cached(contentType));
    }

    private final CharSequenceToObjectMap<AsciiString> types = new CharSequenceToObjectMap<>();
    private final StringBuilder extension = new StringBuilder(8);

    ContentTypes(final Map<String, AsciiString> types) {
        this.types.putAll(types);
    }

    /**
     * Takes the extension of the last segment of {@code path} and looks it up, lowercased - the table is
     * written in lower case, and a name is not.
     *
     * @param path to read the name from
     * @param from index of the first character of the name, or of the path it ends
     * @param to index past its last character
     * @return the content type, {@code application/octet-stream} when the name says nothing
     */
    AsciiString of(final CharSequence path,
                   final int from,
                   final int to) {
        int dot = -1;
        for (int i = to - 1; i >= from; i--) {
            final char c = path.charAt(i);
            if (c == '/') {
                break;
            }
            if (c == '.') {
                dot = i;
                break;
            }
        }
        if (dot < 0 || dot + 1 >= to) {
            return DEFAULT;
        }

        extension.setLength(0);
        for (int i = dot + 1; i < to; i++) {
            extension.append(Character.toLowerCase(path.charAt(i)));
        }

        final AsciiString found = types.get(extension, 0, extension.length());
        return found != null ? found : DEFAULT;
    }
}
