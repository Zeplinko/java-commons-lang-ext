package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingBiFunctionTest {

    @Test
    void test_whenApplyIsInvoked_thenCombinesBothValues() throws Exception {
        ThrowingBiFunction<Integer, Integer, Integer> function = Integer::sum;
        Integer result = function.apply(5, 3);
        assertEquals(8, result);
    }

    @Test
    void test_whenApplyIsInvokedWithNullValues_thenHandlesNulls() throws Exception {
        ThrowingBiFunction<String, String, String> function = (first, second) -> {
            if (first == null && second == null)
                return "both null";
            if (first == null)
                return "first null";
            if (second == null)
                return "second null";
            return first + second;
        };
        assertEquals("both null", function.apply(null, null));
        assertEquals("first null", function.apply(null, "test"));
        assertEquals("second null", function.apply("test", null));
    }

    @Test
    void test_whenApplyThrowsException_thenExceptionIsPropagated() {
        ThrowingBiFunction<String, String, String> function = (first, second) -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, () -> function.apply("a", "b"));
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenApplyIsInvokedMultipleTimes_thenTransformsEachPair() throws Exception {
        ThrowingBiFunction<Integer, Integer, Integer> function = (a, b) -> a * b;
        assertEquals(6, function.apply(2, 3));
        assertEquals(20, function.apply(4, 5));
        assertEquals(42, function.apply(6, 7));
    }

    @Test
    void test_whenApplyReturnsNull_thenReturnsNull() throws Exception {
        ThrowingBiFunction<String, String, String> function = (first, second) -> null;
        String result = function.apply("a", "b");
        assertNull(result);
    }
}
