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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

class ObjectToObjectMapTest {

    @Test
    public void putReportsWhetherTheKeyWasNew() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();

        Assertions.assertTrue(map.put("a", 1));
        Assertions.assertEquals(1, map.size());
        Assertions.assertEquals(1, map.get("a", -1));
        Assertions.assertEquals(-1, map.get("missing", -1));

        Assertions.assertFalse(map.put("a", 2), "a key which was already there was called new");
        Assertions.assertEquals(1, map.size());
        Assertions.assertEquals(2, map.get("a", null), "the value was not replaced");
    }

    @Test
    public void removeGivesBackWhatWasThereAndSaysWhetherThereWasAnything() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("a", 10);
        map.put("b", 20);

        Assertions.assertEquals(10, map.remove("a", null));
        Assertions.assertNull(map.remove("a", null));
        Assertions.assertEquals(1, map.size());
        Assertions.assertFalse(map.containsKey("a"));
        Assertions.assertTrue(map.containsKey("b"));

        // and the overload which only says whether it found one
        Assertions.assertTrue(map.remove("b"));
        Assertions.assertFalse(map.remove("b"));
        Assertions.assertTrue(map.isEmpty());
    }

    @Test
    public void testSizeIsEmptyClear() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        Assertions.assertTrue(map.isEmpty());
        Assertions.assertEquals(0, map.size());

        map.put("a", 1);
        map.put("b", 2);
        Assertions.assertFalse(map.isEmpty());
        Assertions.assertEquals(2, map.size());

        map.clear();
        Assertions.assertTrue(map.isEmpty());
        Assertions.assertEquals(0, map.size());
        Assertions.assertNull(map.get("a", null));
    }

    @Test
    public void testContainsKeyAndValue() {
        final ObjectToObjectMap<String, String> map = new ObjectToObjectMap<>();
        final String val = "hello";
        map.put("key", val);

        Assertions.assertTrue(map.containsKey("key"));
        Assertions.assertFalse(map.containsKey("other"));
        Assertions.assertTrue(map.containsValue(val));
        Assertions.assertFalse(map.containsValue("different"));
    }

    @Test
    public void testAutoResize() {
        final ObjectToObjectMap<Integer, Integer> map = new ObjectToObjectMap<>(16);
        final int count = 100;
        for (int i = 0; i < count; i++) {
            map.put(i, i * 10);
        }
        Assertions.assertEquals(count, map.size());
        for (int i = 0; i < count; i++) {
            Assertions.assertEquals(i * 10, map.get(i, -1));
        }
    }

    /**
     * The three ways out of the map's values, each of which has to reach every one of them.
     *
     * @return one case per way: what it is called, and how it collects what it reached.
     */
    private static Stream<Arguments> everyWayOverTheValues() {
        return Stream.of(
                Arguments.of("iterator",
                        (Function<ObjectToObjectMap<String, Integer>, Set<Integer>>) map -> {
                            final Set<Integer> seen = new HashSet<>();
                            final Iterator<Integer> it = map.iterator();
                            while (it.hasNext()) {
                                seen.add(it.next());
                            }
                            return seen;
                        }),
                Arguments.of("forEach",
                        (Function<ObjectToObjectMap<String, Integer>, Set<Integer>>) map -> {
                            final List<Integer> collected = new ArrayList<>();
                            map.forEach(collected::add);
                            return new HashSet<>(collected);
                        }),
                Arguments.of("elements",
                        (Function<ObjectToObjectMap<String, Integer>, Set<Integer>>) map -> {
                            final Set<Integer> seen = new HashSet<>();
                            final ResettableEnumeration<Integer> en = map.elements();
                            while (en.hasMoreElements()) {
                                seen.add(en.nextElement());
                            }
                            return seen;
                        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWayOverTheValues")
    public void everyValueIsVisited(final String way,
                                    final Function<ObjectToObjectMap<String, Integer>, Set<Integer>> walk) {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);

        Assertions.assertEquals(Set.of(1, 2, 3), walk.apply(map), way);
    }

    @Test
    public void theElementsEnumerationStartsAgainWhenItIsReset() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);

        final ResettableEnumeration<Integer> en = map.elements();
        final Set<Integer> first = new HashSet<>();
        while (en.hasMoreElements()) {
            first.add(en.nextElement());
        }
        Assertions.assertEquals(Set.of(1, 2), first);

        en.reset();
        final Set<Integer> second = new HashSet<>();
        while (en.hasMoreElements()) {
            second.add(en.nextElement());
        }
        Assertions.assertEquals(first, second);
    }

    @Test
    public void theKeyOfAnElementIsTheOneJustReturned() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);

        final ResettableEnumeration<Integer> en = map.elements();
        @SuppressWarnings("unchecked")
        final KeyEntry<String> keys = (KeyEntry<String>) en;

        final Map<String, Integer> walked = new HashMap<>();
        while (en.hasMoreElements()) {
            final Integer value = en.nextElement();
            walked.put(keys.key(), value);   // the key of that value, not of the next one
        }

        Assertions.assertEquals(Map.of("a", 1, "b", 2, "c", 3), walked);
    }

    @Test
    public void thereIsNoKeyUntilSomethingHasBeenReturned() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("a", 1);

        final ResettableEnumeration<Integer> en = map.elements();
        @SuppressWarnings("unchecked")
        final KeyEntry<String> keys = (KeyEntry<String>) en;

        Assertions.assertThrows(IllegalStateException.class, keys::key);

        en.nextElement();
        Assertions.assertEquals("a", keys.key());

        en.reset();
        Assertions.assertThrows(IllegalStateException.class, keys::key,
                "a reset left the key of a walk which has started again");
    }

    @Test
    public void testKeyIterator() {
        final ObjectToObjectMap<String, Integer> map = new ObjectToObjectMap<>();
        map.put("x", 1);
        map.put("y", 2);

        final Set<String> keys = new HashSet<>();
        final Iterator<String> it = map.keyIterator();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Assertions.assertEquals(Set.of("x", "y"), keys);
    }
}
