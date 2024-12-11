package io.github.green4j.newa.lang;

public enum Charset {
    US_ASCII("us-ascii"),
    UTF8("utf-8");

    private final String charset;

    Charset(final String charset) {
        this.charset = charset;
    }

    public String charset() {
        return charset;
    }
}
