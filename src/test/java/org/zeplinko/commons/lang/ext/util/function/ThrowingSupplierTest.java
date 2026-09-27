package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingSupplierTest {

    @Test
    void test_whenGetIsInvoked_thenReturnsExpectedValue() throws Exception {
        ThrowingSupplier<String> supplier = () -> "test value";
        String result = supplier.get();
        assertEquals("test value", result);
    }

    @Test
    void test_whenGetIsInvokedWithNull_thenReturnsNull() throws Exception {
        ThrowingSupplier<String> supplier = () -> null;
        String result = supplier.get();
        assertNull(result);
    }

    @Test
    void test_whenGetThrowsException_thenExceptionIsPropagated() {
        ThrowingSupplier<String> supplier = () -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, supplier::get);
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenGetIsInvokedMultipleTimes_thenReturnsConsistentValue() throws Exception {
        ThrowingSupplier<Integer> supplier = () -> 42;
        assertEquals(42, supplier.get());
        assertEquals(42, supplier.get());
        assertEquals(42, supplier.get());
    }

    @Test
    void test_whenGetIsInvokedWithComplexObject_thenReturnsObject() throws Exception {
        String[] expectedArray = { "a", "b", "c" };
        ThrowingSupplier<String[]> supplier = () -> expectedArray;
        String[] result = supplier.get();
        assertSame(expectedArray, result);
        assertArrayEquals(expectedArray, result);
    }
}
