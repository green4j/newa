/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The CharSequence-keyed contract, which both maps answer to. They share no supertype which carries it -
 * one extends HashMap and the other ConcurrentHashMap - so the contract is named here and both are run
 * against every case of it. What belongs to one of them alone is tested where it belongs:
 * {@link CharSequenceToObjectMapConcurrentTest} for putIfAbsent and for reading while it is written.
 */
class CharSequenceToObjectMapTest {

    /** The part of both maps which is the same, so that one set of cases can ask it of either. */
    private interface Subject {
        void put(CharSequence key, Integer value);

        Integer get(CharSequence key);

        Integer get(CharSequence key, int start, int end);

        boolean containsKey(CharSequence key);

        Integer remove(CharSequence key);

        Integer remove(CharSequence key, int start, int end);

        int size();

        boolean isEmpty();
    }

    private static Subject plain() {
        final CharSequenceToObjectMap<Integer> map = new CharSequenceToObjectMap<>();
        return new Subject() {
            @Override
            public void put(final CharSequence key, final Integer value) {
                map.put(key, value);
            }

            @Override
            public Integer get(final CharSequence key) {
                return map.get(key);
            }

            @Override
            public Integer get(final CharSequence key, final int start, final int end) {
                return map.get(key, start, end);
            }

            @Override
            public boolean containsKey(final CharSequence key) {
                return map.containsKey(key);
            }

            @Override
            public Integer remove(final CharSequence key) {
                return map.remove(key);
            }

            @Override
            public Integer remove(final CharSequence key, final int start, final int end) {
                return map.remove(key, start, end);
            }

            @Override
            public int size() {
                return map.size();
            }

            @Override
            public boolean isEmpty() {
                return map.isEmpty();
            }
        };
    }

    private static Subject concurrent() {
        final CharSequenceToObjectMapConcurrent<Integer> map = new CharSequenceToObjectMapConcurrent<>();
        return new Subject() {
            @Override
            public void put(final CharSequence key, final Integer value) {
                map.put(key, value);
            }

            @Override
            public Integer get(final CharSequence key) {
                return map.get(key);
            }

            @Override
            public Integer get(final CharSequence key, final int start, final int end) {
                return map.get(key, start, end);
            }

            @Override
            public boolean containsKey(final CharSequence key) {
                return map.containsKey(key);
            }

            @Override
            public Integer remove(final CharSequence key) {
                return map.remove(key);
            }

            @Override
            public Integer remove(final CharSequence key, final int start, final int end) {
                return map.remove(key, start, end);
            }

            @Override
            public int size() {
                return map.size();
            }

            @Override
            public boolean isEmpty() {
                return map.isEmpty();
            }
        };
    }

    private static Stream<Arguments> bothMaps() {
        return Stream.of(
                Arguments.of("CharSequenceToObjectMap", (Supplier<Subject>)
                        CharSequenceToObjectMapTest::plain),
                Arguments.of("CharSequenceToObjectMapConcurrent", (Supplier<Subject>)
                        CharSequenceToObjectMapTest::concurrent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothMaps")
    public void putAndGet(final String name,
                          final Supplier<Subject> of) {
        final Subject map = of.get();
        map.put("hello", 1);
        map.put(new StringBuilder("world"), 2); // a key which is not a String, and never becomes one

        Assertions.assertEquals(1, map.get("hello"), name);
        Assertions.assertEquals(1, map.get(new StringBuilder("hello")), name);
        Assertions.assertEquals(2, map.get("world"), name);
        Assertions.assertNull(map.get("missing"), name);
        Assertions.assertEquals(2, map.size(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothMaps")
    public void getWithSubsequence(final String name,
                                   final Supplier<Subject> of) {
        final Subject map = of.get();
        map.put("abc", 42);

        // the point of the whole class: a key read out of a longer line without cutting one out of it
        Assertions.assertEquals(42, map.get("xxabcyy", 2, 5), name);
        Assertions.assertNull(map.get("xxabcyy", 0, 3), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothMaps")
    public void containsKey(final String name,
                            final Supplier<Subject> of) {
        final Subject map = of.get();
        map.put("key", 1);

        Assertions.assertTrue(map.containsKey("key"), name);
        Assertions.assertTrue(map.containsKey(new StringBuilder("key")), name);
        Assertions.assertFalse(map.containsKey("other"), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothMaps")
    public void remove(final String name,
                       final Supplier<Subject> of) {
        final Subject map = of.get();
        map.put("a", 1);
        map.put("cat", 5);

        Assertions.assertEquals(1, map.remove(new StringBuilder("a")), name);
        Assertions.assertNull(map.remove("a"), name);
        Assertions.assertFalse(map.containsKey("a"), name);

        Assertions.assertEquals(5, map.remove("xxcatyy", 2, 5), name);
        Assertions.assertTrue(map.isEmpty(), name);
    }

    @Test
    public void testContainsKeySubsequence() {
        // only the plain map offers this one
        final CharSequenceToObjectMap<Integer> map = new CharSequenceToObjectMap<>();
        map.put("dog", 1);

        Assertions.assertTrue(map.containsKey("xxdogxx", 2, 5));
        Assertions.assertFalse(map.containsKey("xxdogxx", 0, 3));
    }

    @Test
    public void theKeyBufferIsTheStringItStandsFor() {
        // what makes a lookup by subsequence allocation-free: the buffer hashes and compares as the String
        // the map is keyed by, so the table finds the entry without a String ever being built
        final CharSequenceToObjectMap.KeyBuffer buf = new CharSequenceToObjectMap.KeyBuffer();

        buf.set("hello");
        Assertions.assertEquals("hello".hashCode(), buf.hashCode());

        buf.set("world");
        Assertions.assertTrue(buf.equals("world"));
        Assertions.assertFalse(buf.equals("other"));
    }
}
