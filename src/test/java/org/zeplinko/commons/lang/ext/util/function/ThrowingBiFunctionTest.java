package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.BiFunction;

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

    @Test
    void test_whenAndThenIsInvoked_thenAppliesAfterFunction() throws Exception {
        ThrowingBiFunction<Integer, Integer, Integer> sum = Integer::sum;
        ThrowingFunction<Integer, String> stringify = i -> "result=" + i;
        ThrowingBiFunction<Integer, Integer, String> composed = sum.andThen(stringify);
        assertEquals("result=8", composed.apply(5, 3));
    }

    @Test
    void test_whenAndThenBiFunctionThrows_thenExceptionPropagated() {
        ThrowingBiFunction<String, String, String> biFunction = (a, b) -> {
            throw new IOException("bi failed");
        };
        ThrowingFunction<String, Integer> after = String::length;
        ThrowingBiFunction<String, String, Integer> composed = biFunction.andThen(after);
        assertThrows(IOException.class, () -> composed.apply("a", "b"));
    }

    @Test
    void test_whenAndThenAfterFunctionThrows_thenExceptionPropagated() {
        ThrowingBiFunction<Integer, Integer, Integer> sum = Integer::sum;
        ThrowingFunction<Integer, String> after = i -> {
            throw new IOException("after failed");
        };
        ThrowingBiFunction<Integer, Integer, String> composed = sum.andThen(after);
        assertThrows(IOException.class, () -> composed.apply(1, 2));
    }

    @Test
    void test_whenAndThenCalledWithNull_thenThrowsNullPointerException() {
        ThrowingBiFunction<Integer, Integer, Integer> f = Integer::sum;
        assertThrows(NullPointerException.class, () -> f.andThen(null));
    }

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardBiFunction() {
        ThrowingBiFunction<Integer, Integer, Integer> throwing = Integer::sum;
        BiFunction<Integer, Integer, Integer> standard = throwing.toUnchecked();
        assertEquals(8, standard.apply(5, 3));
    }

    @Test
    void test_whenToUncheckedThrows_thenRethrowsAsRuntimeException() {
        ThrowingBiFunction<String, String, String> throwing = (a, b) -> {
            throw new IOException("io error");
        };
        BiFunction<String, String, String> standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, () -> standard.apply("a", "b"));
    }

    @Test
    void test_whenToUncheckedThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingBiFunction<String, String, String> throwing = (a, b) -> {
            throw cause;
        };
        BiFunction<String, String, String> standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> standard.apply("a", "b"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingBiFunction<String, String, String> throwing = (a, b) -> {
            throw original;
        };
        RuntimeException ex = assertThrows(
                IllegalArgumentException.class,
                () -> throwing.toUnchecked().apply("a", "b")
        );
        assertSame(original, ex);
    }
}
