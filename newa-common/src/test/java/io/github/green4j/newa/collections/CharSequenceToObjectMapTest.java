/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CharSequenceToObjectMapTest {

    @Test
    public void testPutAndGet() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("hello", 1);
        map.put("world", 2);

        Assertions.assertEquals(1, map.get("hello"));
        Assertions.assertEquals(2, map.get("world"));
        Assertions.assertNull(map.get("missing"));
    }

    @Test
    public void testGetWithSubsequence() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("abc", 42);

        final String text = "xxabcyy";
        Assertions.assertEquals(42,
                map.get(text, 2, 5));
        Assertions.assertNull(map.get(text, 0, 3));
    }

    @Test
    public void testContainsKey() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("key", 1);

        Assertions.assertTrue(
                map.containsKey((CharSequence) "key"));
        Assertions.assertFalse(
                map.containsKey((CharSequence) "other"));
    }

    @Test
    public void testContainsKeySubsequence() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("dog", 1);

        Assertions.assertTrue(
                map.containsKey("xxdogxx", 2, 5));
        Assertions.assertFalse(
                map.containsKey("xxdogxx", 0, 3));
    }

    @Test
    public void testRemoveCharSequence() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("a", 1);
        map.put("b", 2);

        Assertions.assertEquals(1,
                map.remove((CharSequence) "a"));
        Assertions.assertNull(
                map.remove((CharSequence) "a"));
        Assertions.assertEquals(1, map.size());
    }

    @Test
    public void testRemoveSubsequence() {
        final CharSequenceToObjectMap<Integer> map =
                new CharSequenceToObjectMap<>();
        map.put("cat", 5);

        Assertions.assertEquals(5,
                map.remove("xxcatyy", 2, 5));
        Assertions.assertTrue(map.isEmpty());
    }

    @Test
    public void testKeyBufferHashCodeMatchesString() {
        final CharSequenceToObjectMap.KeyBuffer buf =
                new CharSequenceToObjectMap.KeyBuffer();
        final String key = "hello";
        buf.set(key);
        Assertions.assertEquals(
                key.hashCode(), buf.hashCode());
    }

    @Test
    public void testKeyBufferEqualsString() {
        final CharSequenceToObjectMap.KeyBuffer buf =
                new CharSequenceToObjectMap.KeyBuffer();
        buf.set("world");
        Assertions.assertTrue(buf.equals("world"));
        Assertions.assertFalse(buf.equals("other"));
    }
}
