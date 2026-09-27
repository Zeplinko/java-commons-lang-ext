package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
}
