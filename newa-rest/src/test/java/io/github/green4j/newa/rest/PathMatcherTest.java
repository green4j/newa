package io.github.green4j.newa.rest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMatcherTest {

    @Test
    public void testStaticMatch() {
        final PathMatcher.Builder<Integer> builder = PathMatcher.builder();
        builder.withPath("/dogs", 1);
        builder.withPath("/dogs/a", 2);
        builder.withPath("/dogs/aaa", 3);
        builder.withPath("/dogs/aaaa", 4);
        builder.withPath("/dogs/aaa/bbb", 5);
        builder.withPath("/dogs/aaaa/bbb/c", 6);
        builder.withPath("/cats", 7);
        builder.withPath("/cats/a", 8);
        builder.withPath("/cats/aaa/bbb", 9);

        final PathMatcher<Integer> pathMatcher = builder.build();

        Assertions.assertEquals(7, pathMatcher.match("/cats").handler());
        Assertions.assertEquals(8, pathMatcher.match("/cats/a").handler());
        Assertions.assertEquals(6, pathMatcher.match("/dogs/aaaa/bbb/c").handler());
        Assertions.assertEquals(9, pathMatcher.match("/cats/aaa/bbb").handler());
        Assertions.assertEquals(1, pathMatcher.match("/dogs").handler());
        Assertions.assertEquals(2, pathMatcher.match("/dogs/a").handler());
        Assertions.assertEquals(3, pathMatcher.match("/dogs/aaa").handler());
        Assertions.assertEquals(5, pathMatcher.match("/dogs/aaa/bbb").handler());
        Assertions.assertEquals(4, pathMatcher.match("/dogs/aaaa").handler());

        Assertions.assertNull(pathMatcher.match("/dog"));
        Assertions.assertNull(pathMatcher.match("/dog/a"));
        Assertions.assertNull(pathMatcher.match("/cats/c/bbb"));
        Assertions.assertNull(pathMatcher.match("/cats/aa"));
        Assertions.assertNull(pathMatcher.match("/cats/aaa"));
        Assertions.assertNull(pathMatcher.match("/cats/aaa/bbb/c"));
    }

    @Test
    public void testParameterMatch() {
        final PathMatcher.Builder<Integer> builder = PathMatcher.builder();
        builder.withPath("/{id}", 1);
        builder.withPath("/dogs", 2);
        builder.withPath("/dogs/{id}/x", 3);
        builder.withPath("/dogs/{id}/x/yy", 4);
        builder.withPath("/dogs/{id}/xxxx/{id2}", 5);
        builder.withPath("/cats/{id}", 6);
        builder.withPath("/cats/{id}/{id2}", 7);
        builder.withPath("/cats", 8);

        final PathMatcher<Integer> pathMatcher = builder.build();

        PathMatcher<Integer>.Result result = pathMatcher.match("/10");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.handler());
        Assertions.assertEquals(1, result.numberOfParameters());
        Assertions.assertEquals("10", result.parameterValue("id").toString());

        result = pathMatcher.match("/dogs");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(2, result.handler());
        Assertions.assertEquals(0, result.numberOfParameters());

        result = pathMatcher.match("/dogs/20/x");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(3, result.handler());
        Assertions.assertEquals(1, result.numberOfParameters());
        Assertions.assertEquals("20", result.parameterValue("id").toString());

        result = pathMatcher.match("/dogs/30/x/yy");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(4, result.handler());
        Assertions.assertEquals(1, result.numberOfParameters());
        Assertions.assertEquals("30", result.parameterValue("id").toString());

        result = pathMatcher.match("/dogs/40/xxxx/150");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(5, result.handler());
        Assertions.assertEquals(2, result.numberOfParameters());
        Assertions.assertEquals("40", result.parameterValue("id").toString());
        Assertions.assertEquals("150", result.parameterValue("id2").toString());

        result = pathMatcher.match("/cats/80");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(6, result.handler());
        Assertions.assertEquals(1, result.numberOfParameters());
        Assertions.assertEquals("80", result.parameterValue("id").toString());

        result = pathMatcher.match("/cats/90/100");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(7, result.handler());
        Assertions.assertEquals(2, result.numberOfParameters());
        Assertions.assertEquals("90", result.parameterValue("id").toString());
        Assertions.assertEquals("100", result.parameterValue("id2").toString());

        result = pathMatcher.match("/cats");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(8, result.handler());
        Assertions.assertEquals(0, result.numberOfParameters());

        Assertions.assertNull(pathMatcher.match("/10/20"));
        Assertions.assertNull(pathMatcher.match("/cats/90/100/10"));
        Assertions.assertNull(pathMatcher.match("/dogs/10/y"));
    }

    @Test
    public void testAmbiguousError() {
        final PathMatcher.Builder<Integer> builder1 = PathMatcher.builder();
        builder1.withPath("/dogs/yy", 1);
        builder1.withPath("/cats", 2);

        Throwable exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder1.withPath("/dogs/yy", 3)
        );
        assertTrue(exception.getMessage().startsWith("Ambiguous path expression"));

        final PathMatcher.Builder<Integer> builder2 = PathMatcher.builder();
        builder2.withPath("/dogs/{id}/xx", 1);
        exception = assertThrows(
                IllegalArgumentException.class,
                () -> builder2.withPath("/dogs/{id2}/yy", 2)
        );
        assertTrue(exception.getMessage().startsWith("Ambiguous parameter"));
    }

    @Test
    public void testCopyConcurrently() {
        final PathMatcher.Builder<Integer> builder = PathMatcher.builder();
        builder.withPath("/dogs/{dogName}", 1);
        builder.withPath("/cats/{catName}", 2);

        final PathMatcher<Integer> pathMatcher1 = builder.build();
        final PathMatcher<Integer> pathMatcher2 = new PathMatcher<>(pathMatcher1);

        final PathMatcher<Integer>.Result result1 = pathMatcher1.match("/dogs/rex");
        final PathMatcher<Integer>.Result result2 = pathMatcher2.match("/cats/pussy");

        Assertions.assertNotNull(result1);
        Assertions.assertEquals(1, result1.handler());
        Assertions.assertEquals("rex", result1.parameterValue("dogName").toString());

        Assertions.assertNotNull(result2);
        Assertions.assertEquals(2, result2.handler());
        Assertions.assertEquals("pussy", result2.parameterValue("catName").toString());
    }
}