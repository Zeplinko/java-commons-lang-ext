package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingRunnableTest {

    @Test
    void test_whenRunIsInvoked_thenExecutesSuccessfully() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        ThrowingRunnable runnable = counter::incrementAndGet;
        runnable.run();
        assertEquals(1, counter.get());
    }

    @Test
    void test_whenRunIsInvokedMultipleTimes_thenExecutesEachTime() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        ThrowingRunnable runnable = counter::incrementAndGet;
        runnable.run();
        runnable.run();
        runnable.run();
        assertEquals(3, counter.get());
    }

    @Test
    void test_whenRunThrowsException_thenExceptionIsPropagated() {
        ThrowingRunnable runnable = () -> {
            throw new IOException("Test exception");
        };
        Exception exception = assertThrows(Exception.class, runnable::run);
        assertEquals("Test exception", exception.getMessage());
        assertInstanceOf(IOException.class, exception);
    }

    @Test
    void test_whenRunIsInvokedWithSideEffect_thenSideEffectOccurs() throws Exception {
        StringBuilder sb = new StringBuilder();
        ThrowingRunnable runnable = () -> sb.append("executed");
        runnable.run();
        assertEquals("executed", sb.toString());
    }

    @Test
    void test_whenRunIsInvokedWithNoOperation_thenNoExceptionIsThrown() {
        ThrowingRunnable runnable = () -> {
            // No operation
        };
        assertDoesNotThrow(runnable::run);
    }
}
