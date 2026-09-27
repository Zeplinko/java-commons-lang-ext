package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingFunctionTest {

    @Test
    void test_whenApplyIsInvoked_thenTransformsValue() throws Exception {
        ThrowingFunction<String, Integer> function = String::length;
        Integer result = function.apply("test");
        assertEquals(4, result);
    }

    @Test
    void test_whenApplyIsInvokedWithNull_thenHandlesNull() throws Exception {
        ThrowingFunction<String, String> function = (value) -> value == null ? "null" : value.toUpperCase();
        String result = function.apply(null);
        assertEquals("null", result);
    }

    @Test
    void test_whenApplyThrowsException_thenExceptionIsPropagated() {
        ThrowingFunction<String, Integer> function = (value) -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, () -> function.apply("test"));
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenApplyIsInvokedMultipleTimes_thenTransformsEachValue() throws Exception {
        ThrowingFunction<Integer, Integer> function = (value) -> value * 2;
        assertEquals(4, function.apply(2));
        assertEquals(10, function.apply(5));
        assertEquals(20, function.apply(10));
    }

    @Test
    void test_whenApplyIsInvokedWithComplexTransformation_thenReturnsTransformedValue() throws Exception {
        ThrowingFunction<String, String[]> function = (value) -> value.split(",");
        String[] result = function.apply("a,b,c");
        assertEquals(3, result.length);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    void test_whenApplyReturnsNull_thenReturnsNull() throws Exception {
        ThrowingFunction<String, String> function = (value) -> null;
        String result = function.apply("test");
        assertNull(result);
    }
}
