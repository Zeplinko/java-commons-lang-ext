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

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardRunnable() {
        AtomicInteger counter = new AtomicInteger(0);
        ThrowingRunnable throwing = counter::incrementAndGet;
        Runnable standard = throwing.toUnchecked();
        standard.run();
        assertEquals(1, counter.get());
    }

    @Test
    void test_whenToUncheckedThrows_thenRethrowsAsRuntimeException() {
        ThrowingRunnable throwing = () -> {
            throw new IOException("io error");
        };
        Runnable standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, standard::run);
    }

    @Test
    void test_whenToUncheckedThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingRunnable throwing = () -> {
            throw cause;
        };
        Runnable standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, standard::run);
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingRunnable throwing = () -> {
            throw original;
        };
        RuntimeException ex = assertThrows(IllegalArgumentException.class, throwing.toUnchecked()::run);
        assertSame(original, ex);
    }
}
