package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.Function;

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

    @Test
    void test_whenAndThenIsInvoked_thenAppliesAfterFunction() throws Exception {
        ThrowingFunction<String, Integer> parse = Integer::parseInt;
        ThrowingFunction<Integer, String> format = i -> String.format("%04d", i);
        ThrowingFunction<String, String> composed = parse.andThen(format);
        assertEquals("0042", composed.apply("42"));
    }

    @Test
    void test_whenAndThenFirstFunctionThrows_thenExceptionPropagated() {
        ThrowingFunction<String, Integer> parse = s -> {
            throw new IOException("parse failed");
        };
        ThrowingFunction<Integer, String> format = Object::toString;
        ThrowingFunction<String, String> composed = parse.andThen(format);
        Exception ex = assertThrows(IOException.class, () -> composed.apply("bad"));
        assertEquals("parse failed", ex.getMessage());
    }

    @Test
    void test_whenAndThenSecondFunctionThrows_thenExceptionPropagated() {
        ThrowingFunction<String, Integer> parse = Integer::parseInt;
        ThrowingFunction<Integer, String> format = i -> {
            throw new IOException("format failed");
        };
        ThrowingFunction<String, String> composed = parse.andThen(format);
        Exception ex = assertThrows(IOException.class, () -> composed.apply("42"));
        assertEquals("format failed", ex.getMessage());
    }

    @Test
    void test_whenComposeIsInvoked_thenAppliesBeforeFunction() throws Exception {
        ThrowingFunction<String, Integer> parse = Integer::parseInt;
        ThrowingFunction<Integer, String> stringify = i -> i + "!";
        ThrowingFunction<String, String> composed = stringify.compose(parse);
        assertEquals("42!", composed.apply("42"));
    }

    @Test
    void test_whenComposeBeforeFunctionThrows_thenExceptionPropagated() {
        ThrowingFunction<String, Integer> parse = s -> {
            throw new IOException("parse failed");
        };
        ThrowingFunction<Integer, String> stringify = Object::toString;
        ThrowingFunction<String, String> composed = stringify.compose(parse);
        Exception ex = assertThrows(IOException.class, () -> composed.apply("bad"));
        assertEquals("parse failed", ex.getMessage());
    }

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardFunction() {
        ThrowingFunction<String, Integer> throwing = Integer::parseInt;
        Function<String, Integer> standard = throwing.toUnchecked();
        assertEquals(42, standard.apply("42"));
    }

    @Test
    void test_whenToUncheckedFunctionThrows_thenRethrowsAsRuntimeException() {
        ThrowingFunction<String, Integer> throwing = s -> {
            throw new IOException("io error");
        };
        Function<String, Integer> standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, () -> standard.apply("bad"));
    }

    @Test
    void test_whenToUncheckedFunctionThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingFunction<String, Integer> throwing = s -> {
            throw cause;
        };
        Function<String, Integer> standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> standard.apply("bad"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingFunction<String, Integer> throwing = s -> {
            throw original;
        };
        RuntimeException ex = assertThrows(IllegalArgumentException.class, () -> throwing.toUnchecked().apply("x"));
        assertSame(original, ex);
    }

    @Test
    void test_whenAndThenCalledWithNull_thenThrowsNullPointerException() {
        ThrowingFunction<String, Integer> f = Integer::parseInt;
        assertThrows(NullPointerException.class, () -> f.andThen(null));
    }

    @Test
    void test_whenComposeCalledWithNull_thenThrowsNullPointerException() {
        ThrowingFunction<Integer, String> f = Object::toString;
        assertThrows(NullPointerException.class, () -> f.compose(null));
    }

    @Test
    void test_whenComposeOuterFunctionThrows_thenExceptionPropagated() {
        ThrowingFunction<String, Integer> parse = Integer::parseInt;
        ThrowingFunction<Integer, String> stringify = i -> {
            throw new IOException("stringify failed");
        };
        ThrowingFunction<String, String> composed = stringify.compose(parse);
        Exception ex = assertThrows(IOException.class, () -> composed.apply("42"));
        assertEquals("stringify failed", ex.getMessage());
    }

    @Test
    void test_whenIdentityIsInvoked_thenReturnsSameInstance() throws Exception {
        ThrowingFunction<String, String> identity = ThrowingFunction.identity();
        String value = "hello";
        assertSame(value, identity.apply(value));
    }

    @Test
    void test_whenIdentityIsInvokedWithNull_thenReturnsNull() throws Exception {
        ThrowingFunction<String, String> identity = ThrowingFunction.identity();
        assertNull(identity.apply(null));
    }
}
