package org.zeplinko.commons.lang.ext.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TryTest {
    private static Stream<Arguments> provideHashcodeCoverageCases() {
        RuntimeException runtimeException = new RuntimeException();
        return Stream.of(
                Arguments.of(Try.success(2), Objects.hash(2, null)),
                Arguments.of(Try.failure(runtimeException), Objects.hash(null, runtimeException))
        );
    }

    private static Stream<Arguments> provideEqualityCases() {
        Try<Integer> successTry = Try.success(5);
        RuntimeException runtimeException = new RuntimeException("Hello");
        Try<Void> failureTry = Try.failure(runtimeException);
        return Stream.of(
                Arguments.of(successTry, successTry, true),
                Arguments.of(successTry, Try.success(5), true),
                Arguments.of(successTry, Try.success(6), false),
                Arguments.of(successTry, Optional.of(6), false),
                Arguments.of(successTry, null, false),
                Arguments.of(failureTry, failureTry, true),
                Arguments.of(failureTry, Try.<Void>failure(runtimeException), true),
                Arguments.of(failureTry, Try.<Void>failure(new IllegalArgumentException()), false),
                Arguments.of(failureTry, Optional.of(new IllegalArgumentException()), false),
                Arguments.of(failureTry, null, false),
                Arguments.of(failureTry, successTry, false)
        );
    }

    private static Stream<Arguments> provideToStringCases() {
        return Stream.of(
                Arguments.of(Try.success(2), "Try.Success[2]"),
                Arguments.of(Try.success(null), "Try.Success[null]"),
                Arguments.of(Try.failure(new RuntimeException()), "Try.Failure[java.lang.RuntimeException]")
        );
    }

    @Test
    void test_givenNonNullData_whenSuccessIsCalled_thenSuccessTryIsCreated() {
        String data = "success";
        Try<String> result = Try.success(data);

        assertTrue(result.isSuccess());
        assertSame(data, result.getData());
        assertNull(result.getError());
    }

    @Test
    void test_givenNullData_whenSuccessIsCalled_thenSuccessTryIsCreated() {
        Try<String> result = assertDoesNotThrow(() -> Try.success(null));

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        assertNull(result.getError());
    }

    @Test
    void test_givenNonNullException_whenFailureIsCalled_thenFailureTryIsCreated() {
        Exception error = new RuntimeException("error");
        Try<String> result = Try.failure(error);

        assertTrue(result.isFailure());
        assertNull(result.getData());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenNullException_whenFailureIsCalled_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(NullPointerException.class, () -> Try.failure(null));

        assertNotNull(nullPointerException);
    }

    @Test
    void test_whenToIsCalledWithNullCallable_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(NullPointerException.class, () -> Try.to(null));

        assertNotNull(nullPointerException);
    }

    @Test
    void test_whenToIsCalledWithCallableThatSucceeds_thenSuccessTryIsReturned() {
        Callable<String> callable = () -> "success";
        Try<String> result = Try.to(callable);

        assertTrue(result.isSuccess());
        assertEquals("success", result.getData());
    }

    @Test
    void test_whenToIsCalledWithCallableThatThrowsInterruptedException_thenFailureTryIsReturned() {
        Exception expectedError = new InterruptedException("error");
        Callable<String> callable = () -> {
            throw expectedError;
        };

        Try<String> result = Try.to(callable);

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_whenToIsCalledWithCallableThatThrowsException_thenFailureTryIsReturned() {
        Exception expectedError = new RuntimeException("error");
        Callable<String> callable = () -> {
            throw expectedError;
        };

        Try<String> result = Try.to(callable);

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_givenSuccessTry_whenOrElse_thenOriginalValueShouldBeReturned() {
        Try<Integer> integerTry = Try.success(10);
        int value = integerTry.orElse(5);
        Assertions.assertEquals(10, value);
    }

    @Test
    void test_givenFailureTry_whenOrElse_thenNewValueShouldBeReturned() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));
        int value = failure.orElse(5);
        Assertions.assertEquals(5, value);
    }

    @Test
    void test_whenOrElseGetSupplierCalledOnSuccessTry_thenSupplierIsNotCalled() {
        Try<Integer> success = Try.success(10);
        int value = success.orElseGet(() -> 5);
        Assertions.assertEquals(10, value);
    }

    @Test
    void test_whenOrElseGetSupplierCalledOnFailureTry_thenSupplierIsNotCalled() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));
        int value = failure.orElseGet(() -> 5);
        Assertions.assertEquals(5, value);
    }

    @Test
    void testOrElseGetWithSuccessTry() {
        Try<Integer> success = Try.success(10);
        int value = success.orElseGet(error -> 5);
        Assertions.assertEquals(10, value);
    }

    @Test
    void testOrElseGetWithFailureTry() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));
        int value = failure.orElseGet(error -> 5);
        Assertions.assertEquals(5, value);
    }

    @Test
    void testOrElseThrowWithSuccessTry() {
        Try<Integer> success = Try.success(10);
        int value = assertDoesNotThrow(() -> success.orElseThrow(Exception::new));
        Assertions.assertEquals(10, value);
    }

    @Test
    void testOrElseThrowWithFailureTry() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));
        Exception exception = Assertions.assertThrows(
                Exception.class,
                () -> failure.orElseThrow(e -> new Exception(e.getMessage()))
        );
        Assertions.assertEquals("Error occurred", exception.getMessage());
    }

    @Test
    void test_givenSuccess_whenOrElseThrowCalled_thenReturnValue() {
        Try<Integer> success = Try.success(10);
        int value = assertDoesNotThrow(() -> success.orElseThrow());
        Assertions.assertEquals(10, value);
    }

    @Test
    void test_givenFailure_whenOrElseThrowCalled_thenThrowException() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));
        Exception exception = Assertions.assertThrows(
                RuntimeException.class,
                failure::orElseThrow
        );
        Assertions.assertEquals("Error occurred", exception.getMessage());
    }

    @Test
    void test_givenSuccess_whenToOptionalCalled_thenReturnsOptionalWithSameValue() {
        Try<Integer> success = Try.success(10);

        Optional<Integer> optional = success.toOptional();
        Assertions.assertNotNull(optional);
        Assertions.assertTrue(optional.isPresent());
        Assertions.assertSame(success.getData(), optional.get());
    }

    @Test
    void test_givenFailure_whenToOptionalCalled_thenReturnsEmptyOptional() {
        Try<Integer> failure = Try.failure(new RuntimeException("Error occurred"));

        Optional<Integer> optional = failure.toOptional();
        Assertions.assertNotNull(optional);
        Assertions.assertFalse(optional.isPresent());
    }

    @Test
    void test_givenNullMapper_whenMapIsCalled_thenExceptionIsThrown() {
        Try<Integer> success = Try.success(10);

        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(NullPointerException.class, () -> success.map(null));
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenMapIsCalled_thenMappedSuccessTryIsReturned() {
        Try<Integer> success = Try.success(10);
        Try<String> result = success.map(Object::toString);

        assertTrue(result.isSuccess());
        assertEquals("10", result.getData());
    }

    @Test
    void test_givenFailure_whenMapIsCalled_thenSameFailureIsReturned() {
        Exception error = new RuntimeException("error");
        Try<Integer> failure = Try.failure(error);
        Try<String> result = failure.map(Object::toString);

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenNullMapper_whenOtherwiseIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));

        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.otherwise(null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenFailure_whenOtherwiseIsCalled_thenSuccessTryIsReturned() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.otherwise(e -> "recovered");

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenSuccess_whenOtherwiseIsCalled_thenSameSuccessIsReturned() {
        Try<String> success = Try.success("data");
        Try<String> result = success.otherwise(e -> "ignored");

        assertTrue(result.isSuccess());
        assertSame(success, result);
        assertSame("data", result.getData());
    }

    @Test
    void test_givenNullMapper_whenFlatMapIsCalled_thenExceptionIsThrown() {
        Try<Integer> success = Try.success(10);

        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> success.flatMap(null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenFlatMapIsCalled_thenMappedSuccessTryIsReturned() {
        Try<Integer> success = Try.success(10);
        Try<String> result = success.flatMap(i -> Try.success(i.toString()));

        assertTrue(result.isSuccess());
        assertEquals("10", result.getData());
    }

    @Test
    void test_givenFailure_whenFlatMapIsCalled_thenSameFailureIsReturned() {
        Exception error = new RuntimeException("error");
        Try<Integer> failure = Try.failure(error);
        Try<String> result = failure.flatMap(i -> Try.success(i.toString()));

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenNullMapper_whenRecoverIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover(null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenFailure_whenRecoverIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(e -> Try.success("recovered"));

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenSuccess_whenRecoverIsCalled_thenSameSuccessIsReturned() {
        Try<String> success = Try.success("data");
        Try<String> result = success.recover(e -> Try.success("ignored"));

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void test_givenNullMapper_whenPredicateRecoverIsCalled_thenExceptionIsThrown() {
        Try<String> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover((Predicate<? super Exception>) IllegalArgumentException.class::isInstance, null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenNullPredicate_whenPredicateRecoverIsCalled_thenExceptionIsThrown() {
        Try<String> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover((Predicate<? super Exception>) null, e -> Try.success("ignored"))
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenPredicateRecoverIsCalled_thenSameSuccessIsReturned() {
        Try<String> success = Try.success("data");
        Try<String> result = success.recover(IllegalArgumentException.class::isInstance, e -> Try.success("ignored"));

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void test_givenFailureWhichDoesNotSatisfyThePredicate_whenPredicateRecoverIsCalled_thenSameTryIsReturned() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(IllegalArgumentException.class::isInstance, e -> Try.success("recovered"));

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenFailureWhichSatisfiesThePredicate_whenPredicateRecoverIsCalled_thenSameTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(IllegalArgumentException.class::isInstance, e -> Try.success("recovered"));

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenNullExceptionType_whenRecoverWithExceptionTypeIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover((Class<Exception>) null, e -> Try.success(0))
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenRecoverWithExceptionTypeIsCalled_thenSameSuccessIsReturned() {
        Try<String> success = Try.success("data");
        Try<String> result = success.recover(RuntimeException.class, e -> Try.success("recovered"));

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void test_givenFailureWithNonMatchingException_whenRecoverWithExceptionTypeIsCalled_thenSameFailureIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(IllegalStateException.class, e -> Try.success("recovered"));

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenFailureWithMatchingException_whenRecoverWithExceptionTypeIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(IllegalArgumentException.class, e -> Try.success("recovered"));

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithMatchingExceptionSubclass_whenRecoverWithExceptionTypeIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(RuntimeException.class, e -> Try.success("recovered"));

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithMatchingException_whenRecoverWithExceptionTypeReturnsFailure_thenNewFailureIsReturned() {
        Exception originalError = new IllegalArgumentException("original");
        Exception newError = new IllegalStateException("new error");
        Try<String> failure = Try.failure(originalError);
        Try<String> result = failure.recover(IllegalArgumentException.class, e -> Try.failure(newError));

        assertTrue(result.isFailure());
        assertSame(newError, result.getError());
    }

    @Test
    void test_givenNullMapper_whenTransformIsCalled_thenExceptionIsThrown() {
        Try<Integer> success = Try.success(10);

        @SuppressWarnings("DataFlowIssue")
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> success.transform(null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenTry_whenTransformIsCalled_thenTransformedTryIsReturned() {
        Try<Integer> success = Try.success(10);
        Try<String> transformed = success.transform(t -> t.map(Object::toString));

        assertFalse(transformed.isFailure());
        assertEquals("10", transformed.getData());
    }

    @Test
    void test_givenNonNullConsumer_whenOnHandleIsCalled_thenConsumerIsExecuted() {
        Try<String> stringTry = Try.success("data");

        List<Try<String>> consumedTryList = new ArrayList<>();
        Try<String> returnedTry = stringTry.onHandle(consumedTryList::add);

        assertSame(stringTry, returnedTry);
        assertFalse(consumedTryList.isEmpty());
        assertEquals(1, consumedTryList.size());
        assertSame(stringTry, consumedTryList.get(0));
    }

    @Test
    void test_givenNullConsumer_whenOnHandleIsCalled_thenNoExceptionIsThrown() {
        Try<String> stringTry = Try.success("data");

        Try<String> returnedTry = assertDoesNotThrow(() -> stringTry.onHandle(null));
        assertSame(stringTry, returnedTry);
    }

    @Test
    void test_givenNullConsumer_whenOnSuccessIsCalled_thenDoesNotThrow() {
        Try<String> success = Try.success("data");

        Try<String> returnedTry = assertDoesNotThrow(() -> success.onSuccess(null));
        assertSame(success, returnedTry);
    }

    @Test
    void test_givenSuccess_whenOnSuccessIsCalled_thenConsumerIsExecuted() {
        Try<String> success = Try.success("data");
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = success.onSuccess(consumed::append);

        assertSame(success, returnedTry);
        assertEquals("data", consumed.toString());
    }

    @Test
    void test_givenNullConsumer_whenOnSuccessIsCalled_thenConsumerIsExecuted() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);

        Try<String> returnedTry = assertDoesNotThrow(() -> failure.onSuccess(null));
        assertSame(failure, returnedTry);
    }

    @Test
    void test_givenFailure_whenOnSuccessIsCalled_thenConsumerIsExecuted() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onSuccess(consumed::append);

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailure_whenOnFailureIsCalled_thenConsumerIsExecuted() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenSuccess_whenOnFailureIsCalled_thenConsumerIsNotExecuted() {
        Try<String> success = Try.success("data");
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = success.onFailure(e -> consumed.append(e.getMessage()));

        assertSame(success, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenNullPredicate_whenPredicateOnFailureIsCalled_thenThrowsException() {
        Try<String> failure = Try.failure(new RuntimeException("error"));
        StringBuilder consumed = new StringBuilder();
        Assertions.assertThrows(
                NullPointerException.class,
                () -> failure.onFailure((Predicate<? super Exception>) null, e -> consumed.append(e.getMessage()))
        );
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenSuccess_whenPredicateOnFailureIsCalled_thenConsumerIsNotExecuted() {
        Try<String> success = Try.success("data");
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = success
                .onFailure(IllegalArgumentException.class::isInstance, e -> consumed.append(e.getMessage()));

        assertSame(success, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWhichDoesNotSatisfyPredicate_whenPredicateOnFailureIsCalled_thenConsumerIsNotExecuted() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure
                .onFailure(IllegalArgumentException.class::isInstance, e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWhichSatisfiesPredicate_whenPredicateOnFailureIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure
                .onFailure(IllegalArgumentException.class::isInstance, e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    // ========== Tests for single exception type onFailure method ==========

    @Test
    void test_givenNullExceptionType_whenOnFailureWithExceptionTypeIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.onFailure((Class<Exception>) null, e -> {
                })
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenOnFailureWithExceptionTypeIsCalled_thenConsumerIsNotExecuted() {
        Try<String> success = Try.success("data");
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = success.onFailure(RuntimeException.class, e -> consumed.append(e.getMessage()));

        assertSame(success, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWithNonMatchingException_whenOnFailureWithExceptionTypeIsCalled_thenConsumerIsNotExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(IllegalStateException.class, e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWithMatchingException_whenOnFailureWithExceptionTypeIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure
                .onFailure(IllegalArgumentException.class, e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureWithMatchingExceptionSubclass_whenOnFailureWithExceptionTypeIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(RuntimeException.class, e -> consumed.append(e.getMessage()));

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenChainedOnFailureCallsWithDifferentTypes_whenFailureMatches_thenOnlyMatchingConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("validation error");
        Try<String> failure = Try.failure(error);
        List<String> log = new ArrayList<>();

        Try<String> result = failure
                .onFailure(IllegalStateException.class, e -> log.add("state error"))
                .onFailure(IllegalArgumentException.class, e -> log.add("argument error"))
                .onFailure(NullPointerException.class, e -> log.add("null error"));

        assertSame(failure, result);
        assertEquals(1, log.size());
        assertEquals("argument error", log.get(0));
    }

    @Test
    void test_givenChainedOnFailureWithCatchAll_whenFailureOccurs_thenAllMatchingConsumersAreExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        List<String> log = new ArrayList<>();

        Try<String> result = failure
                .onFailure(IllegalArgumentException.class, e -> log.add("specific"))
                .onFailure(RuntimeException.class, e -> log.add("general"))
                .onFailure(e -> log.add("catch-all"));

        assertSame(failure, result);
        assertEquals(3, log.size());
        assertEquals("specific", log.get(0));
        assertEquals("general", log.get(1));
        assertEquals("catch-all", log.get(2));
    }

    // ========== Tests for varargs exception types onFailure method ==========

    @Test
    void test_givenNullExceptionTypesArray_whenOnFailureWithVarargsIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.onFailure(e -> {
                }, (Class<? extends Exception>[]) null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenOnFailureWithVarargsIsCalled_thenConsumerIsNotExecuted() {
        Try<String> success = Try.success("data");
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = success.onFailure(
                e -> consumed.append(e.getMessage()),
                RuntimeException.class,
                IllegalArgumentException.class
        );

        assertSame(success, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWithNoMatchingException_whenOnFailureWithVarargsIsCalled_thenConsumerIsNotExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                IllegalStateException.class,
                NullPointerException.class
        );

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWithEmptyVarargsArray_whenOnFailureWithVarargsIsCalled_thenConsumerIsNotExecuted() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                new Class[0]
        );

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenFailureWithSingleMatchingException_whenOnFailureWithVarargsIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                IllegalArgumentException.class
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureWithFirstMatchingException_whenOnFailureWithVarargsIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureWithSecondMatchingException_whenOnFailureWithVarargsIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalStateException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureWithLastMatchingException_whenOnFailureWithVarargsIsCalled_thenConsumerIsExecuted() {
        Exception error = new NullPointerException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureWithMatchingExceptionSubclass_whenOnFailureWithVarargsIsCalled_thenConsumerIsExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                RuntimeException.class,
                Exception.class
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureAndNullExceptionTypesInVarargs_whenOnFailureWithVarargsIsCalled_thenNullsAreIgnored() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                null,
                IllegalArgumentException.class,
                null
        );

        assertSame(failure, returnedTry);
        assertEquals("error", consumed.toString());
    }

    @Test
    void test_givenFailureAndAllNullExceptionTypesInVarargs_whenOnFailureWithVarargsIsCalled_thenConsumerIsNotExecuted() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        StringBuilder consumed = new StringBuilder();
        Try<String> returnedTry = failure.onFailure(
                e -> consumed.append(e.getMessage()),
                null,
                null
        );

        assertSame(failure, returnedTry);
        assertEquals(0, consumed.length());
    }

    @Test
    void test_givenMultipleIOExceptions_whenOnFailureWithVarargsIsCalled_thenSingleHandlerIsExecuted() {
        Exception error = new java.io.FileNotFoundException("file not found");
        Try<String> failure = Try.failure(error);
        List<String> log = new ArrayList<>();
        Try<String> returnedTry = failure.onFailure(
                e -> log.add("I/O error: " + e.getMessage()),
                java.io.IOException.class,
                java.io.FileNotFoundException.class,
                java.net.SocketTimeoutException.class
        );

        assertSame(failure, returnedTry);
        assertEquals(1, log.size());
        assertEquals("I/O error: file not found", log.get(0));
    }

    @Test
    void test_givenChainedOnFailureCallsWithVarargs_whenDifferentExceptionGroups_thenCorrectHandlerIsExecuted() {
        Exception error = new IllegalArgumentException("validation error");
        Try<String> failure = Try.failure(error);
        List<String> log = new ArrayList<>();

        Try<String> result = failure
                .onFailure(
                        e -> log.add("io"),
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class
                )
                .onFailure(
                        e -> log.add("validation"),
                        IllegalArgumentException.class,
                        IllegalStateException.class
                );

        assertSame(failure, result);
        assertEquals(1, log.size());
        assertEquals("validation", log.get(0));
    }

    @Test
    void test_givenRealWorldLoggingScenario_whenOnFailureWithMultipleTypes_thenCorrectLoggingOccurs() {
        Exception error = new java.io.IOException("network error");
        Try<String> failure = Try.failure(error);
        List<String> logMessages = new ArrayList<>();

        Try<String> result = failure
                .onFailure(java.io.IOException.class, e -> logMessages.add("ERROR: " + e.getMessage()))
                .onFailure(java.util.concurrent.TimeoutException.class, e -> logMessages.add("WARN: " + e.getMessage()))
                .onFailure(e -> logMessages.add("UNKNOWN: " + e.getMessage()));

        assertSame(failure, result);
        assertEquals(2, logMessages.size());
        assertEquals("ERROR: network error", logMessages.get(0));
        assertEquals("UNKNOWN: network error", logMessages.get(1));
    }

    @Test
    void test_givenSuccess_whenToResultIsCalled_thenResultIsReturned() {
        Try<String> success = Try.success("data");
        Result<String, Exception> result = success.toResult();

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void test_givenFailure_whenToResultIsCalled_thenResultIsReturned() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        Result<String, Exception> result = failure.toResult();

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    // ========== Tests for varargs recover method ==========

    @Test
    void test_givenNullFailureMapper_whenRecoverWithVarargsIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover(null, RuntimeException.class, IllegalArgumentException.class)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenNullExceptionTypesArray_whenRecoverWithVarargsIsCalled_thenExceptionIsThrown() {
        Try<Integer> failure = Try.failure(new RuntimeException("error"));
        NullPointerException nullPointerException = assertThrows(
                NullPointerException.class,
                () -> failure.recover(e -> Try.success(0), (Class<? extends Exception>[]) null)
        );
        assertNotNull(nullPointerException);
    }

    @Test
    void test_givenSuccess_whenRecoverWithVarargsIsCalled_thenSameSuccessIsReturned() {
        Try<String> success = Try.success("data");
        Try<String> result = success.recover(
                e -> Try.success("recovered"),
                RuntimeException.class,
                IllegalArgumentException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("data", result.getData());
    }

    @Test
    void test_givenFailureWithNoMatchingException_whenRecoverWithVarargsIsCalled_thenSameFailureIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                IllegalStateException.class,
                NullPointerException.class
        );

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenFailureWithEmptyVarargsArray_whenRecoverWithVarargsIsCalled_thenSameFailureIsReturned() {
        Exception error = new RuntimeException("error");
        Try<String> failure = Try.failure(error);
        @SuppressWarnings("unchecked")
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                new Class[0] // Explicitly pass empty array to call varargs version
        );

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenFailureWithSingleMatchingException_whenRecoverWithVarargsIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                IllegalArgumentException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithFirstMatchingException_whenRecoverWithVarargsIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithSecondMatchingException_whenRecoverWithVarargsIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalStateException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithLastMatchingException_whenRecoverWithVarargsIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new NullPointerException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithMatchingExceptionSubclass_whenRecoverWithVarargsIsCalled_thenRecoveredTryIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                RuntimeException.class,
                Exception.class
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureWithMatchingException_whenRecoverWithVarargsReturnsFailure_thenNewFailureIsReturned() {
        Exception originalError = new IllegalArgumentException("original");
        Exception newError = new IllegalStateException("new error");
        Try<String> failure = Try.failure(originalError);
        Try<String> result = failure.recover(
                e -> Try.failure(newError),
                IllegalArgumentException.class,
                NullPointerException.class
        );

        assertTrue(result.isFailure());
        assertSame(newError, result.getError());
    }

    @Test
    void test_givenFailureAndNullExceptionTypesInVarargs_whenRecoverWithVarargsIsCalled_thenNullsAreIgnored() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                null,
                IllegalArgumentException.class,
                null
        );

        assertTrue(result.isSuccess());
        assertEquals("recovered", result.getData());
    }

    @Test
    void test_givenFailureAndAllNullExceptionTypesInVarargs_whenRecoverWithVarargsIsCalled_thenSameFailureIsReturned() {
        Exception error = new IllegalArgumentException("error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("recovered"),
                null,
                null
        );

        assertTrue(result.isFailure());
        assertSame(error, result.getError());
    }

    @Test
    void test_givenMultipleIOExceptions_whenRecoverWithVarargsIsCalled_thenSingleHandlerIsApplied() {
        Exception error = new java.io.FileNotFoundException("file not found");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure.recover(
                e -> Try.success("default content"),
                java.io.IOException.class,
                java.io.FileNotFoundException.class,
                java.net.SocketTimeoutException.class
        );

        assertTrue(result.isSuccess());
        assertEquals("default content", result.getData());
    }

    @Test
    void test_givenChainedRecoverCallsWithVarargs_whenDifferentExceptionGroups_thenCorrectHandlerIsApplied() {
        Exception error = new IllegalArgumentException("validation error");
        Try<String> failure = Try.failure(error);
        Try<String> result = failure
                .recover(
                        e -> Try.success("io recovered"),
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class
                )
                .recover(
                        e -> Try.success("validation recovered"),
                        IllegalArgumentException.class,
                        IllegalStateException.class
                );

        assertTrue(result.isSuccess());
        assertEquals("validation recovered", result.getData());
    }

    @MethodSource("provideHashcodeCoverageCases")
    @ParameterizedTest
    void test_givenTry_whenHashcodeIsInvoked_thenRespectiveOutcomeIsReturned(Try<?> tryObject, int expectedHashcode) {
        int actualHashcode = tryObject.hashCode();
        Assertions.assertEquals(expectedHashcode, actualHashcode);
    }

    @MethodSource("provideEqualityCases")
    @ParameterizedTest
    void test_givenTry_whenEqualsIsInvoked_thenRespectiveOutcomeIsReturned(
            Try<?> tryObject,
            Object object,
            boolean expectedIsEqual
    ) {
        boolean actualIsEquals = tryObject.equals(object);
        Assertions.assertEquals(expectedIsEqual, actualIsEquals);
    }

    @MethodSource("provideToStringCases")
    @ParameterizedTest
    void test_givenTry_whenToStringIsInvoked_thenRespectiveOutcomeIsReturned(
            Try<?> tryObject,
            String expectedToString
    ) {
        String actualToString = tryObject.toString();
        Assertions.assertEquals(expectedToString, actualToString);
    }

    @Test
    void test_whenRunIsCalledWithNullRunnable_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(NullPointerException.class, () -> Try.run(null));
        assertNotNull(exception);
    }

    @Test
    void test_whenRunIsCalledWithRunnableThatSucceeds_thenSuccessTryWithEmptyIsReturned() {
        List<String> sideEffect = new ArrayList<>();

        Try<Empty> result = Try.run(() -> sideEffect.add("executed"));

        assertTrue(result.isSuccess());
        assertSame(Empty.getInstance(), result.getData());
        assertEquals(1, sideEffect.size());
        assertEquals("executed", sideEffect.get(0));
    }

    @Test
    void test_whenRunIsCalledWithRunnableThatThrowsException_thenFailureTryIsReturned() {
        RuntimeException expectedError = new RuntimeException("run failed");

        Try<Empty> result = Try.run(() -> {
            throw expectedError;
        });

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_whenRunIsCalledWithRunnableThatThrowsInterruptedException_thenFailureTryIsReturned() {
        try {
            InterruptedException expectedError = new InterruptedException("interrupted");

            Try<Empty> result = Try.run(() -> {
                throw expectedError;
            });

            assertTrue(result.isFailure());
            assertSame(expectedError, result.getError());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void test_whenWithResourcesIsCalledWithNullSupplier_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.withResources(null, r -> "result")
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenWithResourcesIsCalledWithNullAction_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.withResources(TestResource::new, null)
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenWithResourcesSucceeds_thenSuccessTryIsReturnedAndResourceIsClosed() {
        TestResource resource = new TestResource();

        Try<String> result = Try.withResources(() -> resource, r -> "success");

        assertTrue(result.isSuccess());
        assertEquals("success", result.getData());
        assertTrue(resource.isClosed());
    }

    @Test
    void test_whenWithResourcesSupplierThrowsException_thenFailureTryIsReturned() {
        RuntimeException expectedError = new RuntimeException("supplier failed");

        Try<String> result = Try.withResources(
                () -> {
                    throw expectedError;
                },
                r -> "success"
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_whenWithResourcesActionThrowsException_thenFailureTryIsReturnedAndResourceIsClosed() {
        TestResource resource = new TestResource();
        RuntimeException expectedError = new RuntimeException("action failed");

        Try<String> result = Try.withResources(
                () -> resource,
                r -> {
                    throw expectedError;
                }
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
        assertTrue(resource.isClosed());
    }

    @Test
    void test_whenWithResourcesActionThrowsInterruptedException_thenFailureTryIsReturned() {
        try {
            TestResource resource = new TestResource();
            InterruptedException expectedError = new InterruptedException("interrupted");

            Try<String> result = Try.withResources(
                    () -> resource,
                    r -> {
                        throw expectedError;
                    }
            );

            assertTrue(result.isFailure());
            assertSame(expectedError, result.getError());
            assertTrue(resource.isClosed());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void test_whenWithResourcesTwoIsCalledWithNullFirstSupplier_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.withResources(null, TestResource::new, (r1, r2) -> "result")
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenWithResourcesTwoIsCalledWithNullSecondSupplier_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.withResources(TestResource::new, null, (r1, r2) -> "result")
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenWithResourcesTwoIsCalledWithNullAction_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.withResources(TestResource::new, TestResource::new, null)
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenWithResourcesTwoSucceeds_thenSuccessTryIsReturnedAndBothResourcesAreClosed() {
        TestResource resource1 = new TestResource();
        TestResource resource2 = new TestResource();

        Try<String> result = Try.withResources(
                () -> resource1,
                () -> resource2,
                (r1, r2) -> "success"
        );

        assertTrue(result.isSuccess());
        assertEquals("success", result.getData());
        assertTrue(resource1.isClosed());
        assertTrue(resource2.isClosed());
    }

    @Test
    void test_whenWithResourcesTwoFirstSupplierThrows_thenFailureTryIsReturned() {
        RuntimeException expectedError = new RuntimeException("first supplier failed");

        Try<String> result = Try.withResources(
                () -> {
                    throw expectedError;
                },
                TestResource::new,
                (r1, r2) -> "success"
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_whenWithResourcesTwoSecondSupplierThrows_thenFailureTryIsReturnedAndFirstResourceIsClosed() {
        TestResource resource1 = new TestResource();
        RuntimeException expectedError = new RuntimeException("second supplier failed");

        Try<String> result = Try.withResources(
                () -> resource1,
                () -> {
                    throw expectedError;
                },
                (r1, r2) -> "success"
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
        assertTrue(resource1.isClosed());
    }

    @Test
    void test_whenWithResourcesTwoActionThrows_thenFailureTryIsReturnedAndBothResourcesAreClosed() {
        TestResource resource1 = new TestResource();
        TestResource resource2 = new TestResource();
        RuntimeException expectedError = new RuntimeException("action failed");

        Try<String> result = Try.withResources(
                () -> resource1,
                () -> resource2,
                (r1, r2) -> {
                    throw expectedError;
                }
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
        assertTrue(resource1.isClosed());
        assertTrue(resource2.isClosed());
    }

    @Test
    void test_whenWithResourcesTwoActionThrowsInterruptedException_thenFailureTryIsReturned() {
        try {
            TestResource resource1 = new TestResource();
            TestResource resource2 = new TestResource();
            InterruptedException expectedError = new InterruptedException("interrupted");

            Try<String> result = Try.withResources(
                    () -> resource1,
                    () -> resource2,
                    (r1, r2) -> {
                        throw expectedError;
                    }
            );

            assertTrue(result.isFailure());
            assertSame(expectedError, result.getError());
            assertTrue(resource1.isClosed());
            assertTrue(resource2.isClosed());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void test_whenConsumeResourceIsCalledWithNullSupplier_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.consumeResource(null, r -> {
                })
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenConsumeResourceIsCalledWithNullAction_thenExceptionIsThrown() {
        @SuppressWarnings("DataFlowIssue")
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> Try.consumeResource(TestResource::new, null)
        );
        assertNotNull(exception);
    }

    @Test
    void test_whenConsumeResourceSucceeds_thenSuccessTryWithEmptyIsReturnedAndResourceIsClosed() {
        TestResource resource = new TestResource();
        List<String> sideEffect = new ArrayList<>();

        Try<Empty> result = Try.consumeResource(
                () -> resource,
                r -> sideEffect.add("consumed")
        );

        assertTrue(result.isSuccess());
        assertSame(Empty.getInstance(), result.getData());
        assertTrue(resource.isClosed());
        assertEquals(1, sideEffect.size());
    }

    @Test
    void test_whenConsumeResourceSupplierThrows_thenFailureTryIsReturned() {
        RuntimeException expectedError = new RuntimeException("supplier failed");

        Try<Empty> result = Try.consumeResource(
                () -> {
                    throw expectedError;
                },
                r -> {
                }
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
    }

    @Test
    void test_whenConsumeResourceActionThrows_thenFailureTryIsReturnedAndResourceIsClosed() {
        TestResource resource = new TestResource();
        RuntimeException expectedError = new RuntimeException("action failed");

        Try<Empty> result = Try.consumeResource(
                () -> resource,
                r -> {
                    throw expectedError;
                }
        );

        assertTrue(result.isFailure());
        assertSame(expectedError, result.getError());
        assertTrue(resource.isClosed());
    }

    @Test
    void test_whenConsumeResourceActionThrowsInterruptedException_thenFailureTryIsReturned() {
        try {
            TestResource resource = new TestResource();
            InterruptedException expectedError = new InterruptedException("interrupted");

            Try<Empty> result = Try.consumeResource(
                    () -> resource,
                    r -> {
                        throw expectedError;
                    }
            );

            assertTrue(result.isFailure());
            assertSame(expectedError, result.getError());
            assertTrue(resource.isClosed());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static class TestResource implements AutoCloseable {
        private boolean closed = false;

        @Override
        public void close() {
            closed = true;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
