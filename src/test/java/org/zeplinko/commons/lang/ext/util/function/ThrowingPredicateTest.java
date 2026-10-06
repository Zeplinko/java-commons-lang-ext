package org.zeplinko.commons.lang.ext.util.function;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ThrowingPredicateTest {

    @Test
    void test_whenTestIsInvoked_thenEvaluatesPredicate() throws Exception {
        ThrowingPredicate<String> predicate = s -> s.length() > 3;
        assertTrue(predicate.test("hello"));
        assertFalse(predicate.test("hi"));
    }

    @Test
    void test_whenTestThrows_thenExceptionPropagated() {
        ThrowingPredicate<String> predicate = s -> {
            throw new IOException("test failed");
        };
        Exception ex = assertThrows(IOException.class, () -> predicate.test("any"));
        assertEquals("test failed", ex.getMessage());
    }

    @Test
    void test_whenAndIsInvoked_thenBothMustBeTrue() throws Exception {
        ThrowingPredicate<Integer> positive = i -> i > 0;
        ThrowingPredicate<Integer> even = i -> i % 2 == 0;
        ThrowingPredicate<Integer> positiveAndEven = positive.and(even);

        assertTrue(positiveAndEven.test(4));
        assertFalse(positiveAndEven.test(3));
        assertFalse(positiveAndEven.test(-2));
    }

    @Test
    void test_whenAndShortCircuits_thenSecondNotEvaluated() throws Exception {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        ThrowingPredicate<Integer> alwaysFalse = i -> false;
        ThrowingPredicate<Integer> recorder = i -> {
            secondCalled.set(true);
            return true;
        };

        assertFalse(alwaysFalse.and(recorder).test(1));
        assertFalse(secondCalled.get());
    }

    @Test
    void test_whenOrIsInvoked_thenEitherSuffices() throws Exception {
        ThrowingPredicate<Integer> positive = i -> i > 0;
        ThrowingPredicate<Integer> even = i -> i % 2 == 0;
        ThrowingPredicate<Integer> positiveOrEven = positive.or(even);

        assertTrue(positiveOrEven.test(3));
        assertTrue(positiveOrEven.test(-2));
        assertFalse(positiveOrEven.test(-3));
    }

    @Test
    void test_whenOrShortCircuits_thenSecondNotEvaluated() throws Exception {
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        ThrowingPredicate<Integer> alwaysTrue = i -> true;
        ThrowingPredicate<Integer> recorder = i -> {
            secondCalled.set(true);
            return false;
        };

        assertTrue(alwaysTrue.or(recorder).test(1));
        assertFalse(secondCalled.get());
    }

    @Test
    void test_whenNegateIsInvoked_thenInvertsResult() throws Exception {
        ThrowingPredicate<String> isEmpty = String::isEmpty;
        ThrowingPredicate<String> isNotEmpty = isEmpty.negate();

        assertFalse(isNotEmpty.test(""));
        assertTrue(isNotEmpty.test("hello"));
    }

    @Test
    void test_whenToUncheckedIsInvoked_thenReturnsStandardPredicate() {
        ThrowingPredicate<String> throwing = s -> s.length() > 3;
        Predicate<String> standard = throwing.toUnchecked();

        assertTrue(standard.test("hello"));
        assertFalse(standard.test("hi"));
    }

    @Test
    void test_whenToUncheckedThrows_thenRethrowsAsRuntimeException() {
        ThrowingPredicate<String> throwing = s -> {
            throw new IOException("io error");
        };
        Predicate<String> standard = throwing.toUnchecked();
        assertThrows(RuntimeException.class, () -> standard.test("any"));
    }

    @Test
    void test_whenToUncheckedThrows_thenOriginalExceptionIsWrapped() {
        IOException cause = new IOException("io error");
        ThrowingPredicate<String> throwing = s -> {
            throw cause;
        };
        Predicate<String> standard = throwing.toUnchecked();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> standard.test("any"));
        assertSame(cause, ex.getCause());
    }

    @Test
    void test_whenToUncheckedThrowsRuntimeException_thenNotWrapped() {
        IllegalArgumentException original = new IllegalArgumentException("direct");
        ThrowingPredicate<String> throwing = s -> {
            throw original;
        };
        RuntimeException ex = assertThrows(IllegalArgumentException.class, () -> throwing.toUnchecked().test("any"));
        assertSame(original, ex);
    }

    @Test
    void test_whenAndCalledWithNull_thenThrowsNullPointerException() {
        ThrowingPredicate<String> p = s -> true;
        assertThrows(NullPointerException.class, () -> p.and(null));
    }

    @Test
    void test_whenOrCalledWithNull_thenThrowsNullPointerException() {
        ThrowingPredicate<String> p = s -> false;
        assertThrows(NullPointerException.class, () -> p.or(null));
    }

    @Test
    void test_whenNotIsInvoked_thenInvertsResult() throws Exception {
        ThrowingPredicate<String> isEmpty = String::isEmpty;
        ThrowingPredicate<String> isNotEmpty = ThrowingPredicate.not(isEmpty);
        assertFalse(isNotEmpty.test(""));
        assertTrue(isNotEmpty.test("hello"));
    }

    @Test
    void test_whenNotCalledWithNull_thenThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> ThrowingPredicate.not(null));
    }

    @Test
    void test_whenIsEqualIsInvoked_thenMatchesEqualValues() throws Exception {
        ThrowingPredicate<String> predicate = ThrowingPredicate.isEqual("hello");
        assertTrue(predicate.test("hello"));
        assertFalse(predicate.test("world"));
    }

    @Test
    void test_whenIsEqualIsInvokedWithNullTarget_thenMatchesNull() throws Exception {
        ThrowingPredicate<String> predicate = ThrowingPredicate.isEqual(null);
        assertTrue(predicate.test(null));
        assertFalse(predicate.test("hello"));
    }

    @Test
    void test_whenIsEqualIsInvokedWithNullInput_thenDoesNotMatch() throws Exception {
        ThrowingPredicate<String> predicate = ThrowingPredicate.isEqual("hello");
        assertFalse(predicate.test(null));
    }
}
