package io.github.green4j.newa.json;

import io.github.green4j.jelly.JsonParser;
import io.github.green4j.jelly.JsonParserListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public class JsonParserUtil {
    private static final ThreadLocal<AsciiByteCharSequence> PARSE_ASCII_BUFFER =
            ThreadLocal.withInitial(() -> new AsciiByteCharSequence(4096));

    public static <T extends JsonParserListener> T parseAsciiAndCloseNoX(
            final InputStream json, final T listener) {
        try {
            parseAsciiAndClose(json, listener);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return listener;
    }

    public static <T extends JsonParserListener> T parseAsciiNoX(final InputStream json, final T listener) {
        try {
            parseAscii(json, listener);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return listener;
    }

    public static <T extends JsonParserListener> T parseAsciiAndClose(
            final InputStream stream, final T listener) throws IOException {
        try {
            return parseAscii(stream, listener);
        } finally {
            stream.close();
        }
    }

    public static <T extends JsonParserListener> T parseAscii(
            final InputStream stream, final T listener) throws IOException {
        final AsciiByteCharSequence buffer = PARSE_ASCII_BUFFER.get();
        final JsonParser jsonParser = new JsonParser().setListener(listener);
        int len;
        while ((len = stream.read(buffer.bytes(), 0, buffer.bytes().length)) != -1) {
            buffer.setLength(len);
            jsonParser.parse(buffer);
        }
        jsonParser.eoj();
        return listener;
    }
}