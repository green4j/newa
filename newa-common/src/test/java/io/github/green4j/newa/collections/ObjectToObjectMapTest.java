package io.github.green4j.newa.collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

class ObjectToObjectMapTest {

    @Test
    public void testPutNewKey() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        Assertions.assertTrue(map.put("a", 1));
        Assertions.assertEquals(1, map.size());
    }

    @Test
    public void testPutExistingKey() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 1);
        Assertions.assertFalse(map.put("a", 2));
        Assertions.assertEquals(1, map.size());
        Assertions.assertEquals(2,
                map.get("a", null));
    }

    @Test
    public void testGetExistingAndMissing() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("x", 42);
        Assertions.assertEquals(42,
                map.get("x", -1));
        Assertions.assertEquals(-1,
                map.get("missing", -1));
    }

    @Test
    public void testRemove() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 10);
        map.put("b", 20);

        Assertions.assertEquals(10,
                map.remove("a", null));
        Assertions.assertNull(map.remove("a", null));
        Assertions.assertEquals(1, map.size());
        Assertions.assertFalse(map.containsKey("a"));
        Assertions.assertTrue(map.containsKey("b"));
    }

    @Test
    public void testSizeIsEmptyClear() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
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
        final ObjectToObjectMap<String, String> map =
                new ObjectToObjectMap<>();
        final String val = "hello";
        map.put("key", val);

        Assertions.assertTrue(map.containsKey("key"));
        Assertions.assertFalse(map.containsKey("other"));
        Assertions.assertTrue(map.containsValue(val));
        Assertions.assertFalse(
                map.containsValue("different"));
    }

    @Test
    public void testAutoResize() {
        final ObjectToObjectMap<Integer, Integer> map =
                new ObjectToObjectMap<>(16);
        final int count = 100;
        for (int i = 0; i < count; i++) {
            map.put(i, i * 10);
        }
        Assertions.assertEquals(count, map.size());
        for (int i = 0; i < count; i++) {
            Assertions.assertEquals(i * 10,
                    map.get(i, -1));
        }
    }

    @Test
    public void testIterator() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);

        final Set<Integer> values = new HashSet<>();
        final Iterator<Integer> it = map.iterator();
        while (it.hasNext()) {
            values.add(it.next());
        }
        Assertions.assertEquals(
                Set.of(1, 2, 3), values);
    }

    @Test
    public void testForEach() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 10);
        map.put("b", 20);

        final List<Integer> collected = new ArrayList<>();
        map.forEach(collected::add);

        Assertions.assertEquals(2, collected.size());
        Assertions.assertTrue(collected.contains(10));
        Assertions.assertTrue(collected.contains(20));
    }

    @Test
    public void testElementsEnumeration() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);

        final ResetableEnumeration<Integer> en =
                map.elements();
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
    public void testKeyIterator() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("x", 1);
        map.put("y", 2);

        final Set<String> keys = new HashSet<>();
        final Iterator<String> it = map.keyIterator();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Assertions.assertEquals(Set.of("x", "y"), keys);
    }

    @Test
    public void testRemoveBoolean() {
        final ObjectToObjectMap<String, Integer> map =
                new ObjectToObjectMap<>();
        map.put("a", 1);
        Assertions.assertTrue(map.remove("a"));
        Assertions.assertFalse(map.remove("a"));
        Assertions.assertTrue(map.isEmpty());
    }
}
