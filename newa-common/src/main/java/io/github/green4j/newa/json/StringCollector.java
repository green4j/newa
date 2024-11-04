package io.github.green4j.newa.json;

import io.github.green4j.jelly.JsonParserListenerAdapter;

import java.util.ArrayList;
import java.util.List;

public class StringCollector extends JsonParserListenerAdapter {
    private final List<String> values = new ArrayList<>();

    @Override
    public void onJsonStarted() {
        values.clear();
    }

    @Override
    public boolean onStringValue(final CharSequence data) {
        values.add(data.toString());
        return true;
    }

    public String[] values() {
        return values.toArray(String[]::new);
    }
}
