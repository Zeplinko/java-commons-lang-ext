package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingConsumerTest {

    @Test
    void test_whenAcceptIsInvoked_thenConsumesValue() throws Exception {
        List<String> list = new ArrayList<>();
        ThrowingConsumer<String> consumer = list::add;
        consumer.accept("test");
        assertEquals(1, list.size());
        assertEquals("test", list.get(0));
    }

    @Test
    void test_whenAcceptIsInvokedWithNull_thenConsumesNull() throws Exception {
        List<String> list = new ArrayList<>();
        ThrowingConsumer<String> consumer = list::add;
        consumer.accept(null);
        assertEquals(1, list.size());
        assertNull(list.get(0));
    }

    @Test
    void test_whenAcceptThrowsException_thenExceptionIsPropagated() {
        ThrowingConsumer<String> consumer = (value) -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, () -> consumer.accept("test"));
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenAcceptIsInvokedMultipleTimes_thenConsumesAllValues() throws Exception {
        List<Integer> list = new ArrayList<>();
        ThrowingConsumer<Integer> consumer = list::add;
        consumer.accept(1);
        consumer.accept(2);
        consumer.accept(3);
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));
    }

    @Test
    void test_whenAcceptIsInvokedWithComplexObject_thenConsumesObject() throws Exception {
        List<String[]> list = new ArrayList<>();
        ThrowingConsumer<String[]> consumer = list::add;
        String[] array = { "a", "b", "c" };
        consumer.accept(array);
        assertEquals(1, list.size());
        assertSame(array, list.get(0));
    }

    @Test
    void test_whenAndThenIsInvoked_thenBothConsumersAreExecuted() throws Exception {
        List<String> log = new ArrayList<>();
        ThrowingConsumer<String> first = s -> log.add("first:" + s);
        ThrowingConsumer<String> second = s -> log.add("second:" + s);
        first.andThen(second).accept("x");
        assertEquals(Arrays.asList("first:x", "second:x"), log);
    }

    @Test
    void test_whenAndThenFirstConsumerThrows_thenSecondConsumerNotCalled() {
        List<String> log = new ArrayList<>();
        ThrowingConsumer<String> first = s -> {
            throw new IOException("first failed");
        };
        ThrowingConsumer<String> second = s -> log.add("second");
        assertThrows(IOException.class, () -> first.andThen(second).accept("x"));
        assertTrue(log.isEmpty());
    }

    @Test
    void test_whenAndThenCalledWithNull_thenThrowsNullPointerException() {
        ThrowingConsumer<String> c = s -> {
        };
        assertThrows(NullPointerException.class, () -> c.andThen(null));
    }

    @Test
    void test_whenAndThenSecondConsumerThrows_thenExceptionPropagated() {
        List<String> log = new ArrayList<>();
        ThrowingConsumer<String> first = s -> log.add("first:" + s);
        ThrowingConsumer<String> second = s -> {
            throw new IOException("second failed");
        };
        assertThrows(IOException.class, () -> first.andThen(second).accept("x"));
        assertEquals(Arrays.asList("first:x"), log);
    }

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardConsumer() {
        List<String> log = new ArrayList<>();
        ThrowingConsumer<String> throwing = log::add;
        Consumer<String> standard = throwing.toUnchecked();
        standard.accept("hello");
        assertEquals(Arrays.asList("hello"), log);
    }

    @Test
    void test_whenToUncheckedThrows_thenRethrowsAsRuntimeException() {
        ThrowingConsumer<String> throwing = s -> {
            throw new IOException("io error");
        };
        Consumer<String> standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, () -> standard.accept("x"));
    }

    @Test
    void test_whenToUncheckedThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingConsumer<String> throwing = s -> {
            throw cause;
        };
        Consumer<String> standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> standard.accept("x"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingConsumer<String> throwing = s -> {
            throw original;
        };
        RuntimeException ex = assertThrows(IllegalArgumentException.class, () -> throwing.toUnchecked().accept("x"));
        assertSame(original, ex);
    }
}
