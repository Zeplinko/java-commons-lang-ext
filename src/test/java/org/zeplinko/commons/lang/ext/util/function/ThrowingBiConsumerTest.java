package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
}
