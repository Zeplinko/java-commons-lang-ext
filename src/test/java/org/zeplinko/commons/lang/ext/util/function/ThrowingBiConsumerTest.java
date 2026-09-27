package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingBiConsumerTest {

    @Test
    void test_whenAcceptIsInvoked_thenConsumesBothValues() throws Exception {
        Map<String, Integer> map = new HashMap<>();
        ThrowingBiConsumer<String, Integer> consumer = map::put;
        consumer.accept("key", 42);
        assertEquals(1, map.size());
        assertEquals(42, map.get("key"));
    }

    @Test
    void test_whenAcceptIsInvokedWithNullValues_thenConsumesNulls() throws Exception {
        Map<String, Integer> map = new HashMap<>();
        ThrowingBiConsumer<String, Integer> consumer = map::put;
        consumer.accept(null, null);
        assertEquals(1, map.size());
        assertTrue(map.containsKey(null));
        assertNull(map.get(null));
    }

    @Test
    void test_whenAcceptThrowsException_thenExceptionIsPropagated() {
        ThrowingBiConsumer<String, Integer> consumer = (key, value) -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, () -> consumer.accept("key", 42));
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenAcceptIsInvokedMultipleTimes_thenConsumesAllValues() throws Exception {
        Map<String, Integer> map = new HashMap<>();
        ThrowingBiConsumer<String, Integer> consumer = map::put;
        consumer.accept("one", 1);
        consumer.accept("two", 2);
        consumer.accept("three", 3);
        assertEquals(3, map.size());
        assertEquals(1, map.get("one"));
        assertEquals(2, map.get("two"));
        assertEquals(3, map.get("three"));
    }

    @Test
    void test_whenAndThenIsInvoked_thenBothConsumersAreExecuted() throws Exception {
        List<String> log = new ArrayList<>();
        ThrowingBiConsumer<String, Integer> first = (k, v) -> log.add("first:" + k + v);
        ThrowingBiConsumer<String, Integer> second = (k, v) -> log.add("second:" + k + v);
        first.andThen(second).accept("x", 1);
        assertEquals(Arrays.asList("first:x1", "second:x1"), log);
    }

    @Test
    void test_whenAndThenFirstConsumerThrows_thenSecondConsumerNotCalled() {
        List<String> log = new ArrayList<>();
        ThrowingBiConsumer<String, Integer> first = (k, v) -> {
            throw new IOException("first failed");
        };
        ThrowingBiConsumer<String, Integer> second = (k, v) -> log.add("second");
        assertThrows(IOException.class, () -> first.andThen(second).accept("x", 1));
        assertTrue(log.isEmpty());
    }

    @Test
    void test_whenAndThenCalledWithNull_thenThrowsNullPointerException() {
        ThrowingBiConsumer<String, Integer> c = (k, v) -> {
        };
        assertThrows(NullPointerException.class, () -> c.andThen(null));
    }

    @Test
    void test_whenAndThenSecondConsumerThrows_thenExceptionPropagated() {
        List<String> log = new ArrayList<>();
        ThrowingBiConsumer<String, Integer> first = (k, v) -> log.add("first:" + k + v);
        ThrowingBiConsumer<String, Integer> second = (k, v) -> {
            throw new IOException("second failed");
        };
        assertThrows(IOException.class, () -> first.andThen(second).accept("x", 1));
        assertEquals(Arrays.asList("first:x1"), log);
    }

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardBiConsumer() {
        Map<String, Integer> map = new HashMap<>();
        ThrowingBiConsumer<String, Integer> throwing = map::put;
        BiConsumer<String, Integer> standard = throwing.toUnchecked();
        standard.accept("key", 42);
        assertEquals(42, map.get("key"));
    }

    @Test
    void test_whenToUncheckedThrows_thenRethrowsAsRuntimeException() {
        ThrowingBiConsumer<String, Integer> throwing = (k, v) -> {
            throw new IOException("io error");
        };
        BiConsumer<String, Integer> standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, () -> standard.accept("k", 1));
    }

    @Test
    void test_whenToUncheckedThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingBiConsumer<String, Integer> throwing = (k, v) -> {
            throw cause;
        };
        BiConsumer<String, Integer> standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> standard.accept("k", 1));
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingBiConsumer<String, Integer> throwing = (k, v) -> {
            throw original;
        };
        RuntimeException ex = assertThrows(IllegalArgumentException.class, () -> throwing.toUnchecked().accept("k", 1));
        assertSame(original, ex);
    }
}
