package org.zeplinko.commons.lang.ext.util.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

    private static final int CONCURRENT_VALIDATIONS_PER_INPUT = 20;

    // P001: builder(Class<T>).

    @Test
    void givenPersonClassWhenBuildingValidatorThenCreatesTypedValidator() {
        Validator<Person> validator = Validator.builder(Person.class).build();

        assertNotNull(validator);
        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullClassTokenWhenCreatingBuilderThenRejectsConfiguration() {
        assertThrows(RuntimeException.class, () -> Validator.<Person>builder(null).build());
    }

    // P002: build().

    @Test
    void givenBuilderChangesAfterBuildWhenValidatingThenEarlierValidatorKeepsOriginalRules() {
        Validator.Builder<Person> builder = Validator.builder(Person.class);
        builder.rule(person -> false);
        Validator<Person> first = builder.build();

        builder.rule(person -> false);
        Validator<Person> second = builder.build();

        List<ValidationError> firstErrors = first.validate(new Person());
        List<ValidationError> secondErrors = second.validate(new Person());
        assertEquals(1, firstErrors.size());
        assertEquals(2, secondErrors.size());
    }

    // P003: validate(T). Field traversal checks are in the P014 section below.

    @Test
    void givenPassingAndFailingRulesWhenValidatingThenCollectsEveryApplicableFailureAtRoot() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("first", "First rule failed", person -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .rule("passing", "Passing rule", person -> {
                    evaluations.incrementAndGet();
                    return true;
                })
                .rule("last", "Last rule failed", person -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(3, evaluations.get());
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().anyMatch(error -> "first".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "last".equals(error.getCode())));
    }

    @Test
    void givenRepeatedInputsWhenValidatingThenReturnsIndependentImmutableErrorLists() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> person.name != null)
                .build();

        List<ValidationError> first = validator.validate(new Person());
        Person namedPerson = new Person();
        namedPerson.name = "Ada";
        List<ValidationError> second = validator.validate(namedPerson);
        List<ValidationError> third = validator.validate(new Person());

        assertNotSame(first, second);
        assertNotSame(first, third);
        assertSingleError(first, "name.required", "Name is required");
        assertTrue(second.isEmpty());
        assertSingleError(third, "name.required", "Name is required");
        ValidationError replacement = new ValidationError("#", "age.required", "Age is required");
        assertThrows(UnsupportedOperationException.class, () -> first.add(replacement));
        assertThrows(UnsupportedOperationException.class, () -> first.set(0, replacement));
        assertThrows(UnsupportedOperationException.class, () -> first.remove(0));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> second.add(replacement));
    }

    @Test
    void givenSharedIncludedValidatorsWhenValidatingConcurrentlyThenKeepsRootAndNestedResultsIsolatedAndImmutable()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                null,
                new Person(null),
                new Person(new Address(null)),
                new Person(new Address("Main Street"))
        );
        String[][] expectedErrors = {
                { "# [notNull]", "#.address [validation.skipped]" },
                { "#.address [notNull]", "#.address.streetName [validation.skipped]" },
                { "#.address.streetName [notNull]" },
                {}
        };
        CyclicBarrier validationBarrier = new CyclicBarrier(inputs.size());
        Validator<Address> sharedAddress = Validator.builder(Address.class)
                .notNull()
                .field("streetName", Address::getStreetName, street -> street.notNull())
                .build();
        Validator<Person> sharedPerson = Validator.builder(Person.class)
                .notNull()
                .field("address", Person::getAddress, address -> address.include(sharedAddress))
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    awaitConcurrentValidation(validationBarrier);
                    return true;
                })
                .include(sharedPerson)
                .rule(person -> {
                    awaitConcurrentValidation(validationBarrier);
                    return true;
                })
                .build();

        assertConcurrentResults(validator, inputs, expectedErrors);
    }

    @Test
    void givenNoRulesWhenValidatingNullRootThenReturnsImmutableEmptyErrorList() {
        Validator<Person> validator = Validator.builder(Person.class).build();

        List<ValidationError> errors = validator.validate(null);

        assertTrue(errors.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> errors.add(new ValidationError("#", "person.required", "Person is required"))
        );
    }

    @Test
    void givenNullRootWhenRulesAreNullAwareThenReportsOnlyUnsatisfiedRuleAtRoot() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person.required", "Person is required", Objects::nonNull)
                .rule("person.absent", "Person must be absent", Objects::isNull)
                .build();

        assertSingleError(validator.validate(null), "person.required", "Person is required");
    }

    @Test
    void givenPredicateThrowsRuntimeExceptionWhenValidatingThenRecordsFailureAndContinuesIndependentRules() {
        AtomicInteger independentRuleEvaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> {
                    throw new IllegalStateException("broken predicate");
                })
                .rule("age.invalid", "Age is invalid", person -> {
                    independentRuleEvaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, independentRuleEvaluations.get());
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing execution failure"));
        assertTrue(executionError.getMessage().contains(IllegalStateException.class.getName()));
        assertTrue(executionError.getMessage().contains("broken predicate"));
        assertTrue(errors.stream().anyMatch(error -> "age.invalid".equals(error.getCode())));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "broken predicate" })
    void givenAnonymousPredicateExceptionWhenValidatingThenReportsBinaryClassNameAtRootAndContinuesIndependentRules(
            String message
    ) {
        RuntimeException failure = new RuntimeException(message) {
            private static final long serialVersionUID = 1L;
        };
        AtomicInteger independentRules = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    throw failure;
                })
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(() -> validator.validate(new Person()));

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [person.invalid]");
        assertEquals(1, independentRules.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing execution failure"));
        assertExceptionDetails(executionError, failure.getClass(), message);
    }

    @Test
    void givenPredicateExceptionWithThrowingMessageWhenValidatingThenRecordsFailureAndContinuesIndependentRules() {
        AtomicInteger independentRuleEvaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    throw new ThrowingMessageException();
                })
                .rule("independent.invalid", person -> {
                    independentRuleEvaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(() -> validator.validate(new Person()));

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [independent.invalid]");
        assertEquals(1, independentRuleEvaluations.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing execution failure"));
        assertNonblankMessage(executionError);
        assertTrue(executionError.getMessage().contains(ThrowingMessageException.class.getName()));
    }

    @Test
    void givenNullRootWhenPredicateDereferencesItThenRecordsExecutionFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> person.name != null)
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains(NullPointerException.class.getName()));
    }

    @Test
    void givenPredicateThrowsWithoutMessageWhenValidatingThenRecordsExceptionClassAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule(person -> {
                    throw new IllegalStateException();
                })
                .build()
                .validate(new Person());

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains(IllegalStateException.class.getName()));
    }

    @Test
    void givenTwoThrowingPredicatesWhenValidatingThenCollectsBothExecutionFailuresAtRoot() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    throw new IllegalStateException("first failure");
                })
                .rule(person -> {
                    throw new IllegalArgumentException("second failure");
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().allMatch(error -> "validation.executionFailed".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("first failure")));
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("second failure")));
    }

    @Test
    void givenPredicateThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal failure");
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    throw failure;
                })
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person())));
    }

    // P005: notNull().

    @Test
    void givenNullRootWhenRootNotNullUsesDefaultsThenReportsNotNullCodeAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .notNull()
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "notNull");
        assertNonblankMessage(errors.get(0));
    }

    @Test
    void givenNonNullRootWhenRootNotNullUsesDefaultsThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class).notNull().build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullRootAndAnotherFailingRuleWhenUsingDefaultRootNotNullThenCollectsBothErrorsAtRoot() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .rule("person.invalid", "Person is invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().anyMatch(error -> "notNull".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "person.invalid".equals(error.getCode())));
    }

    // P006: notNull(code).

    @Test
    void givenNullRootWhenRootNotNullHasCustomCodeThenReportsCodeAndDefaultMessageAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .notNull("person.required")
                .build()
                .validate(null);
        List<ValidationError> defaultErrors = Validator.builder(Person.class).notNull().build().validate(null);

        assertSingleErrorWithCode(errors, "person.required");
        assertNonblankMessage(errors.get(0));
        assertEquals(defaultErrors.get(0).getMessage(), errors.get(0).getMessage());
    }

    @Test
    void givenNonNullRootWhenRootNotNullHasCustomCodeThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class).notNull("person.required").build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullRootAndIndependentFailingRuleWhenUsingCustomNotNullCodeThenCollectsBothErrorsAtRoot() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required")
                .rule(person -> false)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().anyMatch(error -> "person.required".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "rule.unsatisfied".equals(error.getCode())));
    }

    @Test
    void givenNullOrBlankCodeWhenAddingRootNotNullWithCodeThenRejectsConfiguration() {
        for (String code : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class).notNull(code).build()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "\u2003", "\u3000", "\t \u2003\u3000\r\n" })
    void givenUnicodeWhitespaceOnlyCodeWhenAddingRootNotNullWithCodeThenRejectsConfiguration(String code) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).notNull(code).build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { " person.required ", "\u2003person.required\u3000", "person required" })
    void givenNonblankCodeContainingWhitespaceWhenAddingRootNotNullWithCodeThenPreservesExplicitCode(String code) {
        Validator<Person> validator = assertDoesNotThrow(
                () -> Validator.builder(Person.class).notNull(code).build()
        );

        assertSingleErrorWithCode(validator.validate(null), code);
    }

    // P007: notNull(code, message). Attributes are introduced in P011.

    @Test
    void givenNullRootWhenRootNotNullHasOverridesThenReportsCustomMetadataAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .build()
                .validate(null);

        assertSingleError(errors, "person.required", "Person is required");
    }

    @Test
    void givenNonNullRootWhenRootNotNullHasOverridesThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullRootAndIndependentFailingRuleWhenUsingNotNullOverridesThenCollectsBothErrorsAtRoot() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .rule("name.required", "Name is required", person -> person != null)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().anyMatch(error -> "person.required".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "name.required".equals(error.getCode())));
    }

    @Test
    void givenNullOrBlankCodeWhenAddingRootNotNullWithOverridesThenRejectsConfiguration() {
        for (String code : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class).notNull(code, "Person is required").build()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "\u2003", "\u3000", "\t \u2003\u3000\r\n" })
    void givenUnicodeWhitespaceOnlyCodeWhenAddingRootNotNullWithOverridesThenRejectsConfiguration(String code) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).notNull(code, "Person is required").build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { " person.required ", "\u2003person.required\u3000", "person required" })
    void givenNonblankCodeContainingWhitespaceWhenAddingRootNotNullWithOverridesThenPreservesExplicitMetadata(
            String code
    ) {
        Validator<Person> validator = assertDoesNotThrow(
                () -> Validator.builder(Person.class).notNull(code, "Person is required").build()
        );

        assertSingleError(validator.validate(null), code, "Person is required");
    }

    @Test
    void givenNullMessageWhenAddingRootNotNullWithOverridesThenRejectsConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).notNull("person.required", null).build()
        );
    }

    @Test
    void givenBlankMessageWhenAddingRootNotNullWithOverridesThenPreservesExplicitMessage() {
        for (String message : new String[] { "", "   " }) {
            List<ValidationError> errors = Validator.builder(Person.class)
                    .notNull("person.required", message)
                    .build()
                    .validate(null);

            assertSingleError(errors, "person.required", message);
        }
    }

    // P008: rule(predicate).

    @Test
    void givenPredicateOnlyRuleWhenPredicateFailsThenReportsDefaultMetadataAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule(person -> person.name != null)
                .build()
                .validate(new Person());

        assertSingleErrorWithCode(errors, "rule.unsatisfied");
        assertNonblankMessage(errors.get(0));
    }

    @Test
    void givenPredicateOnlyRuleWhenPredicatePassesThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenPredicateOnlyRuleReturnsFalseThenReportsOrdinaryFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule(Objects::nonNull)
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "rule.unsatisfied");
    }

    @Test
    void givenNullRootWhenPredicateOnlyRuleReturnsTrueThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class).rule(Objects::isNull).build();

        assertTrue(validator.validate(null).isEmpty());
    }

    @Test
    void givenNullRootWhenPredicateOnlyRuleDereferencesItThenReportsExecutionFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule(value -> value.name != null)
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains(NullPointerException.class.getName()));
    }

    @Test
    void givenTwoFailingPredicateOnlyRulesWhenValidatingThenReportsBothAtRootWithoutDeduplication() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(value -> false)
                .rule(value -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "#".equals(error.getPath())));
        assertTrue(errors.stream().allMatch(error -> "rule.unsatisfied".equals(error.getCode())));
    }

    @Test
    void givenSupertypePredicateWhenAddingPredicateOnlyRuleThenValidatesSubtypeAtCurrentNode() {
        Predicate<Object> present = Objects::nonNull;
        Validator<String> validator = Validator.builder(String.class).rule(present).build();

        assertTrue(validator.validate("Ada").isEmpty());
        assertSingleErrorWithCode(validator.validate(null), "rule.unsatisfied");
    }

    @Test
    void givenNullPredicateWhenAddingPredicateOnlyRuleThenRejectsConfiguration() {
        assertThrows(RuntimeException.class, () -> Validator.builder(Person.class).rule(null).build());
    }

    // P009: rule(code, predicate). The string supplies metadata, never a path.

    @Test
    void givenCustomCodeWhenTwoArgumentRuleFailsThenReportsCodeAndDefaultMessageAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", person -> person.name != null)
                .build()
                .validate(new Person());
        List<ValidationError> defaultErrors = Validator.builder(Person.class)
                .rule(person -> false)
                .build()
                .validate(new Person());

        assertSingleErrorWithCode(errors, "name.required");
        assertNonblankMessage(errors.get(0));
        assertEquals(defaultErrors.get(0).getMessage(), errors.get(0).getMessage());
    }

    @Test
    void givenPassingTwoArgumentRuleWhenValidatingThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name.required", value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenTwoArgumentRuleReturnsFalseThenReportsCustomCodeAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("person.required", Objects::nonNull)
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "person.required");
        assertNonblankMessage(errors.get(0));
    }

    @Test
    void givenNullRootWhenTwoArgumentRuleReturnsTrueThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class).rule("person.absent", Objects::isNull).build();

        assertTrue(validator.validate(null).isEmpty());
    }

    @Test
    void givenThrowingTwoArgumentRuleWhenValidatingThenReportsExecutionFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", value -> {
                    throw new IllegalStateException("lookup failed");
                })
                .build()
                .validate(new Person());

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains(IllegalStateException.class.getName()));
        assertTrue(errors.get(0).getMessage().contains("lookup failed"));
    }

    @Test
    void givenNullOrBlankCodeWhenAddingTwoArgumentRuleThenRejectsConfiguration() {
        for (String code : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class).rule(code, person -> true).build()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "\u2003", "\u3000", "\t \u2003\u3000\r\n" })
    void givenUnicodeWhitespaceOnlyCodeWhenAddingTwoArgumentRuleThenRejectsConfiguration(String code) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule(code, person -> true).build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { " person.required ", "\u2003person.required\u3000", "person required" })
    void givenNonblankCodeContainingWhitespaceWhenAddingTwoArgumentRuleThenPreservesExplicitCode(String code) {
        Validator<Person> validator = assertDoesNotThrow(
                () -> Validator.builder(Person.class).rule(code, person -> false).build()
        );

        assertSingleErrorWithCode(validator.validate(new Person()), code);
    }

    @Test
    void givenSupertypePredicateWhenAddingCodeOnlyRuleThenPreservesCodeAndValidatesSubtype() {
        Predicate<CharSequence> nonEmpty = value -> value != null && value.length() > 0;
        Validator<String> validator = Validator.builder(String.class).rule("text.required", nonEmpty).build();

        assertTrue(validator.validate("Ada").isEmpty());
        List<ValidationError> errors = validator.validate("");
        assertSingleErrorWithCode(errors, "text.required");
        assertNonblankMessage(errors.get(0));
    }

    @Test
    void givenNullPredicateWhenAddingTwoArgumentRuleThenRejectsConfiguration() {
        assertThrows(RuntimeException.class, () -> Validator.builder(Person.class).rule("name.required", null).build());
    }

    // P010: rule(code, message, predicate), replacing the retired P004 overload.

    @Test
    void givenCodeAndMessageWhenThreeArgumentRuleFailsThenReportsBothAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> person.name != null)
                .build()
                .validate(new Person());

        assertSingleError(errors, "name.required", "Name is required");
    }

    @Test
    void givenSupertypePredicateWhenAddingCodeAndMessageRuleThenPreservesMetadataAtCurrentNode() {
        Predicate<Object> present = Objects::nonNull;
        Validator<String> validator = Validator.builder(String.class)
                .rule("text.required", "Text is required", present)
                .build();

        assertTrue(validator.validate("Ada").isEmpty());
        assertSingleError(validator.validate(null), "text.required", "Text is required");
    }

    @Test
    void givenPassingThreeArgumentRuleWhenValidatingThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name.required", "Name is required", value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenThreeArgumentRuleReturnsFalseThenReportsCustomMetadataAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("person.required", "Person is required", Objects::nonNull)
                .build()
                .validate(null);

        assertSingleError(errors, "person.required", "Person is required");
    }

    @Test
    void givenNullRootWhenThreeArgumentRuleReturnsTrueThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person.absent", "Person must be absent", Objects::isNull)
                .build();

        assertTrue(validator.validate(null).isEmpty());
    }

    @Test
    void givenThrowingThreeArgumentRuleWhenValidatingThenReportsExecutionFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", "Name is required", value -> {
                    throw new IllegalStateException("lookup failed");
                })
                .build()
                .validate(new Person());

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains(IllegalStateException.class.getName()));
        assertTrue(errors.get(0).getMessage().contains("lookup failed"));
    }

    @Test
    void givenNullOrBlankCodeWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        for (String code : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class).rule(code, "Name is required", person -> true).build()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "\u2003", "\u3000", "\t \u2003\u3000\r\n" })
    void givenUnicodeWhitespaceOnlyCodeWhenAddingThreeArgumentRuleThenRejectsConfiguration(String code) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule(code, "Name is required", person -> true).build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { " person.required ", "\u2003person.required\u3000", "person required" })
    void givenNonblankCodeContainingWhitespaceWhenAddingThreeArgumentRuleThenPreservesExplicitMetadata(String code) {
        Validator<Person> validator = assertDoesNotThrow(
                () -> Validator.builder(Person.class).rule(code, "Person is required", person -> false).build()
        );

        assertSingleError(validator.validate(new Person()), code, "Person is required");
    }

    @Test
    void givenNullMessageWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule("name.required", null, person -> true).build()
        );
    }

    @Test
    void givenBlankMessageWhenAddingThreeArgumentRuleThenPreservesExplicitMessage() {
        for (String message : new String[] { "", "   " }) {
            Validator<Person> validator = assertDoesNotThrow(
                    () -> Validator.builder(Person.class).rule("name.required", message, person -> false).build()
            );

            assertSingleError(validator.validate(new Person()), "name.required", message);
        }
    }

    @Test
    void givenNullPredicateWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule("name.required", "Name is required", null).build()
        );
    }

    @Test
    void givenRevisedRuleApiWhenInspectingPublicMethodsThenPathTakingFourArgumentOverloadIsAbsent() {
        assertThrows(
                NoSuchMethodException.class,
                () -> Validator.Builder.class
                        .getMethod("rule", String.class, String.class, String.class, Predicate.class)
        );
    }

    // P011: ValidationError.getAttributes() and the attributes constructor.
    // Producer-specific attributes and nested diagnostic snapshots are covered
    // when their APIs are introduced.

    @Test
    void whenInspectingAttributesAccessorThenFindsAPublicNoArgumentMethodReturningAMap() throws NoSuchMethodException {
        assertEquals(Map.class, ValidationError.class.getMethod("getAttributes").getReturnType());
    }

    @ParameterizedTest
    @ValueSource(strings = { "#", "#.address", "#.lines[0].sku", "#.contacts[1].value.number" })
    void givenErrorCreatedWithoutAttributesWhenReadingAttributesThenReturnsAnEmptyImmutableMapAndPreservesMetadata(
            String path
    ) {
        ValidationError error = new ValidationError(path, "value.invalid", "Value is invalid");

        assertEmptyImmutableAttributes(error);

        assertAll(
                () -> assertEquals(path, error.getPath()),
                () -> assertEquals("value.invalid", error.getCode()),
                () -> assertEquals("Value is invalid", error.getMessage())
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenMutableAttributesWhenTheSourceChangesAfterConstructionThenErrorRetainsItsOriginalSnapshot(
            boolean initiallyEmpty
    ) {
        Map<String, Object> source = new HashMap<>();
        if (!initiallyEmpty) {
            source.put("min", 18);
        }
        Map<String, Object> expected = new HashMap<>(source);
        ValidationError error = new ValidationError("#.age", "age.invalid", "Age is invalid", source);

        source.put("min", 21);
        source.put("max", 120);

        Map<String, Object> attributes = error.getAttributes();
        assertEquals(expected, attributes);
        source.clear();
        assertEquals(expected, attributes);
        assertEquals(expected, error.getAttributes());
        assertImmutableAttributes(error);
    }

    @Test
    void givenUnmodifiableAttributesViewWhenItsBackingMapChangesThenErrorRetainsTheValuesAtConstruction() {
        Map<String, Object> source = new HashMap<>();
        source.put("min", 18);
        Map<String, Object> view = Collections.unmodifiableMap(source);
        ValidationError error = new ValidationError("#.age", "min", "Age must be at least 18", view);

        source.put("min", 21);

        assertEquals(Collections.singletonMap("min", 18), error.getAttributes());
        assertImmutableAttributes(error);
    }

    @Test
    void givenNullAttributesWhenCreatingValidationErrorThenRejectsExplicitNullMetadata() {
        assertThrows(
                RuntimeException.class,
                () -> new ValidationError("#.age", "min", "Age must be at least 18", null)
        );
    }

    @Test
    void givenTypedAndWildcardAttributeMapsWhenConstructingValidationErrorsThenAcceptsThemAndKeepsDefensiveSnapshots() {
        Map<String, Integer> integerAttributes = new HashMap<>();
        integerAttributes.put("min", 18);
        Map<String, Long> longAttributes = new HashMap<>();
        longAttributes.put("max", 120L);
        Map<String, ? extends Number> wildcardAttributes = longAttributes;

        ValidationError minimum = new ValidationError("#.age", "min", "Minimum required", integerAttributes);
        ValidationError maximum = new ValidationError("#.age", "max", "Maximum exceeded", wildcardAttributes);
        integerAttributes.put("min", 21);
        longAttributes.clear();

        Map<String, Object> minimumSnapshot = minimum.getAttributes();
        Map<String, Object> maximumSnapshot = maximum.getAttributes();
        assertEquals(Collections.singletonMap("min", 18), minimumSnapshot);
        assertEquals(Collections.singletonMap("max", 120L), maximumSnapshot);
        assertTrue(minimumSnapshot.get("min") instanceof Integer);
        assertTrue(maximumSnapshot.get("max") instanceof Long);
        assertImmutableAttributes(minimum);
        assertImmutableAttributes(maximum);
    }

    @Test
    void givenPopulatedAttributesWhenMutatingTheReturnedMapOrItsViewsThenRejectsChangesAndPreservesAllMetadata() {
        ValidationError error = new ValidationError(
                "#.age", "min", "Age must be at least 18", Collections.singletonMap("min", 18)
        );
        Map<String, Object> attributes = error.getAttributes();
        Map.Entry<String, Object> entry = attributes.entrySet().iterator().next();
        Iterator<Map.Entry<String, Object>> entries = attributes.entrySet().iterator();
        entries.next();

        assertImmutableAttributes(error);
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.remove("min")),
                () -> assertThrows(UnsupportedOperationException.class, attributes::clear),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.replace("min", 21)),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.compute("min", (key, value) -> 21)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.replaceAll((key, value) -> 21)
                ),
                () -> assertThrows(UnsupportedOperationException.class, () -> entry.setValue(21)),
                () -> assertThrows(UnsupportedOperationException.class, entries::remove),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.keySet().remove("min")),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.values().remove(18))
        );
        assertAll(
                () -> assertEquals(Collections.singletonMap("min", 18), attributes),
                () -> assertEquals(Collections.singletonMap("min", 18), error.getAttributes()),
                () -> assertEquals("#.age", error.getPath()),
                () -> assertEquals("min", error.getCode()),
                () -> assertEquals("Age must be at least 18", error.getMessage())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeFreeRuleConfigurations")
    void givenRuleOrNotNullOverloadWhenValidationFailsAtRootThenReturnsEmptyImmutableAttributes(
            String signature,
            Consumer<Validator.Builder<String>> configure,
            String expectedCode
    ) {
        Validator.Builder<String> builder = Validator.builder(String.class);
        configure.accept(builder);

        ValidationError error = assertSingleErrorAt(builder.build().validate(null), "#", expectedCode);

        assertEmptyImmutableAttributes(error);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeFreeRuleConfigurations")
    void givenRuleOrNotNullOverloadWhenValidationFailsAtNestedFieldThenReturnsEmptyImmutableAttributes(
            String signature,
            Consumer<Validator.Builder<String>> configure,
            String expectedCode
    ) {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, configure)
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(new Address(null))),
                "#.address.streetName",
                expectedCode
        );

        assertEmptyImmutableAttributes(error);
    }

    @Test
    void givenSharedValidatorIncludedAtTwoFieldPathsWhenReadingAttributesThenPreservesEachPathAndSharedErrorMetadata() {
        Validator<String> shared = Validator.builder(String.class)
                .rule("text.invalid", "Text is invalid", value -> false)
                .build();
        ValidationError original = assertSingleErrorAt(shared.validate("Ada"), "#", "text.invalid");
        Map<String, Object> originalAttributes = original.getAttributes();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .field("alias", Person::getName, alias -> alias.include(shared))
                .build();

        List<ValidationError> errors = parent.validate(new Person(new Address("Main Street")));

        assertErrorsAt(errors, "#.name [text.invalid]", "#.alias [text.invalid]");
        for (ValidationError error : errors) {
            assertEquals("Text is invalid", error.getMessage());
            assertEmptyImmutableAttributes(error);
        }
        assertEmptyImmutableAttributes(original);
        assertEquals(Collections.emptyMap(), originalAttributes);
        assertEquals("#", original.getPath());
        assertEquals("text.invalid", original.getCode());
        assertEquals("Text is invalid", original.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenGuardedRuleWhenGuardPassesAtRootOrFieldThenReturnsEmptyImmutableAttributesAtTheCurrentPath(
            boolean fieldScope
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(
                        value -> true,
                        rules -> rules
                                .rule("text.invalid", "Text is invalid", value -> false)
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, null),
                fieldScope ? "#.name" : "#",
                "text.invalid"
        );

        assertEquals("Text is invalid", error.getMessage());
        assertEmptyImmutableAttributes(error);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenThrowingRuleWhenExecutionFailsAtRootOrFieldThenReturnsImmutableAttributesAndPreservesFailureDetails(
            boolean fieldScope
    ) {
        IllegalStateException failure = new IllegalStateException("rule failed");
        Validator<String> validator = Validator.builder(String.class)
                .rule(value -> {
                    throw failure;
                })
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, "Ada"),
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );

        assertImmutableAttributes(error);
        assertExceptionDetails(error, failure.getClass(), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenThrowingExtractorWhenExecutionFailsAtAFieldOrNestedFieldThenReturnsImmutableAttributesAtTheBlockedPath(
            boolean fieldScope
    ) {
        IllegalStateException failure = new IllegalStateException("extractor failed");
        Function<String, String> extractor = value -> {
            throw failure;
        };
        Validator<String> validator = Validator.builder(String.class)
                .field("value", extractor, value -> value.notNull())
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, "Ada"),
                fieldScope ? "#.name.value" : "#.value",
                "validation.executionFailed"
        );

        assertImmutableAttributes(error);
        assertExceptionDetails(error, failure.getClass(), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenThrowingGuardWhenExecutionFailsAtRootOrFieldThenReturnsImmutableAttributesAndPreservesFailureDetails(
            boolean fieldScope
    ) {
        IllegalStateException failure = new IllegalStateException("guard failed");
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> {
                    throw failure;
                }, rules -> rules.notNull())
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, "Ada"),
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );

        assertImmutableAttributes(error);
        assertExceptionDetails(error, failure.getClass(), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullParentWhenAFieldOrNestedFieldIsBlockedThenReturnsImmutableAttributesAtTheSkippedPath(
            boolean nullRoot
    ) {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(nullRoot ? null : new Person(null)),
                nullRoot ? "#.address" : "#.address.streetName",
                "validation.skipped"
        );

        assertImmutableAttributes(error);
        assertNonblankMessage(error);
    }

    @Test
    void givenOrdinarySkippedAndExecutionFailuresWhenValidatingThenEveryErrorExposesItsOwnImmutableAttributesContract() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person.invalid", "Person is invalid", person -> false)
                .field("name", Person::getName, name -> name.notNull())
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .onlyIf(person -> {
                    throw new IllegalStateException("guard failed");
                }, rules -> rules.notNull())
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(
                errors,
                "# [person.invalid]",
                "#.name [notNull]",
                "#.address.streetName [validation.skipped]",
                "# [validation.executionFailed]"
        );
        for (ValidationError error : errors) {
            assertImmutableAttributes(error);
            if ("person.invalid".equals(error.getCode()) || "notNull".equals(error.getCode())) {
                assertEquals(Collections.emptyMap(), error.getAttributes());
            }
        }
    }

    @Test
    void givenEarlierErrorAttributesWhenBuilderAndLaterValidationResultsChangeThenPreservesTheOriginalEmptySnapshot() {
        Validator.Builder<String> builder = Validator.builder(String.class)
                .notNull("text.required", "Text is required");
        Validator<String> first = builder.build();
        ValidationError original = assertSingleErrorAt(first.validate(null), "#", "text.required");
        Map<String, Object> attributes = original.getAttributes();

        builder.rule("text.invalid", "Text is invalid", value -> false);
        Validator<String> second = builder.build();
        assertTrue(first.validate("Ada").isEmpty());
        List<ValidationError> laterErrors = second.validate(null);

        assertErrorsAt(laterErrors, "# [text.required]", "# [text.invalid]");
        laterErrors.forEach(ValidatorTest::assertEmptyImmutableAttributes);
        assertEmptyImmutableAttributes(original);
        assertEquals(Collections.emptyMap(), attributes);
        assertEquals("#", original.getPath());
        assertEquals("text.required", original.getCode());
        assertEquals("Text is required", original.getMessage());
        assertEmptyImmutableAttributes(assertSingleErrorAt(first.validate(null), "#", "text.required"));
    }

    // P012: include(validator). Attribute checks are in P011; member traversal
    // has later phases.

    @Test
    void givenTypedReusableValidatorWhenIncludingThenReturnsSameBuilderAndRetainsTypedParentChaining() {
        Validator<Person> shared = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> person.getName() != null)
                .build();
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        Validator.Builder<Person> returned = parent.include(shared);

        assertSame(parent, returned);
        Validator<Person> validator = returned.rule("age.invalid", person -> person.getAge() >= 18).build();
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertErrorsAt(validator.validate(new Person()), "# [name.required]", "# [age.invalid]");
    }

    @Test
    void givenObjectValidatorWhenIncludingAtPersonRootThenRetainsTypedChainingAndValidatesPersonAndNull() {
        Validator<Object> shared = Validator.builder(Object.class)
                .notNull("value.required", "Value is required")
                .build();
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        Validator.Builder<Person> returned = parent.include(shared);

        assertSame(parent, returned);
        Validator<Person> validator = returned
                .rule("age.invalid", person -> person == null || person.getAge() >= 18)
                .build();
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleError(validator.validate(null), "value.required", "Value is required");
        assertSingleErrorAt(
                validator.validate(new Person("Ada", null, 17, Collections.emptyList())),
                "#",
                "age.invalid"
        );
    }

    @Test
    void givenObjectValidatorWhenIncludingAtAddressFieldThenRetainsTypedChildChainingAndReportsAtField() {
        Validator<Object> shared = Validator.builder(Object.class).notNull("value.required").build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .include(shared)
                                .rule("street.required", value -> value == null || value.getStreetName() != null)
                )
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "value.required");
        assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address", "street.required");
    }

    @Test
    void givenCharSequenceValidatorWithNestedFieldWhenIncludingAtStringScopesThenRebasesItsRulesAndRemainsReusable() {
        Validator<CharSequence> shared = Validator.builder(CharSequence.class)
                .rule("text.required", value -> value != null && value.length() > 0)
                .field("length", CharSequence::length, length -> length.rule("length.min", value -> value >= 4))
                .build();
        Validator<String> root = Validator.builder(String.class).include(shared).build();
        Validator<Person> field = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertTrue(root.validate("Augusta").isEmpty());
        assertSingleErrorAt(root.validate("Ada"), "#.length", "length.min");
        assertSingleErrorAt(
                field.validate(new Person(new Address("Main Street"))),
                "#.name.length",
                "length.min"
        );
        assertTrue(field.validate(new Person("Augusta", null, 18, Collections.emptyList())).isEmpty());
        assertSingleErrorAt(shared.validate(new StringBuilder("Ada")), "#.length", "length.min");
    }

    @Test
    void givenWildcardSupertypeValidatorReferenceWhenIncludingTransitivelyThenRetainsStringScopeAndMetadata() {
        Validator<? super String> shared = Validator.builder(CharSequence.class)
                .rule("text.required", "Text is required", value -> value != null && value.length() > 0)
                .build();
        Validator<String> middle = Validator.builder(String.class).include(shared).build();
        Validator<String> outer = Validator.builder(String.class).include(middle).build();

        assertTrue(outer.validate("Ada").isEmpty());
        assertSingleError(outer.validate(""), "text.required", "Text is required");
        assertSingleError(outer.validate(null), "text.required", "Text is required");
    }

    @Test
    void givenEmptyIncludedValidatorWhenValidatingPresentOrNullRootThenReturnsImmutableEmptyResults() {
        Validator<Person> shared = Validator.builder(Person.class).build();
        Validator<Person> validator = Validator.builder(Person.class).include(shared).build();

        List<ValidationError> presentErrors = validator.validate(new Person());
        List<ValidationError> nullErrors = validator.validate(null);

        assertTrue(presentErrors.isEmpty());
        assertTrue(nullErrors.isEmpty());
        ValidationError extraError = new ValidationError("#", "unexpected", "Unexpected error");
        assertThrows(UnsupportedOperationException.class, () -> presentErrors.add(extraError));
        assertThrows(UnsupportedOperationException.class, () -> nullErrors.add(extraError));
    }

    @Test
    void givenIncludedRootAndNestedRulesWhenValidatingThenPreservesTheirPathsCodesAndMessages() {
        Validator<Person> shared = Validator.builder(Person.class)
                .rule("person.invalid", "Person is invalid", person -> false)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.field(
                                "streetName",
                                Address::getStreetName,
                                street -> street.rule("street.invalid", "Street is invalid", value -> false)
                        )
                )
                .build();
        Validator<Person> validator = Validator.builder(Person.class).include(shared).build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(errors, "# [person.invalid]", "#.address.streetName [street.invalid]");
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "person.invalid".equals(error.getCode())
                                        && "Person is invalid".equals(error.getMessage())
                        )
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "street.invalid".equals(error.getCode())
                                        && "Street is invalid".equals(error.getMessage())
                        )
        );
    }

    @Test
    void givenValidatorIncludedAtFieldWhenValidatingThenRebasesRootAndDescendantsExactlyOnce() {
        Validator<Address> shared = Validator.builder(Address.class)
                .rule("address.invalid", "Address is invalid", address -> false)
                .field("road", Address::getStreetName, street -> street.rule("street.invalid", value -> false))
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("postalAddress", Person::getAddress, address -> address.include(shared))
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(errors, "#.postalAddress [address.invalid]", "#.postalAddress.road [street.invalid]");
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.invalid".equals(error.getCode())
                                        && "Address is invalid".equals(error.getMessage())
                        )
        );
        assertErrorsAt(
                shared.validate(new Address("Main Street")),
                "# [address.invalid]",
                "#.road [street.invalid]"
        );
    }

    @Test
    void givenTransitivelyIncludedValidatorsWhenAppliedAtFieldThenKeepsOneCurrentNodePath() {
        Validator<String> leaf = Validator.builder(String.class).rule("text.invalid", value -> false).build();
        Validator<String> middle = Validator.builder(String.class).include(leaf).build();
        Validator<String> outer = Validator.builder(String.class).include(middle).build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(outer))
                .build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.name",
                "text.invalid"
        );
        assertSingleErrorAt(outer.validate("Ada"), "#", "text.invalid");
        assertSingleErrorAt(leaf.validate("Ada"), "#", "text.invalid");
    }

    @Test
    void givenRepeatedFieldNamesAcrossIncludedScopesWhenValidatingThenRetainsEveryDeclaredSegment() {
        Validator<Address> shared = Validator.builder(Address.class)
                .field("address", Address::getStreetName, street -> street.rule("street.invalid", value -> false))
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.include(shared))
                .build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address.address",
                "street.invalid"
        );
    }

    @Test
    void givenSameValidatorIncludedTwiceWhenValidatingThenEvaluatesEachInclusionAndRetainsDuplicateFailures() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .rule("shared.invalid", person -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .build();
        Validator.Builder<Person> parent = Validator.builder(Person.class).include(shared).include(shared);
        assertEquals(0, evaluations.get());
        Validator<Person> validator = parent.build();
        assertEquals(0, evaluations.get());

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(errors, "# [shared.invalid]", "# [shared.invalid]");
        assertEquals(2, evaluations.get());
    }

    @Test
    void givenFailingLocalAndIncludedRulesWhenValidatingThenCollectsEveryIndependentFailureWithoutAssumingOrder() {
        Validator<Person> shared = Validator.builder(Person.class)
                .rule("shared.first", person -> false)
                .rule("shared.second", person -> false)
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("local.first", person -> false)
                .include(shared)
                .rule("local.last", person -> false)
                .build();

        assertErrorsAt(
                validator.validate(new Person()),
                "# [local.first]",
                "# [shared.first]",
                "# [shared.second]",
                "# [local.last]"
        );
    }

    @Test
    void givenIncludedRootPredicateThrowsWhenValidatingThenRecordsFailureAndContinuesIncludedAndOuterRules() {
        AtomicInteger independentRules = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .rule(person -> {
                    throw new IllegalStateException("included predicate failed");
                })
                .rule("shared.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .include(shared)
                .rule("local.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [shared.invalid]", "# [local.invalid]");
        assertEquals(2, independentRules.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing included execution failure"));
        assertExceptionDetails(executionError, IllegalStateException.class, "included predicate failed");
    }

    @Test
    void givenIncludedFieldPredicateThrowsWhenValidatingThenRebasesFailureAndContinuesIndependentRules() {
        Validator<Address> shared = Validator.builder(Address.class)
                .rule(address -> {
                    throw new IllegalArgumentException("included address rule failed");
                })
                .rule("address.invalid", address -> false)
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.include(shared))
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing included field failure"));
        assertExceptionDetails(executionError, IllegalArgumentException.class, "included address rule failed");
    }

    @Test
    void givenIncludedExtractorThrowsWhenValidatingThenRebasesFailureBlocksDescendantsAndContinuesIndependentRules() {
        AtomicInteger descendants = new AtomicInteger();
        Function<Address, String> extractor = address -> {
            throw new IllegalStateException("included extraction failed");
        };
        Validator<Address> shared = Validator.builder(Address.class)
                .field("streetName", extractor, street -> street.rule(value -> {
                    descendants.incrementAndGet();
                    return false;
                }))
                .rule("address.invalid", address -> false)
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.include(shared))
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address.streetName [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        assertEquals(0, descendants.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing included extractor failure"));
        assertExceptionDetails(executionError, IllegalStateException.class, "included extraction failed");
    }

    @Test
    void givenNullRootWhenIncludingNullAwareValidatorThenPassesNullToItsRulesWithoutImplicitPresenceCheck() {
        AtomicReference<Person> observed = new AtomicReference<>(new Person());
        Validator<Person> shared = Validator.builder(Person.class)
                .notNull("person.required")
                .rule("person.absent", person -> {
                    observed.set(person);
                    return person == null;
                })
                .build();
        Validator<Person> validator = Validator.builder(Person.class).include(shared).build();

        assertSingleErrorAt(validator.validate(null), "#", "person.required");
        assertNull(observed.get());
    }

    @Test
    void givenNullRootWhenIncludedValidatorContainsFieldsThenReportsPresenceAndOneSkipPerBlockedField() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .notNull()
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                }, address -> address.rule(value -> {
                    descendants.incrementAndGet();
                    return false;
                }))
                .build();
        Validator<Person> validator = Validator.builder(Person.class).include(shared).build();

        assertErrorsAt(validator.validate(null), "# [notNull]", "#.address [validation.skipped]");
        assertEquals(0, extractions.get());
        assertEquals(0, descendants.get());
    }

    @Test
    void givenNullExtractedValueWhenIncludingNullAwareValidatorThenReportsPresenceAndSkipsOnlyItsDescendantField() {
        AtomicReference<Address> observed = new AtomicReference<>(new Address("Main Street"));
        Validator<Address> shared = Validator.builder(Address.class)
                .notNull("address.required")
                .rule("address.absent", address -> {
                    observed.set(address);
                    return address == null;
                })
                .field("streetName", Address::getStreetName, street -> street.notNull())
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.include(shared))
                .build();

        assertErrorsAt(
                validator.validate(new Person(null)),
                "#.address [address.required]",
                "#.address.streetName [validation.skipped]"
        );
        assertNull(observed.get());
    }

    @Test
    void givenNullParentWhenFieldContainsIncludedValidatorThenSkipsFieldOnceWithoutExecutingItsExtractorOrIncludedRules() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Address> shared = Validator.builder(Address.class)
                .rule(address -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .field("streetName", Address::getStreetName, street -> street.notNull())
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                }, address -> address.include(shared))
                .build();

        assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
        assertEquals(0, extractions.get());
        assertEquals(0, evaluations.get());
    }

    @Test
    void givenNullFieldValueWhenIncludedPredicateDereferencesItThenReportsExecutionFailureAtCurrentField() {
        Validator<String> shared = Validator.builder(String.class).rule(value -> value.length() > 0).build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person()),
                "#.name",
                "validation.executionFailed"
        );
        assertExceptionDetails(error, NullPointerException.class, null);
    }

    @Test
    void givenEmptyValidatorIncludedAtFieldWhenValidatingThenAcceptsNullFieldButStillReportsBlockedParentTraversal() {
        Validator<String> shared = Validator.builder(String.class).build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(validator.validate(null), "#.name", "validation.skipped");
    }

    @Test
    void givenSourceBuilderChangesAfterInclusionWhenBuildingParentThenUsesAlreadyBuiltIncludedSnapshot() {
        Validator.Builder<Person> source = Validator.builder(Person.class).rule("original", person -> false);
        Validator<Person> shared = source.build();
        Validator.Builder<Person> parent = Validator.builder(Person.class).include(shared);

        source.rule("added.before.outer.build", person -> false);
        Validator<Person> validator = parent.build();
        source.rule("added.after.outer.build", person -> false);

        assertSingleErrorAt(validator.validate(new Person()), "#", "original");
        assertSingleErrorAt(shared.validate(new Person()), "#", "original");
        assertErrorsAt(
                source.build().validate(new Person()),
                "# [original]",
                "# [added.before.outer.build]",
                "# [added.after.outer.build]"
        );
    }

    @Test
    void givenMoreInclusionsAndRulesAfterParentBuildWhenValidatingThenEarlierValidatorAndSharedTemplatesStayIsolated() {
        Validator<Person> firstShared = Validator.builder(Person.class).rule("first.shared", person -> false).build();
        Validator<Person> secondShared = Validator.builder(Person.class).rule("second.shared", person -> false).build();
        Validator.Builder<Person> parent = Validator.builder(Person.class).include(firstShared);
        Validator<Person> first = parent.build();

        parent.include(secondShared).rule("local.invalid", person -> false);
        Validator<Person> second = parent.build();

        assertSingleErrorAt(first.validate(new Person()), "#", "first.shared");
        assertErrorsAt(
                second.validate(new Person()),
                "# [first.shared]",
                "# [second.shared]",
                "# [local.invalid]"
        );
        assertSingleErrorAt(firstShared.validate(new Person()), "#", "first.shared");
        assertSingleErrorAt(secondShared.validate(new Person()), "#", "second.shared");
    }

    @Test
    void givenSameValidatorIncludedAtDifferentParentPathsWhenValidatingRepeatedlyThenNeverMutatesItsRootOrOtherPaths() {
        Validator<String> shared = Validator.builder(String.class).rule("text.invalid", value -> false).build();
        Validator<Person> first = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();
        Validator<Person> second = Validator.builder(Person.class)
                .field("alias", Person::getName, name -> name.include(shared))
                .build();
        Person person = new Person(new Address("Main Street"));

        List<ValidationError> firstErrors = first.validate(person);
        List<ValidationError> secondErrors = second.validate(person);

        assertSingleErrorAt(firstErrors, "#.name", "text.invalid");
        assertSingleErrorAt(secondErrors, "#.alias", "text.invalid");
        assertSingleErrorAt(shared.validate("Ada"), "#", "text.invalid");
        assertSingleErrorAt(first.validate(person), "#.name", "text.invalid");
        assertSingleErrorAt(firstErrors, "#.name", "text.invalid");
        assertSingleErrorAt(secondErrors, "#.alias", "text.invalid");
    }

    @Test
    void givenIncludedAndLocalFailuresWhenValidatingDifferentInputsThenKeepsResultsImmutableAndCallLocal() {
        Validator<String> shared = Validator.builder(String.class)
                .rule("text.short", value -> value.length() >= 4)
                .build();
        Validator<String> validator = Validator.builder(String.class)
                .include(shared)
                .rule("local.invalid", value -> false)
                .build();

        List<ValidationError> first = validator.validate("Ada");
        List<ValidationError> second = validator.validate("Augusta");
        List<ValidationError> third = validator.validate("Ada");
        List<ValidationError> sourceErrors = shared.validate("Ada");

        assertErrorsAt(first, "# [text.short]", "# [local.invalid]");
        assertSingleErrorAt(second, "#", "local.invalid");
        assertErrorsAt(third, "# [text.short]", "# [local.invalid]");
        assertSingleErrorAt(sourceErrors, "#", "text.short");
        assertTrue(shared.validate("Augusta").isEmpty());
        ValidationError extraError = new ValidationError("#", "unexpected", "Unexpected error");
        assertThrows(UnsupportedOperationException.class, () -> first.add(extraError));
        assertThrows(UnsupportedOperationException.class, second::clear);
        assertThrows(UnsupportedOperationException.class, () -> third.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> sourceErrors.add(extraError));
    }

    @Test
    void givenPrebuiltValidatorWithNestedRulesWhenIncludingAndBuildingRepeatedlyThenExecutesOnlyDuringValidation() {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .field("name", person -> {
                    extractions.incrementAndGet();
                    return person.getName();
                }, name -> {
                    configurations.incrementAndGet();
                    name.rule("name.invalid", value -> {
                        evaluations.incrementAndGet();
                        return false;
                    });
                })
                .build();
        Validator.Builder<Person> parent = Validator.builder(Person.class).include(shared);

        assertEquals(1, configurations.get());
        assertEquals(0, extractions.get());
        assertEquals(0, evaluations.get());
        Validator<Person> first = parent.build();
        Validator<Person> second = parent.build();
        assertEquals(1, configurations.get());
        assertEquals(0, extractions.get());
        assertEquals(0, evaluations.get());

        Person person = new Person(new Address("Main Street"));
        assertSingleErrorAt(first.validate(person), "#.name", "name.invalid");
        assertSingleErrorAt(second.validate(person), "#.name", "name.invalid");
        assertSingleErrorAt(shared.validate(person), "#.name", "name.invalid");
        assertEquals(1, configurations.get());
        assertEquals(3, extractions.get());
        assertEquals(3, evaluations.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenIncludedPredicateThrowsJvmErrorWhenValidatingRootOrFieldThenPropagatesSameError(boolean fieldScope) {
        AssertionError failure = new AssertionError("fatal included predicate failure");
        Validator<String> shared = Validator.builder(String.class).rule(value -> {
            throw failure;
        }).build();
        Validator.Builder<String> parent = Validator.builder(String.class);
        if (fieldScope) {
            parent.field("value", Function.identity(), value -> value.include(shared));
        } else {
            parent.include(shared);
        }
        Validator<String> validator = parent.build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate("Ada")));
    }

    @Test
    void givenIncludedExtractorThrowsJvmErrorWhenValidatingNestedFieldThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal included extractor failure");
        Function<Address, String> extractor = address -> {
            throw failure;
        };
        Validator<Address> shared = Validator.builder(Address.class)
                .field("streetName", extractor, street -> street.notNull())
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.include(shared))
                .build();

        assertSame(
                failure,
                assertThrows(AssertionError.class, () -> validator.validate(new Person(new Address("Main Street"))))
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullIncludedValidatorWhenConfiguringRootOrFieldThenRejectsConfigurationNoLaterThanBuild(
            boolean fieldScope
    ) {
        Validator<String> shared = null;

        assertThrows(RuntimeException.class, () -> {
            Validator.Builder<String> parent = Validator.builder(String.class);
            if (fieldScope) {
                parent.field("value", Function.identity(), value -> value.include(shared));
            } else {
                parent.include(shared);
            }
            parent.build();
        });
    }

    // P013: onlyIf(condition, rules). Attribute checks are in P011; member
    // traversal has later phases.

    @Test
    void givenTypedGuardAndRulesWhenConfiguringOnlyIfThenReturnsSameParentBuilderAndPreservesTypedChaining() {
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        Validator.Builder<Person> returned = parent.onlyIf(
                person -> person.getAge() >= 18,
                rules -> rules.rule("name.required", person -> person.getName() != null)
        );

        assertSame(parent, returned);
        Validator<Person> validator = returned.rule("age.invalid", person -> person.getAge() >= 0).build();
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(
                validator.validate(new Person(null, null, 18, Collections.emptyList())),
                "#",
                "name.required"
        );
        assertSingleErrorAt(
                validator.validate(new Person(null, null, -1, Collections.emptyList())),
                "#",
                "age.invalid"
        );
    }

    @Test
    void givenSupertypePredicateWhenConfiguringOnlyIfThenRetainsStringScopeForGuardedRules() {
        Predicate<CharSequence> nonEmpty = value -> value != null && value.length() > 0;
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(nonEmpty, rules -> rules.rule("text.short", value -> value.substring(0).length() >= 3))
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertTrue(validator.validate("").isEmpty());
        assertTrue(validator.validate("Ada").isEmpty());
        assertSingleErrorAt(validator.validate("Al"), "#", "text.short");
    }

    @Test
    void givenSupertypeBuilderConsumerWhenConfiguringOnlyIfThenAppliesItsRulesAtTheCurrentNode() {
        Consumer<Validator.Builder<?>> requirePresence = rules -> rules.notNull("value.required");
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> true, requirePresence)
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
        assertSingleErrorAt(validator.validate(null), "#", "value.required");
    }

    @Test
    void givenFalseGuardWhenValidatingThenSilentlySkipsRulesFieldsInclusionsAndNestedGuards() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class).rule(person -> {
            descendants.incrementAndGet();
            throw new AssertionError("Included rule must remain skipped");
        }).build();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    return false;
                },
                        rules -> rules
                                .notNull()
                                .rule(person -> {
                                    descendants.incrementAndGet();
                                    throw new AssertionError("Guarded rule must remain skipped");
                                })
                                .field("address", person -> {
                                    descendants.incrementAndGet();
                                    throw new AssertionError("Guarded extractor must remain skipped");
                                }, address -> address.notNull())
                                .include(shared)
                                .onlyIf(person -> {
                                    descendants.incrementAndGet();
                                    throw new AssertionError("Nested guard must remain skipped");
                                }, nested -> nested.notNull())
                )
                .rule("outside.invalid", person -> false)
                .build();

        assertSingleErrorAt(validator.validate(null), "#", "outside.invalid");
        assertEquals(1, guards.get());
        assertEquals(0, descendants.get());
    }

    @Test
    void givenTrueGuardWithMultipleRulesWhenValidatingThenEvaluatesGuardOnceAndCollectsEveryGuardedAndOutsideFailure() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger rules = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    return true;
                },
                        guarded -> guarded
                                .rule("guarded.first", person -> {
                                    rules.incrementAndGet();
                                    return false;
                                })
                                .rule(person -> {
                                    rules.incrementAndGet();
                                    return true;
                                })
                                .rule("guarded.last", person -> {
                                    rules.incrementAndGet();
                                    return false;
                                })
                )
                .rule("outside.invalid", person -> false)
                .build();

        assertErrorsAt(
                validator.validate(new Person()),
                "# [guarded.first]",
                "# [guarded.last]",
                "# [outside.invalid]"
        );
        assertEquals(1, guards.get());
        assertEquals(3, rules.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenEmptyGuardedBlockWhenValidatingNullThenStillEvaluatesGuardOnceAndReturnsImmutableEmptyResult(
            boolean active
    ) {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger configurations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    assertNull(person);
                    return active;
                }, rules -> configurations.incrementAndGet())
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertTrue(errors.isEmpty());
        assertEquals(1, guards.get());
        assertEquals(1, configurations.get());
        assertThrows(
                UnsupportedOperationException.class,
                () -> errors.add(new ValidationError("#", "unexpected", "Unexpected error"))
        );
    }

    @Test
    void givenRepeatedParentBuildsWhenUsingOnlyIfThenConfiguresBlockOnceAndEvaluatesOnlyDuringValidation() {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger rules = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    return true;
                }, guarded -> {
                    configurations.incrementAndGet();
                    guarded.rule("guarded.invalid", person -> {
                        rules.incrementAndGet();
                        return false;
                    });
                });

        assertEquals(1, configurations.get());
        assertEquals(0, guards.get());
        assertEquals(0, rules.get());
        Validator<Person> first = parent.build();
        Validator<Person> second = parent.build();
        assertEquals(1, configurations.get());
        assertEquals(0, guards.get());
        assertEquals(0, rules.get());

        assertSingleErrorAt(first.validate(new Person()), "#", "guarded.invalid");
        assertSingleErrorAt(second.validate(new Person()), "#", "guarded.invalid");
        assertEquals(1, configurations.get());
        assertEquals(2, guards.get());
        assertEquals(2, rules.get());
    }

    @Test
    void givenDifferentInputsAcrossCallsWhenUsingOnlyIfThenReevaluatesGuardAndKeepsPriorResultsImmutableAndIsolated() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger rules = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    return person != null && person.getAge() >= 18;
                }, guarded -> guarded.rule("adult.invalid", person -> {
                    rules.incrementAndGet();
                    return false;
                }))
                .build();

        List<ValidationError> first = validator.validate(new Person(new Address("Main Street")));
        List<ValidationError> second = validator.validate(new Person("Ada", null, 17, Collections.emptyList()));
        List<ValidationError> third = validator.validate(null);
        List<ValidationError> fourth = validator.validate(new Person(new Address("Main Street")));

        assertSingleErrorAt(first, "#", "adult.invalid");
        assertTrue(second.isEmpty());
        assertTrue(third.isEmpty());
        assertSingleErrorAt(fourth, "#", "adult.invalid");
        assertEquals(4, guards.get());
        assertEquals(2, rules.get());
        ValidationError extraError = new ValidationError("#", "unexpected", "Unexpected error");
        assertThrows(UnsupportedOperationException.class, () -> first.add(extraError));
        assertThrows(UnsupportedOperationException.class, () -> second.add(extraError));
        assertThrows(UnsupportedOperationException.class, () -> third.add(extraError));
        assertThrows(UnsupportedOperationException.class, fourth::clear);
    }

    @Test
    void givenSamePredicateInTwoOnlyIfBlocksWhenValidatingThenEvaluatesEachBlockAndRetainsDuplicateFailures() {
        AtomicInteger guards = new AtomicInteger();
        Predicate<Person> condition = person -> {
            guards.incrementAndGet();
            return true;
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(condition, rules -> rules.rule("duplicate", person -> false))
                .onlyIf(condition, rules -> rules.rule("duplicate", person -> false))
                .build();

        assertErrorsAt(validator.validate(new Person()), "# [duplicate]", "# [duplicate]");
        assertEquals(2, guards.get());
    }

    @Test
    void givenActiveAndInactiveSiblingBlocksWhenValidatingThenGuardsOnlyTheirOwnRules() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("outside.first", person -> false)
                .onlyIf(person -> false, rules -> rules.rule("inactive", person -> false))
                .onlyIf(person -> true, rules -> rules.rule("active", person -> false))
                .rule("outside.last", person -> false)
                .build();

        assertErrorsAt(
                validator.validate(new Person()),
                "# [outside.first]",
                "# [active]",
                "# [outside.last]"
        );
    }

    @Test
    void givenOnlyIfAtAddressFieldWhenGuardIsTrueThenPreservesCurrentAndDescendantPathsAndIncludedMetadata() {
        Validator<Address> shared = Validator.builder(Address.class)
                .rule("included.invalid", "Included address is invalid", address -> false)
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.onlyIf(
                                value -> true,
                                rules -> rules
                                        .rule("address.invalid", "Address is invalid", value -> false)
                                        .field(
                                                "streetName",
                                                Address::getStreetName,
                                                street -> street
                                                        .rule("street.invalid", "Street is invalid", value -> false)
                                        )
                                        .include(shared)
                        )
                )
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address [address.invalid]",
                "#.address.streetName [street.invalid]",
                "#.address [included.invalid]",
                "# [person.invalid]"
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.invalid".equals(error.getCode())
                                        && "Address is invalid".equals(error.getMessage())
                        )
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "included.invalid".equals(error.getCode())
                                        && "Included address is invalid".equals(error.getMessage())
                        )
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "street.invalid".equals(error.getCode())
                                        && "Street is invalid".equals(error.getMessage())
                        )
        );
    }

    @Test
    void givenReusableValidatorContainingOnlyIfWhenIncludedAtDifferentScopesThenRebasesWithoutMutatingItsRoot() {
        Validator<String> shared = Validator.builder(String.class)
                .onlyIf(
                        value -> value != null,
                        rules -> rules
                                .rule("text.short", "Text is too short", value -> value.length() >= 4)
                )
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        ValidationError fieldError = assertSingleErrorAt(
                parent.validate(new Person(new Address("Main Street"))),
                "#.name",
                "text.short"
        );
        assertEquals("Text is too short", fieldError.getMessage());
        assertSingleError(shared.validate("Ada"), "text.short", "Text is too short");
        assertTrue(parent.validate(new Person()).isEmpty());
        assertTrue(shared.validate(null).isEmpty());
        assertSingleErrorAt(parent.validate(new Person(new Address("Main Street"))), "#.name", "text.short");
    }

    @Test
    void givenParameterizedCollectionFieldWhenUsingOnlyIfThenRetainsElementTypeAndGuardsTheWholeCollection() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "aliases",
                        Person::getAliases,
                        aliases -> aliases.onlyIf(
                                values -> values != null && !values.isEmpty(),
                                rules -> rules
                                        .rule(
                                                "aliases.invalid",
                                                values -> values.stream()
                                                        .allMatch(value -> value != null && !value.trim().isEmpty())
                                        )
                        )
                )
                .build();

        assertTrue(validator.validate(new Person("Ada", null, 18, null)).isEmpty());
        assertTrue(validator.validate(new Person("Ada", null, 18, Collections.emptyList())).isEmpty());
        assertTrue(validator.validate(new Person("Ada", null, 18, Arrays.asList("Ada", "Augusta"))).isEmpty());
        assertSingleErrorAt(
                validator.validate(new Person("Ada", null, 18, Arrays.asList("Ada", "   "))),
                "#.aliases",
                "aliases.invalid"
        );
    }

    @ParameterizedTest
    @CsvSource({ "false,true", "true,false", "true,true" })
    void givenNestedOnlyIfBlocksWhenValidatingThenEvaluatesInnerGuardOnlyWhenOuterGuardIsTrue(
            boolean outerActive,
            boolean innerActive
    ) {
        AtomicInteger outerGuards = new AtomicInteger();
        AtomicInteger innerGuards = new AtomicInteger();
        AtomicInteger rules = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    outerGuards.incrementAndGet();
                    return outerActive;
                }, outer -> outer.onlyIf(person -> {
                    innerGuards.incrementAndGet();
                    return innerActive;
                }, inner -> inner.rule("nested.invalid", person -> {
                    rules.incrementAndGet();
                    return false;
                })))
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        if (outerActive && innerActive) {
            assertSingleErrorAt(errors, "#", "nested.invalid");
        } else {
            assertTrue(errors.isEmpty());
        }
        assertEquals(1, outerGuards.get());
        assertEquals(outerActive ? 1 : 0, innerGuards.get());
        assertEquals(outerActive && innerActive ? 1 : 0, rules.get());
    }

    @Test
    void givenNullRootAndNullAwareFalseGuardWhenValidatingThenSuppressesOtherwiseBlockedFieldTraversal() {
        AtomicReference<Person> observed = new AtomicReference<>(new Person());
        AtomicInteger extractions = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    observed.set(person);
                    return person != null;
                },
                        rules -> rules
                                .notNull()
                                .field("address", person -> {
                                    extractions.incrementAndGet();
                                    return person.getAddress();
                                }, address -> address.notNull())
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertNull(observed.get());
        assertEquals(0, extractions.get());
    }

    @Test
    void givenNullRootAndTrueGuardWhenValidatingThenPassesNullToRulesAndReportsPresenceAndOneBlockedField() {
        AtomicReference<Person> observed = new AtomicReference<>(new Person());
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        rules -> rules
                                .notNull("person.required")
                                .rule("person.absent", person -> {
                                    observed.set(person);
                                    return person == null;
                                })
                                .field("address", Person::getAddress, address -> address.notNull())
                )
                .build();

        assertErrorsAt(validator.validate(null), "# [person.required]", "#.address [validation.skipped]");
        assertNull(observed.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullScopeWhenOnlyIfGuardDereferencesItThenRecordsExecutionFailureAndSkipsItsBlock(boolean fieldScope) {
        AtomicInteger guardedRules = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> value.length() > 0, rules -> rules.rule(value -> {
                    guardedRules.incrementAndGet();
                    return false;
                }))
                .rule("outside.invalid", value -> false)
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, fieldScope, null);
        String path = fieldScope ? "#.name" : "#";

        assertErrorsAt(errors, path + " [validation.executionFailed]", path + " [outside.invalid]");
        assertEquals(0, guardedRules.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing null-guard execution failure"));
        assertExceptionDetails(error, NullPointerException.class, null);
    }

    @Test
    void givenNullAddressAndFalseGuardWhenValidatingThenEntersNullableScopeAndSilentlySkipsItsDescendants() {
        AtomicInteger guards = new AtomicInteger();
        AtomicReference<Address> observed = new AtomicReference<>(new Address("Main Street"));
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.onlyIf(value -> {
                    guards.incrementAndGet();
                    observed.set(value);
                    return value != null;
                }, rules -> rules.notNull().field("streetName", Address::getStreetName, street -> street.notNull())))
                .build();

        assertTrue(validator.validate(new Person(null)).isEmpty());
        assertEquals(1, guards.get());
        assertNull(observed.get());
    }

    @Test
    void givenNullAddressAndTrueGuardWhenValidatingThenReportsPresenceAtFieldAndOneSkipAtItsDescendant() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.onlyIf(
                                value -> true,
                                rules -> rules
                                        .notNull("address.required")
                                        .field("streetName", Address::getStreetName, street -> street.notNull())
                        )
                )
                .build();

        assertErrorsAt(
                validator.validate(new Person(null)),
                "#.address [address.required]",
                "#.address.streetName [validation.skipped]"
        );
    }

    @Test
    void givenNullParentWhenFieldContainsOnlyIfThenReportsOneBlockedFieldWithoutEvaluatingItsGuard() {
        AtomicInteger guards = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.onlyIf(value -> {
                    guards.incrementAndGet();
                    return false;
                }, rules -> rules.notNull()))
                .build();

        assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
        assertEquals(0, guards.get());
    }

    @Test
    void givenNullAddressWithPresenceRuleAndNullAwareGuardWhenValidatingThenEvaluatesGuardAndSuppressesDescendantSkip() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger extractions = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.notNull().onlyIf(value -> {
                            guards.incrementAndGet();
                            return value != null;
                        }, rules -> rules.field("streetName", value -> {
                            extractions.incrementAndGet();
                            return value.getStreetName();
                        }, street -> street.notNull()))
                )
                .build();

        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "notNull");
        assertEquals(1, guards.get());
        assertEquals(0, extractions.get());
    }

    @Test
    void givenThrowingRootGuardWhenValidatingThenReportsOneExecutionFailureAndContinuesOutsideRules() {
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> {
                    guards.incrementAndGet();
                    throw new IllegalStateException("root guard failed");
                },
                        rules -> rules
                                .rule(person -> {
                                    descendants.incrementAndGet();
                                    return false;
                                })
                                .field("address", person -> {
                                    descendants.incrementAndGet();
                                    return person.getAddress();
                                }, address -> address.notNull())
                )
                .rule("outside.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [outside.invalid]");
        assertEquals(1, guards.get());
        assertEquals(0, descendants.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing root guard failure"));
        assertExceptionDetails(error, IllegalStateException.class, "root guard failed");
    }

    @Test
    void givenThrowingFieldGuardWhenValidatingThenReportsAtFieldAndContinuesIndependentChildAndRootRules() {
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.onlyIf(value -> {
                            throw new IllegalArgumentException("address guard failed");
                        }, rules -> rules.rule(value -> {
                            descendants.incrementAndGet();
                            return false;
                        })).rule("address.invalid", value -> false)
                )
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        assertEquals(0, descendants.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing field guard failure"));
        assertExceptionDetails(error, IllegalArgumentException.class, "address guard failed");
    }

    @Test
    void givenThrowingNestedGuardWhenValidatingThenSkipsOnlyItsBlockAndContinuesOuterGuardedAndRootRules() {
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        outer -> outer
                                .onlyIf(person -> {
                                    throw new IllegalStateException("nested guard failed");
                                }, inner -> inner.rule(person -> {
                                    descendants.incrementAndGet();
                                    return false;
                                }))
                                .rule("outer.guarded.invalid", person -> false)
                                .onlyIf(person -> true, sibling -> sibling.rule("sibling.invalid", person -> false))
                )
                .rule("root.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(
                errors,
                "# [validation.executionFailed]",
                "# [outer.guarded.invalid]",
                "# [sibling.invalid]",
                "# [root.invalid]"
        );
        assertEquals(0, descendants.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing nested guard failure"));
        assertExceptionDetails(error, IllegalStateException.class, "nested guard failed");
    }

    @ParameterizedTest
    @CsvSource(value = { "false,<null>", "false,guard failed", "true,<null>",
            "true,guard failed" }, nullValues = "<null>")
    void givenAnonymousGuardExceptionWhenValidatingRootOrFieldThenReportsBinaryClassNameAndContinuesOutsideRules(
            boolean fieldScope,
            String message
    ) {
        RuntimeException failure = new RuntimeException(message) {
            private static final long serialVersionUID = 1L;
        };
        AtomicInteger descendants = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> {
                    throw failure;
                }, rules -> rules.rule(value -> {
                    descendants.incrementAndGet();
                    return false;
                }))
                .rule("outside.invalid", value -> false)
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, fieldScope, "Ada");
        String path = fieldScope ? "#.name" : "#";

        assertErrorsAt(errors, path + " [validation.executionFailed]", path + " [outside.invalid]");
        assertEquals(0, descendants.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing anonymous guard failure"));
        assertExceptionDetails(error, failure.getClass(), message);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenGuardExceptionWithThrowingMessageWhenValidatingRootOrFieldThenReportsOriginalTypeAndContinues(
            boolean fieldScope
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> {
                    throw new ThrowingMessageException();
                }, rules -> rules.notNull())
                .rule("outside.invalid", value -> false)
                .build();

        List<ValidationError> errors = assertDoesNotThrow(
                () -> validateAtRootOrNameField(validator, fieldScope, "Ada")
        );
        String path = fieldScope ? "#.name" : "#";

        assertErrorsAt(errors, path + " [validation.executionFailed]", path + " [outside.invalid]");
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing guarded message-lookup failure"));
        assertExceptionDetails(error, ThrowingMessageException.class, null);
    }

    @Test
    void givenTrueGuardAndThrowingGuardedPredicateWhenValidatingThenContinuesOtherGuardedAndOutsideRules() {
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        rules -> rules
                                .rule(person -> {
                                    throw new IllegalStateException("guarded predicate failed");
                                })
                                .rule("guarded.invalid", person -> false)
                )
                .rule("outside.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [guarded.invalid]", "# [outside.invalid]");
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing guarded predicate failure"));
        assertExceptionDetails(error, IllegalStateException.class, "guarded predicate failed");
    }

    @Test
    void givenTrueGuardAndThrowingGuardedExtractorWhenValidatingThenBlocksOnlyThatFieldAndContinuesIndependentRules() {
        AtomicInteger descendants = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            throw new IllegalStateException("guarded extraction failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        rules -> rules
                                .field("address", extractor, address -> address.rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                }))
                                .rule("guarded.invalid", person -> false)
                )
                .rule("outside.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "# [guarded.invalid]",
                "# [outside.invalid]"
        );
        assertEquals(0, descendants.get());
        ValidationError error = errors.stream()
                .filter(value -> "validation.executionFailed".equals(value.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing guarded extractor failure"));
        assertExceptionDetails(error, IllegalStateException.class, "guarded extraction failed");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenGuardThrowsJvmErrorWhenValidatingRootOrFieldThenPropagatesSameError(boolean fieldScope) {
        AssertionError failure = new AssertionError("fatal guard failure");
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> {
                    throw failure;
                }, rules -> rules.notNull())
                .build();

        assertSame(
                failure,
                assertThrows(AssertionError.class, () -> validateAtRootOrNameField(validator, fieldScope, "Ada"))
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenTrueGuardWhenGuardedPredicateOrExtractorThrowsJvmErrorThenPropagatesSameError(boolean extractorFailure) {
        AssertionError failure = new AssertionError("fatal guarded validation failure");
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> true, rules -> {
                    if (extractorFailure) {
                        Function<String, Integer> extractor = value -> {
                            throw failure;
                        };
                        rules.field("length", extractor, length -> length.notNull());
                    } else {
                        rules.rule(value -> {
                            throw failure;
                        });
                    }
                })
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate("Ada")));
    }

    @Test
    void givenNullOnlyIfConditionWhenConfiguringThenRejectsBeforeInvokingRulesCallback() {
        Predicate<Person> condition = null;
        AtomicInteger configurations = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> Validator.builder(Person.class).onlyIf(condition, rules -> {
            configurations.incrementAndGet();
            rules.notNull();
        }));
        assertEquals(0, configurations.get());
    }

    @Test
    void givenNullOnlyIfRulesCallbackWhenConfiguringThenRejectsWithoutEvaluatingGuard() {
        Consumer<Validator.Builder<Person>> callback = null;
        AtomicInteger guards = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> Validator.builder(Person.class).onlyIf(person -> {
            guards.incrementAndGet();
            return true;
        }, callback).build());
        assertEquals(0, guards.get());
    }

    @Test
    void givenInvalidRuleUnderFalseGuardWhenConfiguringThenRejectsTemplateWithoutEvaluatingGuard() {
        AtomicInteger guards = new AtomicInteger();

        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).onlyIf(person -> {
                    guards.incrementAndGet();
                    return false;
                }, rules -> rules.rule("", person -> true)).build()
        );
        assertEquals(0, guards.get());
    }

    @Test
    void givenThrowingRulesCallbackUnderFalseGuardWhenConfiguringThenPropagatesSameFailureWithoutEvaluatingGuard() {
        IllegalStateException failure = new IllegalStateException("invalid guarded configuration");
        AtomicInteger guards = new AtomicInteger();

        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> Validator.builder(Person.class).onlyIf(person -> {
                    guards.incrementAndGet();
                    return false;
                }, rules -> {
                    throw failure;
                }))
        );
        assertEquals(0, guards.get());
    }

    @Test
    void givenGuardedBuilderChangedAfterRegistrationWhenBuildingParentThenIncludesChangesMadeBeforeOuterBuild() {
        AtomicReference<Validator.Builder<Person>> guarded = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class).onlyIf(person -> true, guarded::set);

        assertNotNull(guarded.get(), "Guarded rules must be configured before the outer build");
        guarded.get().rule("added.before.build", person -> false);
        Validator<Person> validator = parent.build();

        assertSingleErrorAt(validator.validate(new Person()), "#", "added.before.build");
    }

    @Test
    void givenDeepGuardedBuilderChangedBeforeOuterBuildWhenBuildingParentThenIncludesNestedChanges() {
        AtomicReference<Validator.Builder<String>> nested = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        rules -> rules
                                .field("name", Person::getName, name -> name.onlyIf(value -> true, nested::set))
                );

        assertNotNull(nested.get(), "Nested guarded rules must be configured before the outer build");
        nested.get().rule("deep.added.before.build", value -> false);
        Validator<Person> validator = parent.build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.name",
                "deep.added.before.build"
        );
    }

    @Test
    void givenGuardedBuilderChangesBetweenParentBuildsWhenValidatingThenEarlierSnapshotsKeepOriginalRules() {
        AtomicReference<Validator.Builder<Person>> guarded = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class).onlyIf(person -> true, rules -> {
            guarded.set(rules);
            rules.rule("original", person -> false);
        });
        assertNotNull(guarded.get(), "Guarded rules must be configured before the outer build");
        Validator<Person> first = parent.build();

        guarded.get().rule("added.second", person -> false);
        parent.rule("outside.second", person -> false);
        Validator<Person> second = parent.build();
        guarded.get().rule("unbuilt", person -> false);

        assertSingleErrorAt(first.validate(new Person()), "#", "original");
        assertErrorsAt(
                second.validate(new Person()),
                "# [original]",
                "# [added.second]",
                "# [outside.second]"
        );
    }

    @Test
    void givenDeepGuardedBuilderChangesBetweenBuildsWhenValidatingThenEachValidatorKeepsItsRecursiveSnapshot() {
        AtomicReference<Validator.Builder<String>> nested = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .onlyIf(
                        person -> true,
                        rules -> rules.field(
                                "name",
                                Person::getName,
                                name -> name
                                        .onlyIf(value -> true, inner -> {
                                            nested.set(inner);
                                            inner.rule("original", value -> false);
                                        })
                        )
                );
        assertNotNull(nested.get(), "Nested guarded rules must be configured before the outer build");
        Validator<Person> first = parent.build();

        nested.get().rule("added.second", value -> false);
        Validator<Person> second = parent.build();
        nested.get().rule("unbuilt", value -> false);
        Person person = new Person(new Address("Main Street"));

        assertSingleErrorAt(first.validate(person), "#.name", "original");
        assertErrorsAt(second.validate(person), "#.name [original]", "#.name [added.second]");
    }

    @Test
    void givenTrueGuardAndNullAcceptingRuleWhenValidatingNullThenAddsNoImplicitPresenceOrSkippedError() {
        Validator<Person> validator = Validator.builder(Person.class)
                .onlyIf(person -> true, rules -> rules.rule("person.absent", Objects::isNull))
                .build();

        assertTrue(validator.validate(null).isEmpty());
    }

    @Test
    void givenSharedGuardedValidatorWhenValidatingConcurrentlyThenKeepsDecisionsCountsAndNestedResultsCallLocal()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                null,
                new Person(),
                new Person(new Address("Main Street")),
                new Person("Augusta", null, 18, Collections.emptyList())
        );
        String[][] expectedErrors = {
                { "#.name [validation.skipped]" },
                {},
                { "#.name [name.short]", "#.name.length [length.short]" },
                {}
        };
        AtomicInteger guards = new AtomicInteger();
        AtomicInteger guardedRules = new AtomicInteger();
        CyclicBarrier validationBarrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .onlyIf(value -> {
                    guards.incrementAndGet();
                    return value != null;
                },
                        rules -> rules
                                .rule("name.short", value -> {
                                    guardedRules.incrementAndGet();
                                    return value.length() >= 4;
                                })
                                .field(
                                        "length",
                                        String::length,
                                        length -> length.rule("length.short", value -> value >= 4)
                                )
                )
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(person -> {
                    awaitConcurrentValidation(validationBarrier);
                    return true;
                })
                .field("name", Person::getName, name -> name.include(shared))
                .rule(person -> {
                    awaitConcurrentValidation(validationBarrier);
                    return true;
                })
                .build();

        assertConcurrentResults(validator, inputs, expectedErrors);
        assertEquals(3 * CONCURRENT_VALIDATIONS_PER_INPUT, guards.get());
        assertEquals(2 * CONCURRENT_VALIDATIONS_PER_INPUT, guardedRules.get());
    }

    // P014: field(name, extractor, rules). Attribute checks are in P011.

    @Test
    void givenMethodReferenceWhenConfiguringFieldThenUsesTypedChildBuilderAndReturnsParentBuilder() {
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        Validator.Builder<Person> returned = parent.field(
                "address",
                Person::getAddress,
                address -> address.rule(value -> value.getStreetName() != null)
        );

        assertSame(parent, returned);
        Validator<Person> validator = returned.rule(person -> person.getName() != null).build();
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address", "rule.unsatisfied");
    }

    @Test
    void givenTypedLambdaExtractorWhenConfiguringFieldThenInfersStringScopeWithoutClassTokenOrCast() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", person -> person.getName(), name -> name.rule(value -> value.length() > 3))
                .build();

        assertTrue(validator.validate(new Person("Augusta", null, 18, Collections.emptyList())).isEmpty());
        assertSingleErrorAt(validator.validate(new Person(new Address("Main Street"))), "#.name", "rule.unsatisfied");
    }

    @Test
    void givenPrimitiveGetterWhenConfiguringGenericFieldThenExposesBoxedValueToTypedRules() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("age", Person::getAge, (Validator.Builder<Integer> age) -> age.rule(value -> value >= 18))
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        Person underage = new Person("Ada", null, 17, Collections.emptyList());
        assertSingleErrorAt(validator.validate(underage), "#.age", "rule.unsatisfied");
    }

    @Test
    void givenSupertypeInputExtractorWhenConfiguringFieldThenValidatesTheExtractedChild() {
        Function<CharSequence, Integer> length = CharSequence::length;
        Validator<String> validator = Validator.builder(String.class)
                .field("length", length, child -> child.rule(value -> value >= 3))
                .build();

        assertTrue(validator.validate("Ada").isEmpty());
        assertSingleErrorAt(validator.validate("Al"), "#.length", "rule.unsatisfied");
    }

    @Test
    void givenSubtypeExtractorResultWhenConfiguringSupertypeChildBuilderThenRetainsTypedRules() {
        Function<String, String> identity = Function.identity();
        Consumer<Validator.Builder<CharSequence>> nonEmpty = child -> child.rule(value -> value.length() > 0);
        Validator<String> validator = Validator.builder(String.class)
                .field("value", identity, nonEmpty)
                .build();

        assertTrue(validator.validate("Ada").isEmpty());
        assertSingleErrorAt(validator.validate(""), "#.value", "rule.unsatisfied");
    }

    @Test
    void givenSupertypeBuilderConsumerWhenConfiguringFieldThenAppliesRulesAtChildNode() {
        Consumer<Validator.Builder<?>> requirePresence = child -> child.notNull("address.required");
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, requirePresence)
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "address.required");
    }

    @Test
    void givenRootAndSiblingFieldFailuresWhenValidatingThenCollectsAllAtTheirOwnNodes() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person.invalid", person -> false)
                .field("address", Person::getAddress, address -> address.rule("address.invalid", value -> false))
                .field("name", Person::getName, name -> name.rule("name.invalid", value -> false))
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "# [person.invalid]",
                "#.address [address.invalid]",
                "#.name [name.invalid]"
        );
    }

    @Test
    void givenNestedFieldsWhenValidatingThenComposesDeclaredNamesExactlyOnce() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "postalAddress",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "road",
                                        Address::getStreetName,
                                        street -> street
                                                .rule("street.invalid", "Street is invalid", value -> false)
                                )
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.postalAddress.road",
                "street.invalid"
        );
        assertEquals("Street is invalid", error.getMessage());
    }

    @Test
    void givenRepeatedFieldNamesAtDifferentLevelsWhenValidatingThenKeepsEveryStructuralSegment() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "value",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "value",
                                        Address::getStreetName,
                                        street -> street
                                                .field("value", String::length, length -> length.rule(value -> false))
                                )
                )
                .build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.value.value.value",
                "rule.unsatisfied"
        );
    }

    @Test
    void givenMultipleChildRulesAndRepeatedCallsWhenValidatingThenConfiguresOnceAndExtractsOncePerCall() {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                }, address -> {
                    configurations.incrementAndGet();
                    address.rule(value -> {
                        evaluations.incrementAndGet();
                        return value.getStreetName() != null;
                    }).rule(value -> {
                        evaluations.incrementAndGet();
                        return value.getStreetName().length() >= 3;
                    });
                })
                .build();

        assertEquals(1, configurations.get());
        assertEquals(0, extractions.get());
        assertEquals(0, evaluations.get());

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertEquals(1, extractions.get());
        assertEquals(2, evaluations.get());

        assertSingleErrorAt(validator.validate(new Person(new Address("A"))), "#.address", "rule.unsatisfied");
        assertEquals(1, configurations.get());
        assertEquals(2, extractions.get());
        assertEquals(4, evaluations.get());
    }

    @Test
    void givenMultipleParentBuildsWhenBuildingThenDoesNotRerunFieldConfiguration() {
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                    configurations.incrementAndGet();
                    address.rule("address.invalid", value -> false);
                });

        Validator<Person> first = parent.build();
        Validator<Person> second = parent.build();

        assertEquals(1, configurations.get());
        assertSingleErrorAt(first.validate(new Person(null)), "#.address", "address.invalid");
        assertSingleErrorAt(second.validate(new Person(null)), "#.address", "address.invalid");
        assertEquals(1, configurations.get());
    }

    @Test
    void givenChildBuilderChangesAfterParentBuildWhenValidatingThenEarlierTemplateKeepsSnapshot() {
        AtomicReference<Validator.Builder<Address>> child = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                    child.set(address);
                    address.rule("original", value -> false);
                });
        Validator<Person> validator = parent.build();

        child.get().rule("added.later", value -> false);
        parent.rule("root.added.later", value -> false);

        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "original");
    }

    @Test
    void givenChildBuilderChangedAfterFieldRegistrationWhenBuildingParentThenIncludesChangesMadeBeforeBuild() {
        AtomicReference<Validator.Builder<Address>> child = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, child::set);

        child.get().rule("address.added.before.build", value -> false);

        Validator<Person> validator = parent.build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address",
                "address.added.before.build"
        );
    }

    @Test
    void givenGrandchildBuilderChangedBeforeOuterBuildWhenBuildingParentThenIncludesDeeplyNestedChanges() {
        AtomicReference<Validator.Builder<String>> grandchild = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, grandchild::set)
                );

        grandchild.get().rule("street.added.before.build", value -> false);

        Validator<Person> validator = parent.build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address.streetName",
                "street.added.before.build"
        );
    }

    @Test
    void givenChildBuilderChangesBetweenOuterBuildsWhenValidatingThenEachValidatorKeepsItsOwnSnapshot() {
        AtomicReference<Validator.Builder<Address>> child = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                    child.set(address);
                    address.rule("original", value -> false);
                });

        Validator<Person> first = parent.build();
        child.get().rule("second.build", value -> false);
        Validator<Person> second = parent.build();
        child.get().rule("third.build", value -> false);
        Validator<Person> third = parent.build();
        child.get().rule("unbuilt", value -> false);

        Person person = new Person(new Address("Main Street"));
        assertAll(
                () -> assertErrorsAt(first.validate(person), "#.address [original]"),
                () -> assertErrorsAt(second.validate(person), "#.address [original]", "#.address [second.build]"),
                () -> assertErrorsAt(
                        third.validate(person),
                        "#.address [original]",
                        "#.address [second.build]",
                        "#.address [third.build]"
                )
        );
    }

    @Test
    void givenGrandchildBuilderChangesBetweenOuterBuildsWhenValidatingThenEachValidatorKeepsItsDeepSnapshot() {
        AtomicReference<Validator.Builder<String>> grandchild = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> {
                                    grandchild.set(street);
                                    street.rule("original", value -> false);
                                })
                );

        Validator<Person> first = parent.build();
        grandchild.get().rule("second.build", value -> false);
        Validator<Person> second = parent.build();
        grandchild.get().rule("third.build", value -> false);
        Validator<Person> third = parent.build();
        grandchild.get().rule("unbuilt", value -> false);

        Person person = new Person(new Address("Main Street"));
        assertAll(
                () -> assertErrorsAt(first.validate(person), "#.address.streetName [original]"),
                () -> assertErrorsAt(
                        second.validate(person),
                        "#.address.streetName [original]",
                        "#.address.streetName [second.build]"
                ),
                () -> assertErrorsAt(
                        third.validate(person),
                        "#.address.streetName [original]",
                        "#.address.streetName [second.build]",
                        "#.address.streetName [third.build]"
                )
        );
    }

    @Test
    void givenTwoFieldsSharingAnObjectWhenValidatingThenUsesFreshChildBuildersAndDistinctPaths() {
        AtomicReference<Validator.Builder<Address>> billing = new AtomicReference<>();
        AtomicReference<Validator.Builder<Address>> shipping = new AtomicReference<>();
        Address sharedAddress = new Address("Main Street");
        Validator<Person> validator = Validator.builder(Person.class)
                .field("billingAddress", Person::getAddress, address -> {
                    billing.set(address);
                    address.rule("billing.invalid", value -> false);
                })
                .field("shippingAddress", Person::getAddress, address -> {
                    shipping.set(address);
                    address.rule("shipping.invalid", value -> false);
                })
                .build();

        assertNotNull(billing.get());
        assertNotNull(shipping.get());
        assertNotSame(billing.get(), shipping.get());
        assertErrorsAt(
                validator.validate(new Person(sharedAddress)),
                "#.billingAddress [billing.invalid]",
                "#.shippingAddress [shipping.invalid]"
        );
    }

    @Test
    void givenTwoConditionsForSameFieldWhenValidatingThenExtractsForEachAndDoesNotDeduplicateFailures() {
        AtomicInteger extractions = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            extractions.incrementAndGet();
            return person.getAddress();
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.rule("address.invalid", value -> false))
                .field("address", extractor, address -> address.rule("address.invalid", value -> false))
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address [address.invalid]",
                "#.address [address.invalid]"
        );
        assertEquals(2, extractions.get());
    }

    @Test
    void givenNullParentWhenValidatingThenSkipsFieldOnceWithoutCallingExtractorOrDescendants() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                },
                        address -> address
                                .notNull()
                                .rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                })
                                .field("streetName", value -> {
                                    descendants.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull())
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
        assertNonblankMessage(error);
        assertEquals(0, extractions.get());
        assertEquals(0, descendants.get());
    }

    @Test
    void givenNullRootAndTwoFieldsWhenValidatingThenReportsRootPresenceAndOneSkipPerField() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .field("address", Person::getAddress, address -> address.notNull().rule(value -> false))
                .field("name", Person::getName, name -> name.notNull())
                .build();

        assertErrorsAt(
                validator.validate(null),
                "# [notNull]",
                "#.address [validation.skipped]",
                "#.name [validation.skipped]"
        );
    }

    @Test
    void givenNullExtractedValueWhenChildRulesAreNullAwareThenPassesNullIntoEveryPredicate() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .rule("address.absent", value -> {
                                    evaluations.incrementAndGet();
                                    assertNull(value);
                                    return true;
                                })
                                .rule("address.required", value -> {
                                    evaluations.incrementAndGet();
                                    assertNull(value);
                                    return false;
                                })
                )
                .build();

        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "address.required");
        assertEquals(2, evaluations.get());
    }

    @Test
    void givenNullExtractedValueWhenUsingAllNotNullAndRuleOverloadsThenReportsMetadataAtFieldNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .notNull("address.required")
                                .notNull("address.custom", "Address is required")
                                .rule(Objects::nonNull)
                                .rule("address.rule", Objects::nonNull)
                                .rule("address.rule.custom", "Address rule failed", Objects::nonNull)
                )
                .build();

        List<ValidationError> errors = validator.validate(new Person(null));

        assertErrorsAt(
                errors,
                "#.address [notNull]",
                "#.address [address.required]",
                "#.address [address.custom]",
                "#.address [rule.unsatisfied]",
                "#.address [address.rule]",
                "#.address [address.rule.custom]"
        );
        errors.forEach(ValidatorTest::assertNonblankMessage);
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.custom".equals(error.getCode())
                                        && "Address is required".equals(error.getMessage())
                        )
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.rule.custom".equals(error.getCode())
                                        && "Address rule failed".equals(error.getMessage())
                        )
        );
    }

    @Test
    void givenNullExtractedValueWhenOnlyNullAcceptingRuleIsConfiguredThenAddsNoImplicitPresenceError() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(Objects::isNull))
                .build();

        assertTrue(validator.validate(new Person(null)).isEmpty());
    }

    @Test
    void givenNullAddressWhenValidatingNestedFieldThenReportsAddressPresenceAndOneDescendantSkip() {
        AtomicInteger nestedExtractions = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .field("streetName", value -> {
                                    nestedExtractions.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull().rule(value -> false))
                )
                .build();

        assertErrorsAt(
                validator.validate(new Person(null)),
                "#.address [notNull]",
                "#.address.streetName [validation.skipped]"
        );
        assertEquals(0, nestedExtractions.get());
    }

    @Test
    void givenPresentAddressWithNullStreetWhenValidatingThenRunsStreetRulesAtNestedNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .build();

        assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address.streetName", "notNull");
    }

    @Test
    void givenNullExtractedValueWhenChildPredicateDereferencesItThenRecordsExecutionFailureAtField() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(value -> value.getStreetName() != null))
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(null)),
                "#.address",
                "validation.executionFailed"
        );
        assertTrue(error.getMessage().contains(NullPointerException.class.getName()));
    }

    @Test
    void givenThrowingExtractorWhenValidatingThenReportsOnceAndContinuesIndependentRules() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        AtomicInteger independentRules = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            extractions.incrementAndGet();
            throw new IllegalStateException("address lookup failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        extractor,
                        address -> address
                                .notNull()
                                .rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                })
                                .field("streetName", value -> {
                                    descendants.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull())
                )
                .field("name", Person::getName, name -> name.rule("name.invalid", value -> {
                    independentRules.incrementAndGet();
                    return false;
                }))
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person(null));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.name [name.invalid]",
                "# [person.invalid]"
        );
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing extractor failure"));
        assertTrue(executionError.getMessage().contains(IllegalStateException.class.getName()));
        assertTrue(executionError.getMessage().contains("address lookup failed"));
        assertEquals(1, extractions.get());
        assertEquals(0, descendants.get());
        assertEquals(2, independentRules.get());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "address lookup failed" })
    void givenAnonymousExtractorExceptionWhenValidatingThenReportsBinaryClassNameAtFieldAndBlocksOnlyItsDescendants(
            String message
    ) {
        RuntimeException failure = new RuntimeException(message) {
            private static final long serialVersionUID = 1L;
        };
        AtomicInteger descendants = new AtomicInteger();
        AtomicInteger independentRules = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            throw failure;
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.rule(value -> {
                    descendants.incrementAndGet();
                    return false;
                }))
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(() -> validator.validate(new Person(null)));

        assertErrorsAt(errors, "#.address [validation.executionFailed]", "# [person.invalid]");
        assertEquals(0, descendants.get());
        assertEquals(1, independentRules.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing extractor failure"));
        assertExceptionDetails(executionError, failure.getClass(), message);
    }

    @Test
    void givenExtractorExceptionWithThrowingMessageWhenValidatingThenReportsBinaryClassNameAndContinuesIndependentRules() {
        AtomicInteger independentRules = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            throw new ThrowingMessageException();
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.notNull())
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(() -> validator.validate(new Person(null)));

        assertErrorsAt(errors, "#.address [validation.executionFailed]", "# [person.invalid]");
        assertEquals(1, independentRules.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing extractor failure"));
        assertExceptionDetails(executionError, ThrowingMessageException.class, null);
    }

    @Test
    void givenThrowingNestedExtractorWhenValidatingThenReportsExecutionFailureAtNestedField() {
        AtomicInteger descendants = new AtomicInteger();
        Function<Address, String> extractor = address -> {
            throw new IllegalArgumentException("street lookup failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", extractor, street -> street.rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                }))
                                .rule("address.invalid", value -> false)
                )
                .rule("person.invalid", person -> false)
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address.streetName [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        assertEquals(0, descendants.get());
    }

    @Test
    void givenThrowingChildPredicateWhenValidatingThenContinuesOtherChildAndRootRules() {
        AtomicInteger nestedEvaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .rule(value -> {
                                    throw new IllegalStateException("child rule failed");
                                })
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street.rule("street.invalid", value -> {
                                            nestedEvaluations.incrementAndGet();
                                            return false;
                                        })
                                )
                )
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.address.streetName [street.invalid]",
                "# [person.invalid]"
        );
        assertEquals(1, nestedEvaluations.get());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "child rule failed" })
    void givenAnonymousChildPredicateExceptionWhenValidatingThenReportsBinaryClassNameAtChildAndContinuesIndependentRules(
            String message
    ) {
        RuntimeException failure = new RuntimeException(message) {
            private static final long serialVersionUID = 1L;
        };
        AtomicInteger independentRules = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .rule(value -> {
                                    throw failure;
                                })
                                .rule("address.invalid", value -> {
                                    independentRules.incrementAndGet();
                                    return false;
                                })
                )
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(
                () -> validator.validate(new Person(new Address("Main Street")))
        );

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        assertEquals(2, independentRules.get());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing child execution failure"));
        assertExceptionDetails(executionError, failure.getClass(), message);
    }

    @Test
    void givenExtractorThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal extractor failure");
        Function<Person, Address> extractor = person -> {
            throw failure;
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.notNull())
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person(null))));
    }

    @Test
    void givenChildPredicateThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal child failure");
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(value -> {
                    throw failure;
                }))
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person(null))));
    }

    @Test
    void givenEmptyChildTemplateWhenParentExistsThenAcceptsPresentOrNullExtractedValue() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                })
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertTrue(validator.validate(new Person(null)).isEmpty());
    }

    @Test
    void givenEmptyChildTemplateWhenParentIsNullThenStillReportsBlockedExtraction() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                })
                .build();

        assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
    }

    @Test
    void givenWholeCollectionFieldWhenValidatingThenRulesReceiveTheCollectionAtOneNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "aliases",
                        Person::getAliases,
                        (Validator.Builder<List<String>> aliases) -> aliases
                                .rule("aliases.minimum", "At least two aliases", values -> values.size() >= 2)
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(null)),
                "#.aliases",
                "aliases.minimum"
        );
        assertEquals("At least two aliases", error.getMessage());
        Person twoAliases = new Person("Ada", null, 18, Arrays.asList("Ada", "Augusta"));
        assertTrue(validator.validate(twoAliases).isEmpty());
    }

    @Test
    void givenRepeatedNestedValidationWhenInputsChangeThenResultsRemainImmutableAndCallLocal() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .build();

        List<ValidationError> first = validator.validate(new Person(new Address(null)));
        List<ValidationError> second = validator.validate(new Person(new Address("Main Street")));
        List<ValidationError> third = validator.validate(new Person(new Address(null)));

        assertSingleErrorAt(first, "#.address.streetName", "notNull");
        assertTrue(second.isEmpty());
        assertSingleErrorAt(third, "#.address.streetName", "notNull");
        assertNotSame(first, third);
        assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> second.add(first.get(0)));
    }

    @Test
    void givenNullOrBlankFieldNameWhenConfiguringFieldThenRejectsConfiguration() {
        for (String name : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class)
                            .field(name, Person::getAddress, address -> address.notNull())
                            .build()
            );
        }
    }

    @Test
    void givenBlankFieldNameWhenConfiguringFieldThenRejectsConfigurationWithoutInvokingRulesCallback() {
        for (String name : new String[] { "", "   ", "\t\r\n" }) {
            AtomicInteger configurations = new AtomicInteger();
            Validator.Builder<Person> parent = Validator.builder(Person.class);

            assertThrows(
                    RuntimeException.class,
                    () -> parent.field(name, Person::getAddress, address -> {
                        configurations.incrementAndGet();
                        address.notNull();
                    })
            );

            assertEquals(0, configurations.get(), "Rules callback must not run for a blank field name");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "\u2003", "\u3000", "\t \u2003\u3000\r\n" })
    void givenUnicodeWhitespaceOnlyFieldNameWhenConfiguringFieldThenRejectsConfigurationWithoutInvokingRulesCallback(
            String name
    ) {
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        assertThrows(
                RuntimeException.class,
                () -> parent.field(name, Person::getAddress, address -> {
                    configurations.incrementAndGet();
                    address.notNull();
                })
        );

        assertEquals(0, configurations.get(), "Rules callback must not run for a Unicode whitespace-only field name");
    }

    @ParameterizedTest
    @ValueSource(strings = { " postalAddress ", "\u2003postalAddress\u3000", "postal address" })
    void givenNonblankFieldNameContainingWhitespaceWhenValidatingThenPreservesDeclaredNameInPath(String name) {
        AtomicInteger configurations = new AtomicInteger();
        Validator<Person> validator = assertDoesNotThrow(
                () -> Validator.builder(Person.class)
                        .field(name, Person::getAddress, address -> {
                            configurations.incrementAndGet();
                            address.notNull("address.required");
                        })
                        .build()
        );

        assertEquals(1, configurations.get());
        assertSingleErrorAt(validator.validate(new Person(null)), "#." + name, "address.required");
    }

    @Test
    void givenReservedPathCharactersInFieldNameWhenConfiguringFieldThenRejectsConfiguration() {
        for (
            String name : new String[] { "address.street", "address[0]", "address]", "#address", ".", "[", "]", "#" }
        ) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class)
                            .field(name, Person::getAddress, address -> address.notNull())
                            .build()
            );
        }
    }

    @Test
    void givenNullExtractorWhenConfiguringFieldThenRejectsConfiguration() {
        Function<Person, Address> extractor = null;

        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).field("address", extractor, address -> address.notNull()).build()
        );
    }

    @Test
    void givenNullExtractorWhenConfiguringFieldThenRejectsConfigurationWithoutInvokingRulesCallback() {
        Function<Person, Address> extractor = null;
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        assertThrows(
                RuntimeException.class,
                () -> parent.field("address", extractor, address -> {
                    configurations.incrementAndGet();
                    address.notNull();
                })
        );

        assertEquals(0, configurations.get(), "Rules callback must not run for a null extractor");
    }

    @Test
    void givenNullRulesCallbackWhenConfiguringFieldThenRejectsConfiguration() {
        Consumer<Validator.Builder<Address>> rules = null;

        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).field("address", Person::getAddress, rules).build()
        );
    }

    @Test
    void givenInvalidChildRuleWhenConfiguringFieldThenRejectsTemplateConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class)
                        .field("address", Person::getAddress, address -> address.rule("", value -> true))
        );
    }

    @Test
    void givenThrowingRulesCallbackWhenConfiguringFieldThenPropagatesSameConfigurationFailure() {
        IllegalStateException failure = new IllegalStateException("invalid child configuration");
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        assertSame(
                failure,
                assertThrows(IllegalStateException.class, () -> parent.field("address", Person::getAddress, address -> {
                    throw failure;
                }))
        );
    }

    // P015: custom(callback). ErrorSink reporting and derived targets are
    // introduced in P016-P021; these tests use only the sink's identity.

    @Test
    void givenTypedCustomCallbackWhenRegisteringThenReturnsTheSameBuilderAndPreservesParentChaining() {
        Validator.Builder<Person> builder = Validator.builder(Person.class);
        AtomicReference<Person> observedValue = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> observedSink = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();

        Validator.Builder<Person> returned = builder.custom((person, errors) -> {
            observedValue.set(person);
            observedSink.set(errors);
            calls.incrementAndGet();
        });

        assertSame(builder, returned);
        Validator<Person> validator = returned
                .field("name", Person::getName, name -> name.notNull())
                .build();
        Person person = new Person(new Address("Main Street"));
        assertTrue(validator.validate(person).isEmpty());
        assertEquals(1, calls.get());
        assertSame(person, observedValue.get());
        assertNotNull(observedSink.get());
    }

    @Test
    void givenCustomCallbackWhenRegisteringAndBuildingRepeatedlyThenExecutesOnlyDuringEachValidation() {
        AtomicInteger calls = new AtomicInteger();
        Validator.Builder<String> builder = Validator.builder(String.class);

        builder.custom((value, errors) -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        Validator<String> first = builder.build();
        Validator<String> second = builder.build();
        assertEquals(0, calls.get());

        assertTrue(first.validate("Ada").isEmpty());
        assertEquals(1, calls.get());
        assertTrue(second.validate("Ada").isEmpty());
        assertEquals(2, calls.get());
        assertTrue(first.validate("Ada").isEmpty());
        assertEquals(3, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullCustomCallbackWhenRegisteringAtRootOrFieldThenRejectsConfiguration(boolean fieldScope) {
        assertThrows(RuntimeException.class, () -> {
            if (fieldScope) {
                Validator.builder(Person.class).field("name", Person::getName, name -> name.custom(null));
            } else {
                Validator.builder(String.class).custom(null);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullCurrentNodeWhenExecutingCustomAtRootOrFieldThenPassesNullAndANonNullSink(boolean fieldScope) {
        AtomicReference<String> observedValue = new AtomicReference<>("not visited");
        AtomicReference<Validator.ErrorSink> observedSink = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    observedValue.set(value);
                    observedSink.set(errors);
                    calls.incrementAndGet();
                })
                .build();

        assertTrue(validateAtRootOrNameField(validator, fieldScope, null).isEmpty());

        assertEquals(1, calls.get());
        assertNull(observedValue.get());
        assertNotNull(observedSink.get());
    }

    @Test
    void givenNestedCustomCallbacksWhenValidatingThenReceivesTheExtractedValuesWithTheirDeclaredTypes() {
        AtomicReference<Address> observedAddress = new AtomicReference<>();
        AtomicReference<String> observedStreet = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Address address = new Address("Main Street");
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        rules -> rules
                                .custom((value, errors) -> {
                                    observedAddress.set(value);
                                    calls.incrementAndGet();
                                })
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street
                                                .custom((value, errors) -> {
                                                    observedStreet.set(value);
                                                    calls.incrementAndGet();
                                                })
                                )
                )
                .build();

        assertTrue(validator.validate(new Person(address)).isEmpty());

        assertEquals(2, calls.get());
        assertSame(address, observedAddress.get());
        assertSame(address.getStreetName(), observedStreet.get());
    }

    @Test
    void givenGenericCollectionFieldWhenExecutingCustomThenReceivesTheEntireTypedCollection() {
        List<String> aliases = Arrays.asList("Ada", "Augusta");
        AtomicReference<List<String>> observed = new AtomicReference<>();
        AtomicInteger totalLength = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("aliases", Person::getAliases, rules -> rules.custom((values, errors) -> {
                    observed.set(values);
                    totalLength.set(values.get(0).length() + values.get(1).length());
                }))
                .build();

        assertTrue(validator.validate(new Person("Ada", null, 18, aliases)).isEmpty());

        assertSame(aliases, observed.get());
        assertEquals(10, totalLength.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenMissingParentWhenCustomIsInsideABlockedFieldThenSkipsTheFieldAndRunsIndependentCustom(
            boolean nullRoot
    ) {
        AtomicInteger blockedCalls = new AtomicInteger();
        AtomicInteger independentCalls = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street
                                                .custom((value, errors) -> blockedCalls.incrementAndGet())
                                )
                )
                .custom((value, errors) -> independentCalls.incrementAndGet())
                .build();

        assertSingleErrorAt(
                validator.validate(nullRoot ? null : new Person(null)),
                nullRoot ? "#.address" : "#.address.streetName",
                "validation.skipped"
        );
        assertEquals(0, blockedCalls.get());
        assertEquals(1, independentCalls.get());
    }

    @Test
    void givenFalseGuardOnNullWhenValidatingThenDoesNotRunGuardedCustomOrReportItsPotentialFailure() {
        AtomicInteger guardedCalls = new AtomicInteger();
        AtomicInteger independentCalls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> value != null, rules -> rules.custom((value, errors) -> {
                    guardedCalls.incrementAndGet();
                    throw new IllegalStateException("must not run");
                }))
                .custom((value, errors) -> independentCalls.incrementAndGet())
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(0, guardedCalls.get());
        assertEquals(1, independentCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenTrueGuardWhenCustomIsAtRootOrFieldThenRunsAtTheCurrentNodeWithoutAddingAPathSegment(
            boolean fieldScope
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> true, rules -> rules.custom((value, errors) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("guarded custom failed");
                }))
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, null),
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );
        assertEquals(1, calls.get());
        assertExceptionDetails(error, IllegalStateException.class, "guarded custom failed");
    }

    @Test
    void givenThrowingGuardWhenValidatingThenBlocksItsCustomCallbackAndRunsIndependentCustom() {
        AtomicInteger blockedCalls = new AtomicInteger();
        AtomicInteger independentCalls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> {
                    throw new IllegalStateException("guard failed");
                }, rules -> rules.custom((value, errors) -> blockedCalls.incrementAndGet()))
                .custom((value, errors) -> independentCalls.incrementAndGet())
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "validation.executionFailed");

        assertExceptionDetails(error, IllegalStateException.class, "guard failed");
        assertEquals(0, blockedCalls.get());
        assertEquals(1, independentCalls.get());
    }

    @Test
    void givenThrowingExtractorWhenValidatingThenBlocksItsCustomCallbackAndRunsIndependentCustom() {
        AtomicInteger blockedCalls = new AtomicInteger();
        AtomicInteger independentCalls = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            throw new IllegalStateException("extractor failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        extractor,
                        address -> address
                                .custom((value, errors) -> blockedCalls.incrementAndGet())
                )
                .custom((value, errors) -> independentCalls.incrementAndGet())
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person()),
                "#.address",
                "validation.executionFailed"
        );

        assertExceptionDetails(error, IllegalStateException.class, "extractor failed");
        assertEquals(0, blockedCalls.get());
        assertEquals(1, independentCalls.get());
    }

    @Test
    void givenFailingPresenceAndPredicateRulesWhenValidatingNullThenStillExecutesCustomWithoutAnAutomaticError() {
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .notNull()
                .custom((value, errors) -> calls.incrementAndGet())
                .rule("text.invalid", value -> false)
                .build();

        assertErrorsAt(validator.validate(null), "# [notNull]", "# [text.invalid]");
        assertEquals(1, calls.get());
    }

    @Test
    void givenIndependentThrowingCustomCallbacksAndRulesWhenValidatingThenCollectsEveryFailureWithoutFailFast() {
        AtomicInteger customCalls = new AtomicInteger();
        AtomicInteger ruleCalls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .rule("first.invalid", value -> {
                    ruleCalls.incrementAndGet();
                    return false;
                })
                .custom((value, errors) -> {
                    customCalls.incrementAndGet();
                    throw new IllegalStateException("first custom failed");
                })
                .custom((value, errors) -> {
                    customCalls.incrementAndGet();
                    throw new IllegalArgumentException("second custom failed");
                })
                .rule("last.invalid", value -> {
                    ruleCalls.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(
                errors,
                "# [first.invalid]",
                "# [last.invalid]",
                "# [validation.executionFailed]",
                "# [validation.executionFailed]"
        );
        assertEquals(2, customCalls.get());
        assertEquals(2, ruleCalls.get());
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("first custom failed")));
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("second custom failed")));
    }

    @Test
    void givenTheSameFailingCustomCallbackRegisteredTwiceWhenValidatingThenKeepsBothErrorsAndUsesDistinctSinks() {
        List<Validator.ErrorSink> sinks = new ArrayList<>();
        BiConsumer<String, Validator.ErrorSink> callback = (value, errors) -> {
            sinks.add(errors);
            throw new IllegalStateException("shared custom failed");
        };
        Validator<String> validator = Validator.builder(String.class).custom(callback).custom(callback).build();

        List<ValidationError> errors = validator.validate("Ada");

        assertErrorsAt(errors, "# [validation.executionFailed]", "# [validation.executionFailed]");
        assertEquals(2, sinks.size());
        assertDistinctCustomSinks(sinks);
        errors.forEach(error -> assertExceptionDetails(error, IllegalStateException.class, "shared custom failed"));
    }

    @ParameterizedTest
    @CsvSource(value = { "false,<null>", "false,custom failed", "true,<null>",
            "true,custom failed" }, nullValues = "<null>")
    void givenCustomThrowingRuntimeExceptionWhenValidatingAtRootOrFieldThenReportsOneExecutionErrorWithBinaryClassName(
            boolean fieldScope,
            String message
    ) {
        IllegalStateException failure = new IllegalStateException(message);
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    calls.incrementAndGet();
                    throw failure;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(
                () -> validateAtRootOrNameField(validator, fieldScope, "Ada")
        );

        ValidationError error = assertSingleErrorAt(
                errors,
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );
        assertEquals(1, calls.get());
        assertExceptionDetails(error, failure.getClass(), message);
        assertImmutableAttributes(error);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCustomThrowingAnonymousExceptionWhenValidatingThenPreservesItsNonEmptyBinaryClassName(
            boolean fieldScope
    ) {
        RuntimeException failure = new RuntimeException("anonymous custom failed") {
            private static final long serialVersionUID = 1L;
        };
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    throw failure;
                })
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, fieldScope, "Ada"),
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );

        assertExceptionDetails(error, failure.getClass(), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCustomExceptionWhoseMessageAccessorThrowsWhenValidatingThenStillRecordsTheOriginalExecutionFailure(
            boolean fieldScope
    ) {
        ThrowingMessageException failure = new ThrowingMessageException();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    throw failure;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(
                () -> validateAtRootOrNameField(validator, fieldScope, "Ada")
        );

        ValidationError error = assertSingleErrorAt(
                errors,
                fieldScope ? "#.name" : "#",
                "validation.executionFailed"
        );
        assertExceptionDetails(error, failure.getClass(), null);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCustomThrowingJvmErrorWhenValidatingAtRootOrFieldThenPropagatesTheSameError(boolean fieldScope) {
        AssertionError failure = new AssertionError("custom JVM error");
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    throw failure;
                })
                .build();

        assertSame(
                failure,
                assertThrows(
                        AssertionError.class,
                        () -> validateAtRootOrNameField(validator, fieldScope, "Ada")
                )
        );
    }

    @Test
    void givenSharedCustomValidatorWhenIncludedAtDifferentPathsThenRebasesEachExecutionErrorAndKeepsItReusable() {
        List<Validator.ErrorSink> sinks = new ArrayList<>();
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    sinks.add(errors);
                    throw new IllegalStateException("shared custom failed");
                })
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .field("alias", Person::getName, alias -> alias.onlyIf(value -> true, rules -> rules.include(shared)))
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.include(shared))
                )
                .build();

        List<ValidationError> parentErrors = parent.validate(new Person(new Address("Main Street")));
        List<ValidationError> sharedErrors = shared.validate(null);

        assertErrorsAt(
                parentErrors,
                "#.name [validation.executionFailed]",
                "#.alias [validation.executionFailed]",
                "#.address.streetName [validation.executionFailed]"
        );
        assertSingleErrorAt(sharedErrors, "#", "validation.executionFailed");
        assertEquals(4, sinks.size());
        assertDistinctCustomSinks(sinks);
        parentErrors
                .forEach(error -> assertExceptionDetails(error, IllegalStateException.class, "shared custom failed"));
        assertErrorsAt(
                parentErrors,
                "#.name [validation.executionFailed]",
                "#.alias [validation.executionFailed]",
                "#.address.streetName [validation.executionFailed]"
        );
    }

    @Test
    void givenRepeatedCustomValidationCallsWhenInputsChangeThenCreatesAFreshNonNullSinkForEveryInvocation() {
        List<String> observedValues = new ArrayList<>();
        List<Validator.ErrorSink> sinks = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    observedValues.add(value);
                    sinks.add(errors);
                })
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertTrue(validator.validate("Ada").isEmpty());
        assertTrue(validator.validate(null).isEmpty());

        assertEquals(Arrays.asList(null, "Ada", null), observedValues);
        assertEquals(3, sinks.size());
        assertDistinctCustomSinks(sinks);
    }

    @Test
    void givenCustomCallbacksAtRootAndSiblingFieldsWhenValidatingThenEachInvocationReceivesItsOwnSink() {
        List<Validator.ErrorSink> sinks = new ArrayList<>();
        BiConsumer<Object, Validator.ErrorSink> callback = (value, errors) -> sinks.add(errors);
        Validator<Person> validator = Validator.builder(Person.class)
                .custom(callback)
                .field("name", Person::getName, name -> name.custom(callback))
                .field("alias", Person::getName, alias -> alias.custom(callback))
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());

        assertEquals(3, sinks.size());
        assertDistinctCustomSinks(sinks);
    }

    @Test
    void givenCustomAddedAfterBuildWhenValidatingEarlierAndLaterTemplatesThenKeepsTheirConditionSnapshotsIndependent() {
        AtomicInteger originalCalls = new AtomicInteger();
        AtomicInteger addedCalls = new AtomicInteger();
        Validator.Builder<String> builder = Validator.builder(String.class)
                .custom((value, errors) -> originalCalls.incrementAndGet());
        Validator<String> first = builder.build();
        builder.custom((value, errors) -> {
            addedCalls.incrementAndGet();
            throw new IllegalStateException("added custom failed");
        });
        Validator<String> second = builder.build();

        assertTrue(first.validate("Ada").isEmpty());
        assertSingleErrorAt(second.validate("Ada"), "#", "validation.executionFailed");
        assertTrue(first.validate(null).isEmpty());

        assertEquals(3, originalCalls.get());
        assertEquals(1, addedCalls.get());
    }

    @Test
    void givenCustomAddedToRetainedChildBeforeOuterBuildWhenValidatingThenIncludesItInTheChildSnapshot() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Validator.Builder<Person> builder = Validator.builder(Person.class)
                .field("name", Person::getName, retained::set);
        retained.get().custom((value, errors) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("late custom failed");
        });

        Validator<Person> validator = builder.build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.name",
                "validation.executionFailed"
        );
        assertEquals(1, calls.get());
    }

    @Test
    void givenCustomAddedToRetainedGuardAfterBuildWhenValidatingThenChangesOnlyTheLaterOuterSnapshot() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Validator.Builder<String> builder = Validator.builder(String.class).onlyIf(value -> true, retained::set);
        Validator<String> first = builder.build();
        retained.get().custom((value, errors) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("late guarded custom failed");
        });
        Validator<String> second = builder.build();

        assertTrue(first.validate(null).isEmpty());
        assertSingleErrorAt(second.validate(null), "#", "validation.executionFailed");
        assertTrue(first.validate(null).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void givenFailingThenPassingCustomInputsWhenReusingValidatorThenKeepsAllReturnedResultsIndependentAndImmutable() {
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    calls.incrementAndGet();
                    if (value == null) {
                        throw new IllegalStateException("text missing");
                    }
                })
                .build();

        List<ValidationError> first = validator.validate(null);
        List<ValidationError> second = validator.validate("Ada");
        List<ValidationError> third = validator.validate(null);

        assertSingleErrorAt(first, "#", "validation.executionFailed");
        assertTrue(second.isEmpty());
        assertSingleErrorAt(third, "#", "validation.executionFailed");
        assertEquals(3, calls.get());
        ValidationError extra = new ValidationError("#", "unexpected", "Unexpected error");
        assertThrows(UnsupportedOperationException.class, () -> first.add(extra));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> second.add(extra));
        assertThrows(UnsupportedOperationException.class, () -> third.set(0, extra));
        assertImmutableAttributes(first.get(0));
        assertImmutableAttributes(third.get(0));
        assertExceptionDetails(first.get(0), IllegalStateException.class, "text missing");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCallbackConsumingObjectForBothParametersWhenUsedAtRootOrFieldThenReceivesTheTypedValueAndSink(
            boolean fieldScope
    ) {
        AtomicReference<Object> observedValue = new AtomicReference<>();
        AtomicReference<Object> observedSink = new AtomicReference<>();
        BiConsumer<Object, Object> callback = (value, errors) -> {
            observedValue.set(value);
            observedSink.set(errors);
        };
        Validator<String> validator = Validator.builder(String.class).custom(callback).build();

        assertTrue(validateAtRootOrNameField(validator, fieldScope, "Ada").isEmpty());

        assertEquals("Ada", observedValue.get());
        assertTrue(observedSink.get() instanceof Validator.ErrorSink);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCallbackConsumingCharSequenceWhenUsedInAStringScopeThenAcceptsTheSupertypeWithoutCasts(
            boolean fieldScope
    ) {
        AtomicReference<CharSequence> observed = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> observedSink = new AtomicReference<>();
        BiConsumer<CharSequence, Validator.ErrorSink> callback = (value, errors) -> {
            observed.set(value);
            observedSink.set(errors);
        };
        Validator<String> validator = Validator.builder(String.class).custom(callback).build();

        assertTrue(validateAtRootOrNameField(validator, fieldScope, "Ada").isEmpty());

        assertEquals("Ada", observed.get());
        assertNotNull(observedSink.get());
    }

    @Test
    void givenWildcardTypedCustomCallbackWhenBuildingThenPreservesTypedChainingAndExecutesTheCallback() {
        AtomicInteger calls = new AtomicInteger();
        BiConsumer<Object, Object> broaderCallback = (value, errors) -> calls.incrementAndGet();
        BiConsumer<? super String, ? super Validator.ErrorSink> callback = broaderCallback;
        Validator<String> validator = Validator.builder(String.class)
                .custom(callback)
                .rule(value -> value.length() > 0)
                .build();

        assertTrue(validator.validate("Ada").isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void givenSharedCustomValidatorWhenValidatingConcurrentlyThenKeepsSinksAndErrorResultsIsolated() throws Exception {
        List<String> inputs = Arrays.asList(null, "", "Ada", "Augusta");
        String[][] expectedErrors = {
                { "# [validation.executionFailed]" },
                { "# [validation.executionFailed]" },
                {},
                {}
        };
        List<Validator.ErrorSink> sinks = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier barrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    sinks.add(errors);
                    awaitConcurrentValidation(barrier);
                    if (value == null || value.isEmpty()) {
                        throw new IllegalStateException("text missing");
                    }
                })
                .build();
        Validator<String> validator = Validator.builder(String.class).include(shared).build();

        assertConcurrentResults(validator, inputs, expectedErrors);

        assertEquals(inputs.size() * CONCURRENT_VALIDATIONS_PER_INPUT, sinks.size());
        assertDistinctCustomSinks(sinks);
    }

    // P016: ErrorSink.add(). Metadata overloads and derived field/index targets
    // remain in P017-P021. A retained sink expires when its callback finishes.

    @Test
    void whenInspectingErrorSinkThenExposesAPublicNoArgumentAddMethod() throws NoSuchMethodException {
        assertNotNull(Validator.ErrorSink.class.getMethod("add"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "Ada" })
    void givenOneAddCallWhenValidatingANullOrPresentRootThenReportsOneGenericErrorAtRoot(String value) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((current, errors) -> errors.add())
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(value), "#", "rule.unsatisfied");

        assertDefaultCustomError(error, "#");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCustomThatMayCallAddWhenValidatingThenReportsOnlyExplicitlyAddedErrors(boolean report) {
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    calls.incrementAndGet();
                    if (report) {
                        errors.add();
                    }
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, calls.get());
        if (report) {
            assertDefaultCustomError(assertSingleErrorAt(errors, "#", "rule.unsatisfied"), "#");
        } else {
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void givenRootCustomInspectingMultipleFieldsWhenItAddsAnErrorThenReportsAtTheCurrentObjectNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .custom((person, errors) -> {
                    if (person.getName() == null && person.getAddress() != null) {
                        errors.add();
                    }
                })
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(null, new Address("Main Street"), 18, Collections.emptyList())),
                "#",
                "rule.unsatisfied"
        );

        assertDefaultCustomError(error, "#");
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNullRootOrExtractedValueWhenCustomAddsThenReportsOnlyAtReachedNodesAndSkipsBlockedDescendants(
            boolean nullRoot
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .custom((value, errors) -> errors.add())
                .field("name", Person::getName, name -> name.custom((value, errors) -> {
                    calls.incrementAndGet();
                    errors.add();
                }))
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .custom((value, errors) -> {
                                    calls.incrementAndGet();
                                    errors.add();
                                })
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street.custom((value, errors) -> {
                                            calls.incrementAndGet();
                                            errors.add();
                                        })
                                )
                )
                .build();

        List<ValidationError> errors = validator.validate(nullRoot ? null : new Person());

        if (nullRoot) {
            assertErrorsAt(
                    errors,
                    "# [rule.unsatisfied]",
                    "#.name [validation.skipped]",
                    "#.address [validation.skipped]"
            );
            assertEquals(0, calls.get());
        } else {
            assertErrorsAt(
                    errors,
                    "# [rule.unsatisfied]",
                    "#.name [rule.unsatisfied]",
                    "#.address [rule.unsatisfied]",
                    "#.address.streetName [validation.skipped]"
            );
            assertEquals(2, calls.get());
        }
        errors.stream()
                .filter(error -> "rule.unsatisfied".equals(error.getCode()))
                .forEach(error -> assertDefaultCustomError(error, error.getPath()));
    }

    @Test
    void givenNestedFieldCustomWhenItAddsThenUsesTheCompleteTraversalPathExactlyOnce() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street.custom((value, errors) -> errors.add())
                                )
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address.streetName",
                "rule.unsatisfied"
        );

        assertDefaultCustomError(error, "#.address.streetName");
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", " name ", "postal address" })
    void givenNamedFieldWhenCustomAddsThenPreservesTheExactFieldNameInTheErrorPath(String name) {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(name, Person::getName, value -> value.custom((current, errors) -> errors.add()))
                .build();

        assertDefaultCustomError(
                assertSingleErrorAt(validator.validate(new Person()), "#." + name, "rule.unsatisfied"),
                "#." + name
        );
    }

    @Test
    void givenCollectionExtractedAsOneFieldWhenCustomAddsForSeveralMembersThenReportsEveryErrorAtTheCollectionNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("aliases", Person::getAliases, aliases -> aliases.custom((values, errors) -> {
                    for (String value : values) {
                        if (value == null) {
                            errors.add();
                        }
                    }
                }))
                .build();

        List<ValidationError> errors = validator.validate(
                new Person("Ada", null, 18, Arrays.asList(null, "Ada", null))
        );

        assertErrorsAt(errors, "#.aliases [rule.unsatisfied]", "#.aliases [rule.unsatisfied]");
        errors.forEach(error -> assertDefaultCustomError(error, "#.aliases"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void givenRepeatedAddCallsInOneCallbackWhenValidatingThenKeepsEveryGenericErrorWithoutDeduplication(int count) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    for (int i = 0; i < count; i++) {
                        errors.add();
                    }
                })
                .build();

        List<ValidationError> errors = validator.validate("Ada");

        assertEquals(count, errors.size());
        errors.forEach(error -> assertDefaultCustomError(error, "#"));
        assertTrue(errors.stream().allMatch(error -> errors.get(0).getMessage().equals(error.getMessage())));
    }

    @Test
    void givenTheSameReportingCallbackRegisteredTwiceWhenValidatingThenRetainsBothErrors() {
        BiConsumer<String, Validator.ErrorSink> callback = (value, errors) -> errors.add();
        Validator<String> validator = Validator.builder(String.class).custom(callback).custom(callback).build();

        assertErrorsAt(validator.validate(null), "# [rule.unsatisfied]", "# [rule.unsatisfied]");
    }

    @Test
    void givenReportingCallbacksAndIndependentFailingRulesWhenValidatingThenCollectsAllFailuresWithoutFailFast() {
        AtomicInteger callbacks = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .notNull()
                .custom((value, errors) -> {
                    callbacks.incrementAndGet();
                    errors.add();
                    errors.add();
                })
                .rule("text.invalid", value -> false)
                .custom((value, errors) -> {
                    callbacks.incrementAndGet();
                    errors.add();
                })
                .build();

        assertErrorsAt(
                validator.validate(null),
                "# [notNull]",
                "# [text.invalid]",
                "# [rule.unsatisfied]",
                "# [rule.unsatisfied]",
                "# [rule.unsatisfied]"
        );
        assertEquals(2, callbacks.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenCustomAddsBeforeThrowingWhenValidatingAtRootOrFieldThenPreservesReportedErrorsAndAddsOneExecutionFailure(
            boolean fieldScope
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add();
                    errors.add();
                    throw new IllegalStateException("custom failed after reporting");
                })
                .build();
        String path = fieldScope ? "#.name" : "#";

        List<ValidationError> errors = validateAtRootOrNameField(validator, fieldScope, null);

        assertErrorsAt(
                errors,
                path + " [rule.unsatisfied]",
                path + " [rule.unsatisfied]",
                path + " [validation.executionFailed]"
        );
        errors.stream()
                .filter(error -> "rule.unsatisfied".equals(error.getCode()))
                .forEach(error -> assertDefaultCustomError(error, path));
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(executionError, IllegalStateException.class, "custom failed after reporting");
    }

    @Test
    void givenCustomAddsBeforeAnExceptionWithABrokenMessageAccessorWhenValidatingThenKeepsBothReportedAndExecutionErrors() {
        ThrowingMessageException failure = new ThrowingMessageException();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add();
                    throw failure;
                })
                .build();

        List<ValidationError> errors = assertDoesNotThrow(() -> validator.validate(null));

        assertErrorsAt(errors, "# [rule.unsatisfied]", "# [validation.executionFailed]");
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(executionError, failure.getClass(), null);
    }

    @Test
    void givenAThrowingSiblingCustomWhenAnotherCallbackAddsThenKeepsTheReportedErrorAndIndependentRuleFailure() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    throw new IllegalStateException("sibling failed");
                })
                .custom((value, errors) -> errors.add())
                .rule("text.invalid", value -> false)
                .build();

        assertErrorsAt(
                validator.validate(null),
                "# [validation.executionFailed]",
                "# [rule.unsatisfied]",
                "# [text.invalid]"
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenConditionalCustomWhenGuardIsFalseOrTrueThenSuppressesOrReportsItsAddedErrorAtTheCurrentPath(
            boolean guard
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "name",
                        Person::getName,
                        name -> name.onlyIf(
                                value -> guard,
                                rules -> rules
                                        .custom((value, errors) -> {
                                            calls.incrementAndGet();
                                            errors.add();
                                        })
                        )
                )
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(guard ? 1 : 0, calls.get());
        if (guard) {
            assertDefaultCustomError(assertSingleErrorAt(errors, "#.name", "rule.unsatisfied"), "#.name");
        } else {
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void givenThrowingExtractorWhenChildCustomWouldAddThenReportsOnlyTheExtractorFailureForThatField() {
        AtomicInteger blockedCalls = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            throw new IllegalStateException("extractor failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.custom((value, errors) -> {
                    blockedCalls.incrementAndGet();
                    errors.add();
                }))
                .custom((value, errors) -> errors.add())
                .build();

        assertErrorsAt(
                validator.validate(new Person()),
                "#.address [validation.executionFailed]",
                "# [rule.unsatisfied]"
        );
        assertEquals(0, blockedCalls.get());
    }

    @Test
    void givenReusableReportingValidatorWhenIncludedAtRootAndNestedPathsThenRebasesEachReportedErrorExactlyOnce() {
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> errors.add())
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .field("alias", Person::getName, alias -> alias.onlyIf(value -> true, rules -> rules.include(shared)))
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.include(shared))
                )
                .build();

        List<ValidationError> original = shared.validate(null);
        List<ValidationError> nested = parent.validate(new Person(new Address("Main Street")));

        assertDefaultCustomError(assertSingleErrorAt(original, "#", "rule.unsatisfied"), "#");
        assertErrorsAt(
                nested,
                "#.name [rule.unsatisfied]",
                "#.alias [rule.unsatisfied]",
                "#.address.streetName [rule.unsatisfied]"
        );
        nested.forEach(error -> assertDefaultCustomError(error, error.getPath()));
        assertDefaultCustomError(assertSingleErrorAt(shared.validate("Ada"), "#", "rule.unsatisfied"), "#");
        assertDefaultCustomError(original.get(0), "#");
    }

    @Test
    void givenTheSameReportingValidatorIncludedTwiceWhenValidatingThenDoesNotDeduplicateItsAddedErrors() {
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> errors.add())
                .build();
        Validator<String> validator = Validator.builder(String.class).include(shared).include(shared).build();

        assertErrorsAt(validator.validate(null), "# [rule.unsatisfied]", "# [rule.unsatisfied]");
    }

    @Test
    void givenAReportingSupertypeValidatorWhenIncludedAtRootAndFieldThenPreservesNodePathsAndTypedValues() {
        Validator<Object> shared = Validator.builder(Object.class)
                .custom((value, errors) -> errors.add())
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .include(shared)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertErrorsAt(validator.validate(new Person()), "# [rule.unsatisfied]", "#.name [rule.unsatisfied]");
    }

    @Test
    void givenReportingCallbacksAddedBetweenBuildsWhenValidatingThenEachTemplateRetainsItsOwnSnapshot() {
        Validator.Builder<String> builder = Validator.builder(String.class)
                .custom((value, errors) -> errors.add());
        Validator<String> first = builder.build();
        builder.custom((value, errors) -> errors.add());
        Validator<String> second = builder.build();

        List<ValidationError> original = first.validate(null);

        assertErrorsAt(original, "# [rule.unsatisfied]");
        assertErrorsAt(second.validate(null), "# [rule.unsatisfied]", "# [rule.unsatisfied]");
        assertErrorsAt(first.validate("Ada"), "# [rule.unsatisfied]");
        assertErrorsAt(original, "# [rule.unsatisfied]");
    }

    @Test
    void givenRepeatedCallsWithDifferentReportCountsWhenValidatingThenKeepsEarlierResultsAndThePassingResultImmutable() {
        Validator<Integer> validator = Validator.builder(Integer.class)
                .custom((count, errors) -> {
                    for (int i = 0; i < count; i++) {
                        errors.add();
                    }
                })
                .build();

        List<ValidationError> first = validator.validate(2);
        List<ValidationError> second = validator.validate(0);
        List<ValidationError> third = validator.validate(1);

        assertErrorsAt(first, "# [rule.unsatisfied]", "# [rule.unsatisfied]");
        assertTrue(second.isEmpty());
        assertErrorsAt(third, "# [rule.unsatisfied]");
        ValidationError extra = new ValidationError("#", "unexpected", "Unexpected error");
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> first.add(extra)),
                () -> assertThrows(UnsupportedOperationException.class, () -> first.set(0, extra)),
                () -> assertThrows(UnsupportedOperationException.class, () -> first.remove(0)),
                () -> assertThrows(UnsupportedOperationException.class, first::clear),
                () -> assertThrows(UnsupportedOperationException.class, () -> second.add(extra)),
                () -> assertThrows(UnsupportedOperationException.class, third::clear)
        );
        first.forEach(error -> assertDefaultCustomError(error, "#"));
        third.forEach(error -> assertDefaultCustomError(error, "#"));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenRetainedSinkAfterNormalCallbackCompletionWhenAddingThenThrowsIllegalStateExceptionWithoutChangingResults(
            boolean report
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    if (report) {
                        errors.add();
                    }
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, retained.get()::add);
        assertThrows(IllegalStateException.class, retained.get()::add);
        if (report) {
            assertDefaultCustomError(assertSingleErrorAt(errors, "#", "rule.unsatisfied"), "#");
        } else {
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void givenRetainedSinkAfterCallbackThrowsRuntimeExceptionWhenAddingThenRejectsUseAndPreservesBothOriginalErrors() {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    errors.add();
                    throw new IllegalStateException("callback failed");
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, retained.get()::add);
        assertErrorsAt(errors, "# [rule.unsatisfied]", "# [validation.executionFailed]");
    }

    @Test
    void givenCallbackExceptionInspectingItsSinkWhenTheMessageIsReadThenTheCallbackSinkHasAlreadyExpired() {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        AtomicReference<RuntimeException> expiredFailure = new AtomicReference<>();
        RuntimeException failure = new RuntimeException() {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
                try {
                    retained.get().add();
                } catch (RuntimeException expired) {
                    expiredFailure.set(expired);
                }
                return "callback failed";
            }
        };
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    errors.add();
                    throw failure;
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertTrue(expiredFailure.get() instanceof IllegalStateException);
        assertErrorsAt(errors, "# [rule.unsatisfied]", "# [validation.executionFailed]");
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(executionError, failure.getClass(), "callback failed");
    }

    @Test
    void givenRetainedSinkAfterCallbackThrowsJvmErrorWhenAddingThenRejectsUseAndPropagatesTheOriginalJvmError() {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        AssertionError failure = new AssertionError("callback JVM error");
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    errors.add();
                    throw failure;
                })
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(null)));
        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, retained.get()::add);
    }

    @Test
    void givenAnExpiredSinkUsedByAnotherValidationCallbackWhenAddingThenReportsExecutionFailureAtTheNewCallbackPath() {
        AtomicReference<Validator.ErrorSink> expired = new AtomicReference<>();
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add();
                    if ("first".equals(value)) {
                        expired.set(errors);
                    } else {
                        expired.get().add();
                    }
                })
                .build();

        List<ValidationError> original = shared.validate("first");
        List<ValidationError> later = validateAtRootOrNameField(shared, true, "second");

        assertErrorsAt(original, "# [rule.unsatisfied]");
        assertErrorsAt(later, "#.name [rule.unsatisfied]", "#.name [validation.executionFailed]");
        ValidationError executionError = later.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(executionError, IllegalStateException.class, null);
        assertErrorsAt(original, "# [rule.unsatisfied]");
    }

    @Test
    void givenReentrantValidationInsideCustomWhenTheInnerCallbackFinishesThenKeepsTheOuterSinkActiveAndResultsIsolated() {
        AtomicReference<Validator<String>> shared = new AtomicReference<>();
        AtomicReference<List<ValidationError>> innerResults = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> innerSink = new AtomicReference<>();
        AtomicReference<RuntimeException> expiredFailure = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add();
                    if ("outer".equals(value)) {
                        innerResults.set(shared.get().validate("inner"));
                        try {
                            innerSink.get().add();
                        } catch (RuntimeException failure) {
                            expiredFailure.set(failure);
                        }
                        errors.add();
                    } else {
                        innerSink.set(errors);
                    }
                })
                .build();
        shared.set(validator);

        List<ValidationError> outer = validateAtRootOrNameField(validator, true, "outer");

        assertErrorsAt(outer, "#.name [rule.unsatisfied]", "#.name [rule.unsatisfied]");
        assertNotNull(innerResults.get());
        assertErrorsAt(innerResults.get(), "# [rule.unsatisfied]");
        assertTrue(expiredFailure.get() instanceof IllegalStateException);
        assertThrows(IllegalStateException.class, innerSink.get()::add);
        assertErrorsAt(outer, "#.name [rule.unsatisfied]", "#.name [rule.unsatisfied]");
    }

    @Test
    void givenSharedReportingValidatorWhenValidatingConcurrentlyThenKeepsEachCallsCountsPathsAndImmutableResultsIsolated()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                new Person(null, null, 18, Collections.emptyList()),
                new Person("", null, 18, Collections.emptyList()),
                new Person("Ada", null, 18, Collections.emptyList()),
                new Person("Augusta", null, 18, Collections.emptyList())
        );
        String[][] expectedErrors = {
                { "#.name [rule.unsatisfied]" },
                { "#.name [rule.unsatisfied]", "#.name [rule.unsatisfied]" },
                {},
                { "#.name [rule.unsatisfied]", "#.name [rule.unsatisfied]", "#.name [rule.unsatisfied]" }
        };
        CyclicBarrier barrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    awaitConcurrentValidation(barrier);
                    int count = value == null ? 1 : value.isEmpty() ? 2 : value.length() > 3 ? 3 : 0;
                    for (int i = 0; i < count; i++) {
                        errors.add();
                    }
                    awaitConcurrentValidation(barrier);
                })
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertConcurrentResults(validator, inputs, expectedErrors);
    }

    // P017-P019: the user will implement the remaining add overloads together.
    // P016 covers add(); derived targets remain in P020-P021.

    @Test
    void whenInspectingErrorSinkThenExposesAllThreeMetadataAddOverloadsPublicly() {
        assertAll(
                () -> assertNotNull(Validator.ErrorSink.class.getMethod("add", String.class)),
                () -> assertNotNull(Validator.ErrorSink.class.getMethod("add", String.class, String.class)),
                () -> assertNotNull(Validator.ErrorSink.class.getMethod("add", String.class, String.class, Map.class))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenMetadataAddOverloadWhenReportingAtANullRootThenPreservesItsCodeMessageAndAttributes(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> report.accept(errors))
                .build();

        assertAddedMetadata(assertSingleErrorAt(validator.validate(null), "#", code), "#", code, message, attributes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenMetadataAddOverloadWhenReportingAtANullNestedValueThenUsesTheCompleteCurrentPath(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street
                                                .custom((value, errors) -> report.accept(errors))
                                )
                )
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address.streetName", code),
                "#.address.streetName",
                code,
                message,
                attributes
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenMetadataAddOverloadInsideBlockedFieldWhenParentIsNullThenReportsOnlyOneSkippedTraversalError(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.custom((value, errors) -> {
                    calls.incrementAndGet();
                    report.accept(errors);
                }))
                .build();

        assertSingleErrorAt(validator.validate(null), "#.name", "validation.skipped");
        assertEquals(0, calls.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenConditionalMetadataReporterWhenGuardChangesThenSkipsOrReportsAtTheSameFieldNode(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "name",
                        Person::getName,
                        name -> name.onlyIf(
                                value -> value != null,
                                rules -> rules
                                        .custom((value, errors) -> {
                                            calls.incrementAndGet();
                                            report.accept(errors);
                                        })
                        )
                )
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
        assertEquals(0, calls.get());
        assertAddedMetadata(
                assertSingleErrorAt(validator.validate(new Person(new Address("Main Street"))), "#.name", code),
                "#.name",
                code,
                message,
                attributes
        );
        assertEquals(1, calls.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenSharedMetadataReporterWhenIncludedAtDifferentPathsThenRebasesPathsAndPreservesAllMetadata(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> report.accept(errors))
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .field("alias", Person::getName, alias -> alias.include(shared))
                .build();

        List<ValidationError> original = shared.validate(null);
        List<ValidationError> nested = parent.validate(new Person(new Address("Main Street")));

        assertErrorsAt(nested, "#.name [" + code + "]", "#.alias [" + code + "]");
        nested.forEach(error -> assertAddedMetadata(error, error.getPath(), code, message, attributes));
        assertAddedMetadata(assertSingleErrorAt(original, "#", code), "#", code, message, attributes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenMetadataAddOverloadCalledTwiceWhenValidatingThenRetainsBothErrorsWithoutDeduplication(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    report.accept(errors);
                    report.accept(errors);
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [" + code + "]", "# [" + code + "]");
        errors.forEach(error -> assertAddedMetadata(error, "#", code, message, attributes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenCallbackReportsMetadataBeforeThrowingWhenValidatingThenKeepsTheReportAndIndependentFailuresAndClosesItsSink(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    report.accept(errors);
                    throw new IllegalStateException("failed after metadata report");
                })
                .rule("independent.invalid", value -> false)
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertErrorsAt(
                errors,
                "#.name [" + code + "]",
                "#.name [validation.executionFailed]",
                "#.name [independent.invalid]"
        );
        ValidationError reported = errors.stream().filter(error -> code.equals(error.getCode())).findFirst().get();
        assertAddedMetadata(reported, "#.name", code, message, attributes);
        ValidationError execution = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(execution, IllegalStateException.class, "failed after metadata report");
        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, () -> report.accept(retained.get()));
        assertAddedMetadata(reported, "#.name", code, message, attributes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataAddOverloads")
    void givenMetadataAddOverloadAfterCallbackCompletionWhenReportingThenRejectsExpiredSinkAndPreservesEarlierResults(
            String overload,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    report.accept(errors);
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, () -> report.accept(retained.get()));
        assertAddedMetadata(assertSingleErrorAt(errors, "#", code), "#", code, message, attributes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAddMetadata")
    void givenInvalidExplicitAddMetadataWhenCallbackCatchesTheFailureThenAddsNoMalformedErrorAndAllowsFurtherReporting(
            String scenario,
            Consumer<Validator.ErrorSink> invalidReport
    ) {
        AtomicReference<RuntimeException> observed = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    try {
                        invalidReport.accept(errors);
                    } catch (RuntimeException failure) {
                        observed.set(failure);
                    }
                    errors.add();
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(observed.get());
        assertDefaultCustomError(assertSingleErrorAt(errors, "#", "rule.unsatisfied"), "#");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullAddMetadata")
    void givenUncaughtInvalidAddMetadataWhenValidatingThenPreservesEarlierReportsRecordsExecutionFailureAndContinuesRules(
            String scenario,
            Consumer<Validator.ErrorSink> invalidReport
    ) {
        AtomicInteger reachedAfterInvalidCall = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add();
                    invalidReport.accept(errors);
                    reachedAfterInvalidCall.incrementAndGet();
                })
                .rule("independent.invalid", value -> false)
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertErrorsAt(
                errors,
                "#.name [rule.unsatisfied]",
                "#.name [validation.executionFailed]",
                "#.name [independent.invalid]"
        );
        assertEquals(0, reachedAfterInvalidCall.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expiredAddOverloadsWithInvalidMetadata")
    void givenExpiredSinkAndInvalidMetadataWhenCallingAnAddOverloadThenRejectsUseWithIllegalStateException(
            String overload,
            Consumer<Validator.ErrorSink> invalidReport
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> retained.set(errors))
                .build();

        assertTrue(validator.validate(null).isEmpty());

        assertNotNull(retained.get());
        assertThrows(IllegalStateException.class, () -> invalidReport.accept(retained.get()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addOverloadsWithNonblankCodes")
    void givenNonblankCodeContainingWhitespaceOrPathSyntaxWhenReportingThenPreservesTheCodeWithoutChangingTheNodePath(
            String scenario,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> report.accept(errors))
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validateAtRootOrNameField(validator, true, null), "#.name", code),
                "#.name",
                code,
                message,
                attributes
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("addOverloadsWithBlankMessages")
    void givenExplicitBlankMessageWhenReportingWithOrWithoutAttributesThenPreservesTheExactMessage(
            String scenario,
            Consumer<Validator.ErrorSink> report,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> report.accept(errors))
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validator.validate(null), "#", "message.override"),
                "#",
                "message.override",
                message,
                attributes
        );
    }

    @Test
    void givenAllFourAddOverloadsInOneCallbackWhenReportingThenKeepsEachErrorsDefaultsAndOverridesIndependent() {
        Map<String, Object> attributes = Collections.singletonMap("min", 18);
        String defaultMessage = defaultCustomMessage();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("rich", "Rich message", attributes);
                    errors.add("message", "Explicit message");
                    errors.add("code");
                    errors.add();
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [rich]", "# [message]", "# [code]", "# [rule.unsatisfied]");
        assertAddedMetadata(
                errors.stream().filter(error -> "rich".equals(error.getCode())).findFirst().get(),
                "#",
                "rich",
                "Rich message",
                attributes
        );
        assertAddedMetadata(
                errors.stream().filter(error -> "message".equals(error.getCode())).findFirst().get(),
                "#",
                "message",
                "Explicit message",
                Collections.emptyMap()
        );
        assertAddedMetadata(
                errors.stream().filter(error -> "code".equals(error.getCode())).findFirst().get(),
                "#",
                "code",
                defaultMessage,
                Collections.emptyMap()
        );
        assertDefaultCustomError(
                errors.stream().filter(error -> "rule.unsatisfied".equals(error.getCode())).findFirst().get(),
                "#"
        );
    }

    @Test
    void givenExplicitEmptyAttributesWhenReportingThenStillAddsAnErrorWithTheSuppliedCodeAndMessage() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.add("custom.code", "Custom message", Collections.emptyMap()))
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validator.validate(null), "#", "custom.code"),
                "#",
                "custom.code",
                "Custom message",
                Collections.emptyMap()
        );
    }

    @Test
    void givenTypedIntegerAndWildcardNumberAttributeMapsWhenReportingThenAcceptsThemWithoutCastsAndPreservesValueTypes() {
        Map<String, Integer> integerAttributes = Collections.singletonMap("min", 18);
        Map<String, ? extends Number> numberAttributes = Collections.singletonMap("max", 120L);
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("minimum", "Minimum required", integerAttributes);
                    errors.add("maximum", "Maximum exceeded", numberAttributes);
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [minimum]", "# [maximum]");
        ValidationError minimum = errors.stream().filter(error -> "minimum".equals(error.getCode())).findFirst().get();
        ValidationError maximum = errors.stream().filter(error -> "maximum".equals(error.getCode())).findFirst().get();
        assertEquals(integerAttributes, minimum.getAttributes());
        assertEquals(numberAttributes, maximum.getAttributes());
        assertTrue(minimum.getAttributes().get("min") instanceof Integer);
        assertTrue(maximum.getAttributes().get("max") instanceof Long);
    }

    @Test
    void givenHeterogeneousAttributesIncludingMetadataNamesWhenReportingThenKeepsThemAsAttributesWithoutOverridingErrorMetadata() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("path", "#.elsewhere");
        attributes.put("code", "another.code");
        attributes.put("message", "Another message");
        attributes.put("min", 18);
        attributes.put("inclusive", true);
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.add("actual.code", "Actual message", attributes))
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validateAtRootOrNameField(validator, true, null), "#.name", "actual.code"),
                "#.name",
                "actual.code",
                "Actual message",
                attributes
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenMutableAttributeMapWhenItChangesAfterAddBeforeCallbackReturnsThenRetainsTheSnapshotAtTheAddCall(
            boolean initiallyEmpty
    ) {
        Map<String, Object> source = new HashMap<>();
        if (!initiallyEmpty) {
            source.put("min", 18);
        }
        Map<String, Object> expected = new HashMap<>(source);
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("custom.code", "Custom message", source);
                    source.put("min", 21);
                    source.put("max", 120);
                })
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "custom.code");

        assertAddedMetadata(error, "#", "custom.code", "Custom message", expected);
        source.clear();
        assertAddedMetadata(error, "#", "custom.code", "Custom message", expected);
    }

    @Test
    void givenUnmodifiableAttributesViewWhenItsBackingMapChangesAfterAddThenPreservesTheOriginalAttributeValues() {
        Map<String, Object> source = new HashMap<>();
        source.put("min", 18);
        Map<String, Object> view = Collections.unmodifiableMap(source);
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("custom.code", "Custom message", view);
                    source.put("min", 21);
                })
                .build();

        assertAddedMetadata(
                assertSingleErrorAt(validator.validate(null), "#", "custom.code"),
                "#",
                "custom.code",
                "Custom message",
                Collections.singletonMap("min", 18)
        );
    }

    @Test
    void givenTheSameMutableAttributesUsedForTwoReportsWhenChangedBetweenAddsThenEachErrorKeepsItsOwnSnapshot() {
        Map<String, Object> source = new HashMap<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    source.put("min", 18);
                    errors.add("first", "First message", source);
                    source.put("min", 21);
                    errors.add("second", "Second message", source);
                    source.clear();
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [first]", "# [second]");
        assertAddedMetadata(
                errors.stream().filter(error -> "first".equals(error.getCode())).findFirst().get(),
                "#",
                "first",
                "First message",
                Collections.singletonMap("min", 18)
        );
        assertAddedMetadata(
                errors.stream().filter(error -> "second".equals(error.getCode())).findFirst().get(),
                "#",
                "second",
                "Second message",
                Collections.singletonMap("min", 21)
        );
    }

    @Test
    void givenRichReportedAttributesWhenMutatingTheReturnedMapOrItsViewsThenRejectsChangesAndPreservesTheReport() {
        Validator<String> validator = Validator.builder(String.class)
                .custom(
                        (value, errors) -> errors
                                .add("custom.code", "Custom message", Collections.singletonMap("min", 18))
                )
                .build();
        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "custom.code");
        Map<String, Object> attributes = error.getAttributes();
        Map.Entry<String, Object> entry = attributes.entrySet().iterator().next();
        Iterator<Map.Entry<String, Object>> entries = attributes.entrySet().iterator();
        entries.next();

        assertImmutableAttributes(error);
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, attributes::clear),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.remove("min")),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.replace("min", 21)),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.compute("min", (key, value) -> 21)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.merge("min", 21, (oldValue, newValue) -> newValue)
                ),
                () -> assertThrows(UnsupportedOperationException.class, () -> entry.setValue(21)),
                () -> assertThrows(UnsupportedOperationException.class, entries::remove),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.keySet().remove("min")),
                () -> assertThrows(UnsupportedOperationException.class, () -> attributes.values().remove(18))
        );
        assertAddedMetadata(error, "#", "custom.code", "Custom message", Collections.singletonMap("min", 18));
    }

    @Test
    void givenRichReportsAcrossValidationCallsWhenTheInputAndSourceMapChangeThenEarlierResultsKeepTheirOriginalAttributes() {
        Map<String, Object> source = new HashMap<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    source.put("length", value.length());
                    errors.add("text.invalid", "Text is invalid", source);
                })
                .build();

        List<ValidationError> first = validator.validate("Ada");
        List<ValidationError> second = validator.validate("Augusta");
        source.clear();

        assertAddedMetadata(
                assertSingleErrorAt(first, "#", "text.invalid"),
                "#",
                "text.invalid",
                "Text is invalid",
                Collections.singletonMap("length", 3)
        );
        assertAddedMetadata(
                assertSingleErrorAt(second, "#", "text.invalid"),
                "#",
                "text.invalid",
                "Text is invalid",
                Collections.singletonMap("length", 7)
        );
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, second::clear);
    }

    @Test
    void givenUnreadableAttributeMapWhenAddingThenRecordsExecutionFailureWithoutAddingAPartialRichError() {
        IllegalStateException failure = new IllegalStateException("attributes unavailable");
        Map<String, Object> attributes = new java.util.AbstractMap<String, Object>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw failure;
            }
        };
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("before.invalid");
                    errors.add("rich.invalid", "Rich error", attributes);
                })
                .rule("independent.invalid", value -> false)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [before.invalid]", "# [validation.executionFailed]", "# [independent.invalid]");
        ValidationError execution = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(execution, failure.getClass(), "attributes unavailable");
    }

    @Test
    void givenRichReportBeforeCallbackThrowsJvmErrorWhenValidatingThenPropagatesTheSameErrorAndExpiresTheSink() {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        AssertionError failure = new AssertionError("callback JVM error");
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    retained.set(errors);
                    errors.add("custom.code", "Custom message", Collections.singletonMap("min", 18));
                    throw failure;
                })
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(null)));
        assertNotNull(retained.get());
        assertThrows(
                IllegalStateException.class,
                () -> retained.get().add("custom.code", "Custom message", Collections.singletonMap("min", 18))
        );
    }

    @Test
    void givenReentrantRichReportingWhenAnInnerValidationRunsThenKeepsOuterAndInnerPathsAttributesAndSinksIndependent() {
        AtomicReference<Validator<String>> shared = new AtomicReference<>();
        AtomicReference<List<ValidationError>> innerResults = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("text.invalid", "Text is invalid", Collections.singletonMap("length", value.length()));
                    if ("outer".equals(value)) {
                        innerResults.set(shared.get().validate("in"));
                        errors.add("outer.after", "Outer report", Collections.singletonMap("length", value.length()));
                    }
                })
                .build();
        shared.set(validator);

        List<ValidationError> outer = validateAtRootOrNameField(validator, true, "outer");

        assertErrorsAt(outer, "#.name [text.invalid]", "#.name [outer.after]");
        assertNotNull(innerResults.get());
        assertAddedMetadata(
                assertSingleErrorAt(innerResults.get(), "#", "text.invalid"),
                "#",
                "text.invalid",
                "Text is invalid",
                Collections.singletonMap("length", 2)
        );
        for (ValidationError error : outer) {
            assertEquals(Collections.singletonMap("length", 5), error.getAttributes());
            assertEquals("#.name", error.getPath());
        }
    }

    @Test
    void givenRichReportingValidatorSharedAcrossThreadsWhenValidatingThenKeepsAttributeSnapshotsAndPathsIsolated()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                new Person("Ada", null, 18, Collections.emptyList()),
                new Person("Augusta", null, 18, Collections.emptyList()),
                new Person("", null, 18, Collections.emptyList()),
                new Person("Grace", null, 18, Collections.emptyList())
        );
        String[][] expectedErrors = {
                { "#.name [text.invalid]" }, { "#.name [text.invalid]" },
                { "#.name [text.invalid]" }, { "#.name [text.invalid]" }
        };
        CyclicBarrier barrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Map<String, Object> attributes = new HashMap<>();
                    attributes.put("length", value.length());
                    awaitConcurrentValidation(barrier);
                    errors.add("text.invalid", "Text is invalid", attributes);
                    attributes.put("length", -1);
                    awaitConcurrentValidation(barrier);
                })
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertConcurrentResults(
                validator,
                inputs,
                expectedErrors,
                (person, errors) -> assertAddedMetadata(
                        errors.get(0),
                        "#.name",
                        "text.invalid",
                        "Text is invalid",
                        Collections.singletonMap("length", person.getName().length())
                )
        );
    }

    // P020: ErrorSink.field(name). Select an error target without extracting a
    // value or changing the original sink's path.

    @Test
    void whenInspectingSinkFieldMethodThenExposesAPublicStringSelectorReturningErrorSink()
            throws NoSuchMethodException {
        assertEquals(
                Validator.ErrorSink.class,
                Validator.ErrorSink.class.getMethod("field", String.class).getReturnType()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { "child", " child ", "\u2003child\u3000", "postal address", "\u00A0", "街道" })
    void givenValidSinkFieldNameWhenReportingThenAppendsItsExactTextToTheCurrentPath(String name) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.field(name).add())
                .build();

        assertDefaultCustomError(
                assertSingleErrorAt(validator.validate(null), "#." + name, "rule.unsatisfied"),
                "#." + name
        );
    }

    @Test
    void givenSinkFieldSelectionWithoutAddWhenCallbackCompletesThenReturnsADerivedTargetWithoutReportingAnError() {
        AtomicReference<Validator.ErrorSink> root = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> derived = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    root.set(errors);
                    derived.set(errors.field("missingProperty"));
                })
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertNotNull(root.get());
        assertNotNull(derived.get());
        assertNotSame(root.get(), derived.get());
    }

    @Test
    void givenParentAndSiblingFieldTargetsWhenReportingAfterMoreSelectionsThenEachTargetRetainsItsOwnPath() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink first = errors.field("first");
                    Validator.ErrorSink second = errors.field("second");
                    first.add("first.before");
                    errors.add("root");
                    second.add("second");
                    first.field("unused");
                    first.add("first.after");
                })
                .build();

        assertErrorsAt(
                validator.validate(null),
                "#.first [first.before]",
                "# [root]",
                "#.second [second]",
                "#.first [first.after]"
        );
    }

    @Test
    void givenRepeatedSinkFieldNamesWhenReportingThenPreservesEveryDeclaredSegmentWithoutDeduplicatingThem() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.field("address").field("address").add("address.invalid"))
                .build();

        assertSingleErrorAt(validator.validate(null), "#.address.address", "address.invalid");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n",
            ".", "[", "]", "#", "address.street", "items[0]", "#.name", "a#b"
    })
    void givenInvalidSinkFieldNameWhenCallbackCatchesSelectionFailureThenRejectsImmediatelyAndKeepsTheParentUsable(
            String name
    ) {
        AtomicReference<RuntimeException> observed = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("before");
                    try {
                        errors.field(name);
                    } catch (RuntimeException failure) {
                        observed.set(failure);
                    }
                    errors.add("after");
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(observed.get());
        assertErrorsAt(errors, "# [before]", "# [after]");
    }

    @Test
    void givenUncaughtInvalidFieldSelectionOnADerivedSinkWhenValidatingThenReportsExecutionFailureAtTheCallbackNode() {
        AtomicInteger reachedAfterInvalidSelection = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = errors.field("valid");
                    target.add("before");
                    target.field("invalid.segment");
                    reachedAfterInvalidSelection.incrementAndGet();
                })
                .rule("independent.invalid", value -> false)
                .build();

        assertErrorsAt(
                validateAtRootOrNameField(validator, true, null),
                "#.name.valid [before]",
                "#.name [validation.executionFailed]",
                "#.name [independent.invalid]"
        );
        assertEquals(0, reachedAfterInvalidSelection.get());
    }

    // P021: ErrorSink.atIndex(index). Select a zero-based path segment without
    // inspecting the current value or checking collection bounds.

    @Test
    void whenInspectingSinkAtIndexMethodThenExposesAPublicIntSelectorReturningErrorSink() throws NoSuchMethodException {
        assertEquals(
                Validator.ErrorSink.class,
                Validator.ErrorSink.class.getMethod("atIndex", int.class).getReturnType()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 12, Integer.MAX_VALUE })
    void givenNonnegativeSinkIndexWhenReportingOnANullRootThenAppendsTheExactZeroBasedDecimalIndex(int index) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.atIndex(index).add())
                .build();

        String path = "#[" + index + "]";
        assertDefaultCustomError(assertSingleErrorAt(validator.validate(null), path, "rule.unsatisfied"), path);
    }

    @Test
    void givenSinkIndexSelectionWithoutAddWhenCallbackCompletesThenReturnsADerivedTargetWithoutReportingAnError() {
        AtomicReference<Validator.ErrorSink> root = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> derived = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    root.set(errors);
                    derived.set(errors.atIndex(Integer.MAX_VALUE));
                })
                .build();

        assertTrue(validator.validate("not a collection").isEmpty());
        assertNotNull(root.get());
        assertNotNull(derived.get());
        assertNotSame(root.get(), derived.get());
    }

    @Test
    void givenParentAndSiblingIndexTargetsWhenReportingAfterMoreSelectionsThenEachTargetRetainsItsOwnPath() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink first = errors.atIndex(0);
                    Validator.ErrorSink second = errors.atIndex(3);
                    first.add("first.before");
                    errors.add("root");
                    second.add("second");
                    first.atIndex(2);
                    first.add("first.after");
                })
                .build();

        assertErrorsAt(
                validator.validate(null),
                "#[0] [first.before]",
                "# [root]",
                "#[3] [second]",
                "#[0] [first.after]"
        );
    }

    @Test
    void givenRepeatedSinkIndexesWhenReportingThenPreservesEveryIndexSegmentWithoutIncrementingOrDeduplicatingThem() {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> errors.atIndex(0).atIndex(0).add("entry.invalid"))
                .build();

        assertSingleErrorAt(validator.validate(null), "#[0][0]", "entry.invalid");
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, -10, Integer.MIN_VALUE })
    void givenNegativeSinkIndexWhenCallbackCatchesSelectionFailureThenRejectsImmediatelyAndKeepsTheParentUsable(
            int index
    ) {
        AtomicReference<RuntimeException> observed = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    errors.add("before");
                    try {
                        errors.atIndex(index);
                    } catch (RuntimeException failure) {
                        observed.set(failure);
                    }
                    errors.add("after");
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertNotNull(observed.get());
        assertErrorsAt(errors, "# [before]", "# [after]");
    }

    @Test
    void givenUncaughtNegativeIndexSelectionOnADerivedSinkWhenValidatingThenReportsExecutionFailureAtTheCallbackNode() {
        AtomicInteger reachedAfterInvalidSelection = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = errors.atIndex(1);
                    target.add("before");
                    target.atIndex(-1);
                    reachedAfterInvalidSelection.incrementAndGet();
                })
                .rule("independent.invalid", value -> false)
                .build();

        assertErrorsAt(
                validateAtRootOrNameField(validator, true, null),
                "#.name[1] [before]",
                "#.name [validation.executionFailed]",
                "#.name [independent.invalid]"
        );
        assertEquals(0, reachedAfterInvalidSelection.get());
    }

    @Test
    void givenMapKeysWhoseToStringThrowsWhenReportingAtEncounterIndexesThenBuildsNumericPathsWithoutStringifyingKeys() {
        Object key = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("Map keys must not be stringified");
            }
        };
        Map<Object, String> contacts = new LinkedHashMap<>();
        contacts.put("first", "present");
        contacts.put(key, null);
        Validator<Person> validator = Validator.builder(Person.class)
                .field("contacts", person -> contacts, rules -> rules.custom((values, errors) -> {
                    int index = 0;
                    for (Map.Entry<Object, String> entry : values.entrySet()) {
                        if (entry.getValue() == null) {
                            errors.atIndex(index).field("number").add("number.required");
                        }
                        index++;
                    }
                }))
                .build();

        assertSingleErrorAt(validator.validate(new Person()), "#.contacts[1].number", "number.required");
    }

    // P020-P021 integration: compose paths while sharing one callback-scoped
    // collector and lifecycle across all derived targets.

    @ParameterizedTest(name = "{0}")
    @MethodSource("composedSinkPathCases")
    void givenComposedSinkTargetsWhenReportingAtRootOrExtractedNodeThenAppendsAllSegmentsToThatNodesPath(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix,
            boolean fieldScope
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom(
                        (value, errors) -> select.apply(errors)
                                .add("target.invalid", "Invalid target", Collections.singletonMap("min", 18))
                )
                .build();
        String path = (fieldScope ? "#.name" : "#") + suffix;

        assertAddedMetadata(
                assertSingleErrorAt(validateAtRootOrNameField(validator, fieldScope, null), path, "target.invalid"),
                path,
                "target.invalid",
                "Invalid target",
                Collections.singletonMap("min", 18)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedSinkReportingCases")
    void givenFieldOrIndexTargetWhenUsingAnyAddOverloadThenReportsAtTheDerivedPathWithTheSuppliedMetadata(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix,
            Consumer<Validator.ErrorSink> report,
            String code,
            String message,
            Map<String, Object> attributes
    ) {
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> report.accept(select.apply(errors)))
                .build();
        String path = "#.name" + suffix;

        assertAddedMetadata(
                assertSingleErrorAt(validateAtRootOrNameField(validator, true, null), path, code),
                path,
                code,
                message,
                attributes
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenGuardedDerivedTargetWhenGuardIsFalseOrTrueThenSkipsOrReportsWithoutAddingAConditionalPathSegment(
            boolean guard
    ) {
        AtomicInteger calls = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> guard, rules -> rules.custom((value, errors) -> {
                    calls.incrementAndGet();
                    errors.field("detail").atIndex(1).add("detail.invalid");
                }))
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertEquals(guard ? 1 : 0, calls.get());
        if (guard) {
            assertSingleErrorAt(errors, "#.name.detail[1]", "detail.invalid");
        } else {
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void givenSharedSupertypeValidatorReportingToDerivedTargetsWhenIncludedAtDifferentNodesThenRebasesEachPathExactlyOnce() {
        Validator<Object> shared = Validator.builder(Object.class)
                .custom(
                        (value, errors) -> errors.field("details")
                                .atIndex(2)
                                .field("bad")
                                .add("shared.invalid", "Shared error", Collections.singletonMap("min", 18))
                )
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .include(shared)
                .field("name", Person::getName, name -> name.include(shared))
                .field("alias", Person::getName, alias -> alias.onlyIf(value -> true, rules -> rules.include(shared)))
                .build();

        List<ValidationError> original = shared.validate(null);
        List<ValidationError> nested = parent.validate(new Person(new Address("Main Street")));

        assertSingleErrorAt(original, "#.details[2].bad", "shared.invalid");
        assertErrorsAt(
                nested,
                "#.details[2].bad [shared.invalid]",
                "#.name.details[2].bad [shared.invalid]",
                "#.alias.details[2].bad [shared.invalid]"
        );
        nested.forEach(
                error -> assertAddedMetadata(
                        error,
                        error.getPath(),
                        "shared.invalid",
                        "Shared error",
                        Collections.singletonMap("min", 18)
                )
        );
        assertSingleErrorAt(shared.validate(new Person()), "#.details[2].bad", "shared.invalid");
        assertSingleErrorAt(original, "#.details[2].bad", "shared.invalid");
    }

    @Test
    void givenInvalidSelectionsCaughtAfterCreatingASiblingTargetWhenReportingThenKeepsBothParentAndExistingTargetUsable() {
        AtomicInteger rejectedSelections = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink safe = errors.field("safe");
                    try {
                        errors.field("invalid.segment");
                    } catch (RuntimeException failure) {
                        rejectedSelections.incrementAndGet();
                    }
                    try {
                        errors.atIndex(-1);
                    } catch (RuntimeException failure) {
                        rejectedSelections.incrementAndGet();
                    }
                    safe.add("safe");
                    errors.add("root");
                })
                .build();

        assertErrorsAt(validator.validate(null), "#.safe [safe]", "# [root]");
        assertEquals(2, rejectedSelections.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedSinkTargets")
    void givenRetainedDerivedSinkAfterNormalCallbackCompletionWhenReportingOrSelectingThenRejectsAllOperationsImmediately(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = select.apply(errors);
                    retained.set(target);
                    target.add("reported");
                })
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertSingleErrorAt(errors, "#.name" + suffix, "reported");
        assertNotNull(retained.get());
        assertExpiredSinkOperations(retained.get());
        assertSingleErrorAt(errors, "#.name" + suffix, "reported");
    }

    @Test
    void givenRootSiblingAndIntermediateTargetsWhenTheCallbackEndsThenExpiresEveryViewUsingTheSameLifecycle() {
        List<Validator.ErrorSink> views = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink first = errors.field("first");
                    Validator.ErrorSink second = errors.field("second");
                    Validator.ErrorSink indexed = first.atIndex(1);
                    Validator.ErrorSink leaf = indexed.field("sku");
                    Validator.ErrorSink other = second.atIndex(2);
                    views.addAll(Arrays.asList(errors, first, second, indexed, leaf, other));
                    views.forEach(target -> target.add("reported"));
                })
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertErrorsAt(
                errors,
                "#.name [reported]",
                "#.name.first [reported]",
                "#.name.second [reported]",
                "#.name.first[1] [reported]",
                "#.name.first[1].sku [reported]",
                "#.name.second[2] [reported]"
        );
        assertEquals(6, views.size());
        views.forEach(ValidatorTest::assertExpiredSinkOperations);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedSinkTargets")
    void givenDerivedReportBeforeCallbackThrowsRuntimeExceptionWhenValidatingThenRetainsTheReportAndExpiresTheTarget(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = select.apply(errors);
                    retained.set(target);
                    target.add("reported");
                    throw new IllegalStateException("callback failed");
                })
                .rule("independent.invalid", value -> false)
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertErrorsAt(
                errors,
                "#.name" + suffix + " [reported]",
                "#.name [validation.executionFailed]",
                "#.name [independent.invalid]"
        );
        ValidationError execution = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(execution, IllegalStateException.class, "callback failed");
        assertNotNull(retained.get());
        assertExpiredSinkOperations(retained.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedSinkTargets")
    void givenDerivedReportBeforeCallbackThrowsJvmErrorWhenValidatingThenPropagatesThatErrorAndExpiresTheTarget(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        AssertionError failure = new AssertionError("callback JVM error");
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = select.apply(errors);
                    retained.set(target);
                    target.add("reported");
                    throw failure;
                })
                .build();

        assertSame(
                failure,
                assertThrows(AssertionError.class, () -> validateAtRootOrNameField(validator, true, null))
        );
        assertNotNull(retained.get());
        assertExpiredSinkOperations(retained.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("derivedSinkTargets")
    void givenCallbackExceptionInspectingADerivedSinkWhenItsMessageIsReadThenTheDerivedSinkHasAlreadyExpired(
            String scenario,
            Function<Validator.ErrorSink, Validator.ErrorSink> select,
            String suffix
    ) {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        AtomicReference<RuntimeException> expiredFailure = new AtomicReference<>();
        RuntimeException failure = new RuntimeException() {
            private static final long serialVersionUID = 1L;

            @Override
            public String getMessage() {
                try {
                    retained.get().add("late");
                } catch (RuntimeException expired) {
                    expiredFailure.set(expired);
                }
                return "callback failed";
            }
        };
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = select.apply(errors);
                    retained.set(target);
                    target.add("reported");
                    throw failure;
                })
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertTrue(expiredFailure.get() instanceof IllegalStateException);
        assertErrorsAt(errors, "#.name" + suffix + " [reported]", "#.name [validation.executionFailed]");
        ValidationError execution = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .get();
        assertExceptionDetails(execution, failure.getClass(), "callback failed");
    }

    @Test
    void givenReentrantValidationWithDerivedTargetsWhenTheInnerCallbackEndsThenKeepsOuterTargetsActiveAndContextsIsolated() {
        AtomicReference<Validator<String>> shared = new AtomicReference<>();
        AtomicReference<List<ValidationError>> innerResults = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> outerTarget = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> innerTarget = new AtomicReference<>();
        AtomicReference<RuntimeException> expiredFailure = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = errors.field("payload").atIndex(0);
                    target.add("initial", "Initial report", Collections.singletonMap("length", value.length()));
                    if ("outer".equals(value)) {
                        outerTarget.set(target);
                        innerResults.set(shared.get().validate("in"));
                        try {
                            innerTarget.get().add("late");
                        } catch (RuntimeException failure) {
                            expiredFailure.set(failure);
                        }
                        target.add("after", "Outer report", Collections.singletonMap("length", value.length()));
                    } else {
                        innerTarget.set(target);
                    }
                })
                .build();
        shared.set(validator);

        List<ValidationError> outer = validateAtRootOrNameField(validator, true, "outer");

        assertErrorsAt(outer, "#.name.payload[0] [initial]", "#.name.payload[0] [after]");
        assertNotNull(innerResults.get());
        assertAddedMetadata(
                assertSingleErrorAt(innerResults.get(), "#.payload[0]", "initial"),
                "#.payload[0]",
                "initial",
                "Initial report",
                Collections.singletonMap("length", 2)
        );
        outer.forEach(error -> assertEquals(Collections.singletonMap("length", 5), error.getAttributes()));
        assertTrue(expiredFailure.get() instanceof IllegalStateException);
        assertNotNull(outerTarget.get());
        assertNotNull(innerTarget.get());
        assertExpiredSinkOperations(outerTarget.get());
        assertExpiredSinkOperations(innerTarget.get());
    }

    @Test
    void givenDifferentDerivedPathsAcrossValidationCallsWhenReusingTheTemplateThenKeepsEarlierPathsAndResultsImmutable() {
        List<Validator.ErrorSink> targets = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink target = errors.field(value == null ? "missing" : "present")
                            .atIndex(value == null ? 0 : 1);
                    targets.add(target);
                    target.add("target.invalid");
                })
                .build();

        List<ValidationError> first = validateAtRootOrNameField(validator, true, null);
        List<ValidationError> second = validateAtRootOrNameField(validator, true, "Ada");
        List<ValidationError> third = validateAtRootOrNameField(validator, true, null);

        assertSingleErrorAt(first, "#.name.missing[0]", "target.invalid");
        assertSingleErrorAt(second, "#.name.present[1]", "target.invalid");
        assertSingleErrorAt(third, "#.name.missing[0]", "target.invalid");
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, second::clear);
        assertThrows(UnsupportedOperationException.class, third::clear);
        assertEquals(3, targets.size());
        targets.forEach(ValidatorTest::assertExpiredSinkOperations);
        assertSingleErrorAt(first, "#.name.missing[0]", "target.invalid");
    }

    @Test
    void givenExpiredDerivedTargetWhenAnotherCallbackUsesTheSamePathThenDoesNotReviveTheOldTargetsReportingOrSelection() {
        AtomicReference<Validator.ErrorSink> expired = new AtomicReference<>();
        List<RuntimeException> rejections = new ArrayList<>();
        List<Consumer<Validator.ErrorSink>> staleOperations = Arrays.asList(
                errors -> errors.add("stale"),
                errors -> errors.field("stale"),
                errors -> errors.atIndex(3)
        );
        Validator<String> validator = Validator.builder(String.class)
                .custom((value, errors) -> {
                    Validator.ErrorSink current = errors.field("child").atIndex(0);
                    current.add("current");
                    if ("first".equals(value)) {
                        expired.set(current);
                    } else {
                        for (Consumer<Validator.ErrorSink> operation : staleOperations) {
                            try {
                                operation.accept(expired.get());
                            } catch (RuntimeException failure) {
                                rejections.add(failure);
                            }
                        }
                        current.add("after");
                    }
                })
                .build();

        List<ValidationError> first = validator.validate("first");
        List<ValidationError> second = validator.validate("second");

        assertSingleErrorAt(first, "#.child[0]", "current");
        assertErrorsAt(second, "#.child[0] [current]", "#.child[0] [after]");
        assertEquals(3, rejections.size());
        assertTrue(rejections.stream().allMatch(failure -> failure instanceof IllegalStateException));
        assertNotNull(expired.get());
        assertExpiredSinkOperations(expired.get());
    }

    @Test
    void givenDerivedTargetsSharedThroughOneValidatorWhenValidatingConcurrentlyThenKeepsPathsMetadataAndResultsIsolated()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                new Person("Ada", null, 18, Collections.emptyList()),
                new Person("Augusta", null, 18, Collections.emptyList()),
                new Person("", null, 18, Collections.emptyList()),
                new Person(null, null, 18, Collections.emptyList())
        );
        String[] expected = {
                "#.name.data[2].value [value.invalid]", "#.name[1].other [other.invalid]", "#.name [whole.invalid]"
        };
        String[][] expectedErrors = { expected, expected, expected, expected };
        CyclicBarrier barrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> {
                    awaitConcurrentValidation(barrier);
                    Validator.ErrorSink target = errors.field("data").atIndex(2).field("value");
                    target.add(
                            "value.invalid",
                            "Invalid value",
                            Collections.singletonMap("length", value == null ? -1 : value.length())
                    );
                    errors.atIndex(1).field("other").add("other.invalid");
                    errors.add("whole.invalid");
                    awaitConcurrentValidation(barrier);
                })
                .build();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(shared))
                .build();

        assertConcurrentResults(validator, inputs, expectedErrors, (person, errors) -> {
            ValidationError error = errors.stream()
                    .filter(item -> "value.invalid".equals(item.getCode()))
                    .findFirst()
                    .get();
            assertAddedMetadata(
                    error,
                    "#.name.data[2].value",
                    "value.invalid",
                    "Invalid value",
                    Collections.singletonMap("length", person.getName() == null ? -1 : person.getName().length())
            );
        });
    }

    // P022-P025: anyOf overloads and builder-consumer options. Options are
    // ordered at runtime; errors within an option have no promised order.

    @Test
    void whenInspectingAnyOfMethodsThenExposesAllThreePublicOverloadsAndTheFluentOptionMethod()
            throws NoSuchMethodException {
        assertEquals(
                Validator.Builder.class,
                Validator.Builder.class.getMethod("anyOf", String.class, String.class, Consumer.class).getReturnType()
        );
        assertEquals(
                Validator.Builder.class,
                Validator.Builder.class.getMethod("anyOf", String.class, Consumer.class).getReturnType()
        );
        assertEquals(
                Validator.Builder.class,
                Validator.Builder.class.getMethod("anyOf", Consumer.class).getReturnType()
        );
        assertEquals(
                Validator.AnyOfOptions.class,
                Validator.AnyOfOptions.class.getMethod("option", Consumer.class).getReturnType()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenAnyOfOverloadWhenRegisteringThenReturnsTheSameParentBuilderWithoutEvaluatingPredicates(int overload) {
        Validator.Builder<String> builder = Validator.builder(String.class);
        AtomicInteger evaluations = new AtomicInteger();

        assertSame(builder, registerAnyOf(builder, overload, choices -> choices.option(rules -> rules.rule(value -> {
            evaluations.incrementAndGet();
            return false;
        }))));
        Validator<String> validator = builder.build();
        assertEquals(0, evaluations.get());

        assertEquals(1, validator.validate("value").size());
        assertEquals(1, evaluations.get());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenAllOptionsFailWhenUsingAnyOfOverloadThenReportsItsOwnCodeAndMessageAtTheCurrentNode(int overload) {
        Validator.Builder<String> builder = Validator.builder(String.class);
        registerAnyOf(
                builder,
                overload,
                choices -> choices.option(
                        rules -> rules
                                .rule("branch.invalid", "Branch message", value -> false)
                )
        );

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(builder.build(), true, null),
                "#.name",
                overload == 2 ? "anyOf.unsatisfied" : "choice.invalid"
        );

        assertEquals(
                overload == 0 ? "Choose a valid alternative" : "At least one alternative must be satisfied",
                error.getMessage()
        );
        assertAnyOfOptionIndexes(error, 0);
        assertAddedMetadata(
                anyOfOptionErrors(error, 0).get(0),
                "#.name",
                "branch.invalid",
                "Branch message",
                Collections.emptyMap()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenNullAnyOfCallbackWhenConfiguringThenRejectsNoLaterThanBuild(int overload) {
        assertThrows(
                RuntimeException.class,
                () -> registerAnyOf(Validator.builder(String.class), overload, null).build()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenNoAnyOfOptionsWhenConfiguringThenRejectsNoLaterThanBuild(int overload) {
        assertThrows(
                RuntimeException.class,
                () -> registerAnyOf(Validator.builder(String.class), overload, choices -> {
                }).build()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAnyOfCodes")
    void givenNullOrBlankAnyOfCodeWhenConfiguringThenRejectsNoLaterThanBuild(int overload, String code) {
        assertThrows(RuntimeException.class, () -> {
            Validator.Builder<String> builder = Validator.builder(String.class);
            Consumer<Validator.AnyOfOptions<String>> options = choices -> choices.option(rules -> {
            });
            if (overload == 0) {
                builder.anyOf(code, "Explicit message", options);
            } else {
                builder.anyOf(code, options);
            }
            builder.build();
        });
    }

    @Test
    void givenNullAnyOfMessageWhenConfiguringThenRejectsNoLaterThanBuild() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(String.class)
                        .anyOf("choice.invalid", null, choices -> choices.option(rules -> {
                        }))
                        .build()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { " choice.invalid ", "\u2003choice.invalid\u3000", "choice invalid", "#.choice[0]",
            "\u00A0" })
    void givenNonblankAnyOfCodeWhenEveryOptionFailsThenPreservesItsExactTextWithoutChangingThePath(String code) {
        Validator<String> rich = Validator.builder(String.class)
                .anyOf(code, "Explicit message", choices -> choices.option(rules -> rules.rule(value -> false)))
                .build();
        Validator<String> coded = Validator.builder(String.class)
                .anyOf(code, choices -> choices.option(rules -> rules.rule(value -> false)))
                .build();

        assertEquals("Explicit message", assertSingleErrorAt(rich.validate(null), "#", code).getMessage());
        assertEquals(
                "At least one alternative must be satisfied",
                assertSingleErrorAt(coded.validate(null), "#", code).getMessage()
        );
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = { " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" })
    void givenExplicitBlankAnyOfMessageWhenAllOptionsFailThenPreservesItExactly(String message) {
        Validator<String> validator = Validator.builder(String.class)
                .anyOf("choice.invalid", message, choices -> choices.option(rules -> rules.rule(value -> false)))
                .build();

        assertEquals(message, assertSingleErrorAt(validator.validate(null), "#", "choice.invalid").getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenEmptyOptionWhenValidatingThenAnyOfSucceedsWithAnImmutableEmptyResult(int overload) {
        Validator.Builder<String> builder = Validator.builder(String.class);
        registerAnyOf(builder, overload, choices -> choices.option(rules -> {
        }));

        List<ValidationError> errors = builder.build().validate(null);

        assertTrue(errors.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> errors.add(new ValidationError("#", "extra", "Extra")));
    }

    @Test
    void givenAnyOfAndOptionConsumersWhenRegisteringThenConfiguresEachOnceAndReturnsTheSameOptionsBuilder() {
        AtomicInteger outerConfigurations = new AtomicInteger();
        AtomicInteger optionConfigurations = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        List<Validator.Builder<String>> received = new ArrayList<>();
        Validator.Builder<String> parent = Validator.builder(String.class);

        parent.anyOf(choices -> {
            outerConfigurations.incrementAndGet();
            for (int index = 0; index < 3; index++) {
                assertSame(choices, choices.option(rules -> {
                    optionConfigurations.incrementAndGet();
                    received.add(rules);
                    rules.rule(value -> {
                        evaluations.incrementAndGet();
                        return false;
                    });
                }));
            }
        });

        assertEquals(1, outerConfigurations.get());
        assertEquals(3, optionConfigurations.get());
        assertEquals(3, received.size());
        assertNotSame(parent, received.get(0));
        assertNotSame(received.get(0), received.get(1));
        assertNotSame(received.get(1), received.get(2));
        assertNotSame(received.get(0), received.get(2));
        Validator<String> first = parent.build();
        Validator<String> second = parent.build();
        assertEquals(0, evaluations.get());
        assertEquals(1, first.validate(null).size());
        assertEquals(1, second.validate("value").size());
        assertEquals(6, evaluations.get());
        assertEquals(1, outerConfigurations.get());
        assertEquals(3, optionConfigurations.get());
    }

    @Test
    void givenEarlierPassingOptionWhenRegisteringLaterOptionsThenStillExecutesEveryConfigurationCallback() {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger laterEvaluations = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(choices -> choices.option(rules -> {
                    configurations.incrementAndGet();
                }).option(rules -> {
                    configurations.incrementAndGet();
                    rules.rule(value -> {
                        laterEvaluations.incrementAndGet();
                        return false;
                    });
                }))
                .build();

        assertEquals(2, configurations.get());
        assertTrue(validator.validate(null).isEmpty());
        assertEquals(0, laterEvaluations.get());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenNullOptionAfterAPassingOptionWhenConfiguringThenRejectsEvenThoughRuntimeWouldNeverReachIt(int overload) {
        assertThrows(
                RuntimeException.class,
                () -> registerAnyOf(
                        Validator.builder(String.class),
                        overload,
                        choices -> choices.option(rules -> {
                        }).option(null)
                ).build()
        );
    }

    @Test
    void givenThrowingAnyOfConfigurationCallbackWhenRegisteringThenPropagatesTheOriginalException() {
        IllegalStateException failure = new IllegalStateException("Invalid alternatives configuration");

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> Validator.builder(String.class)
                                .anyOf(choices -> {
                                    throw failure;
                                })
                )
        );
    }

    @Test
    void givenThrowingUnreachableOptionConsumerWhenRegisteringThenPropagatesItsConfigurationExceptionImmediately() {
        IllegalArgumentException failure = new IllegalArgumentException("Invalid option configuration");

        assertSame(
                failure,
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Validator.builder(String.class)
                                .anyOf(choices -> choices.option(rules -> {
                                }).option(rules -> {
                                    throw failure;
                                }))
                )
        );
    }

    @Test
    void givenConfigurationCallbackThrowsJvmErrorWhenRegisteringThenPropagatesTheOriginalError() {
        AssertionError failure = new AssertionError("Configuration failed");

        assertSame(
                failure,
                assertThrows(
                        AssertionError.class,
                        () -> Validator.builder(String.class)
                                .anyOf(choices -> choices.option(rules -> {
                                    throw failure;
                                }))
                )
        );
    }

    @Test
    void givenInvalidRuleInAnUnreachableOptionWhenConfiguringThenRejectsItRatherThanDeferringToRuntime() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(String.class)
                        .anyOf(choices -> choices.option(rules -> {
                        }).option(rules -> rules.rule(" ", value -> true)))
                        .build()
        );
    }

    @Test
    void givenZeroOptionAnyOfInsideAFalseGuardWhenConfiguringThenStillRejectsInvalidConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(String.class)
                        .onlyIf(value -> false, rules -> rules.anyOf(choices -> {
                        }))
                        .build()
        );
    }

    @Test
    void givenNestedZeroOptionAnyOfInAnUnreachableOptionWhenConfiguringThenStillRejectsIt() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(String.class)
                        .anyOf(choices -> choices.option(rules -> {
                        }).option(rules -> rules.anyOf(nested -> {
                        })))
                        .build()
        );
    }

    @Test
    void givenSupertypeConsumersAndValidatorsWhenConfiguringOptionsThenPreservesTheCurrentNodeTypeAndPecsCompatibility() {
        AtomicInteger observed = new AtomicInteger();
        Consumer<Object> broadConsumer = value -> {
            assertNotNull(value);
            observed.incrementAndGet();
        };
        Consumer<? super Validator.Builder<Person>> personRules = broadConsumer;
        Consumer<Object> broadOptions = value -> {
            assertTrue(value instanceof Validator.AnyOfOptions<?>);
            ((Validator.AnyOfOptions<?>) value).option(broadConsumer);
        };
        Consumer<? super Validator.AnyOfOptions<Person>> personOptions = broadOptions;
        Validator<Object> shared = Validator.builder(Object.class).rule(Objects::nonNull).build();
        Predicate<Person> named = person -> person != null && "Ada".equals(person.getName());
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf("rich", "Rich message", personOptions)
                .anyOf("coded", personOptions)
                .anyOf(personOptions)
                .anyOf(
                        choices -> choices.option(personRules)
                                .option(
                                        rules -> rules
                                                .include(shared)
                                                .rule(named)
                                                .field("name", Person::getName, name -> name.notNull())
                                )
                )
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertEquals(4, observed.get());
    }

    @Test
    void givenRetainedOptionBuilderWhenEditedBeforeAndAfterOuterBuildThenEachTemplateSnapshotsItsCurrentRules() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<String> parent = Validator.builder(String.class)
                .anyOf(choices -> choices.option(rules -> {
                    configurations.incrementAndGet();
                    retained.set(rules);
                    rules.rule("initial", value -> false);
                }));
        assertNotNull(retained.get());
        retained.get().rule("before.build", value -> false);
        Validator<String> first = parent.build();
        retained.get().rule("after.build", value -> false);
        Validator<String> second = parent.build();

        assertErrorsAt(
                anyOfOptionErrors(assertSingleErrorAt(first.validate(null), "#", "anyOf.unsatisfied"), 0),
                "# [initial]",
                "# [before.build]"
        );
        assertErrorsAt(
                anyOfOptionErrors(assertSingleErrorAt(second.validate(null), "#", "anyOf.unsatisfied"), 0),
                "# [initial]",
                "# [before.build]",
                "# [after.build]"
        );
        assertEquals(1, configurations.get());
    }

    @Test
    void givenRetainedOptionsBuilderWhenAddingOptionsAcrossBuildsThenEarlierTemplatesKeepTheirOwnOptionSnapshots() {
        AtomicReference<Validator.AnyOfOptions<String>> retained = new AtomicReference<>();
        Validator.Builder<String> parent = Validator.builder(String.class).anyOf(choices -> {
            retained.set(choices);
            choices.option(rules -> rules.rule("first", value -> false));
        });
        assertNotNull(retained.get());
        retained.get().option(rules -> rules.rule("second", value -> false));
        Validator<String> first = parent.build();
        retained.get().option(rules -> rules.rule("third", value -> false));
        Validator<String> second = parent.build();
        retained.get().option(rules -> {
        });
        Validator<String> third = parent.build();

        assertAnyOfOptionIndexes(assertSingleErrorAt(first.validate(null), "#", "anyOf.unsatisfied"), 0, 1);
        assertAnyOfOptionIndexes(assertSingleErrorAt(second.validate(null), "#", "anyOf.unsatisfied"), 0, 1, 2);
        assertTrue(third.validate(null).isEmpty());
        assertAnyOfOptionIndexes(assertSingleErrorAt(first.validate(null), "#", "anyOf.unsatisfied"), 0, 1);
    }

    @Test
    void givenInitiallyEmptyOptionWhenItsBuilderChangesAfterBuildThenOnlyLaterTemplatesSeeTheAddedRule() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        Validator.Builder<String> parent = Validator.builder(String.class)
                .anyOf(choices -> choices.option(retained::set));
        assertNotNull(retained.get());
        Validator<String> emptySnapshot = parent.build();
        retained.get().rule(value -> false);
        Validator<String> failingSnapshot = parent.build();

        assertTrue(emptySnapshot.validate(null).isEmpty());
        assertSingleErrorAt(failingSnapshot.validate(null), "#", "anyOf.unsatisfied");
        assertTrue(emptySnapshot.validate("again").isEmpty());
    }

    @Test
    void givenRetainedNestedFieldAndGuardBuildersInsideAnOptionWhenBuildingThenRecursivelySnapshotsTheirLatestRules() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class).anyOf(choices -> choices.option(rules -> {
            configurations.incrementAndGet();
            rules.field("name", Person::getName, name -> {
                configurations.incrementAndGet();
                name.onlyIf(value -> true, guarded -> {
                    configurations.incrementAndGet();
                    retained.set(guarded);
                    guarded.rule("initial", value -> false);
                });
            });
        }));
        assertNotNull(retained.get());
        retained.get().rule("before.build", value -> false);
        Validator<Person> first = parent.build();
        retained.get().rule("after.build", value -> false);
        Validator<Person> second = parent.build();

        assertErrorsAt(
                anyOfOptionErrors(assertSingleErrorAt(first.validate(new Person()), "#", "anyOf.unsatisfied"), 0),
                "#.name [initial]",
                "#.name [before.build]"
        );
        assertErrorsAt(
                anyOfOptionErrors(assertSingleErrorAt(second.validate(new Person()), "#", "anyOf.unsatisfied"), 0),
                "#.name [initial]",
                "#.name [before.build]",
                "#.name [after.build]"
        );
        assertEquals(3, configurations.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderedAnyOfOutcomes")
    void givenOrderedOptionOutcomesWhenValidatingThenStopsOnlyAtTheFirstSuccessAndPreservesEveryFailedOptionInDiagnostics(
            String scenario,
            List<String> outcomes
    ) {
        List<Integer> evaluated = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf("choice.invalid", "Choose a valid alternative", choices -> {
                    for (int index = 0; index < outcomes.size(); index++) {
                        final int optionIndex = index;
                        choices.option(rules -> rules.rule("option." + optionIndex, "Option " + optionIndex, value -> {
                            evaluated.add(optionIndex);
                            if ("runtime".equals(outcomes.get(optionIndex))) {
                                throw new IllegalStateException("Broken option " + optionIndex);
                            }
                            return "pass".equals(outcomes.get(optionIndex));
                        }));
                    }
                })
                .build();

        List<ValidationError> errors = validator.validate(null);
        List<Integer> expectedEvaluations = new ArrayList<>();
        String result = "fail";
        for (int index = 0; index < outcomes.size(); index++) {
            expectedEvaluations.add(index);
            result = outcomes.get(index);
            if ("pass".equals(result)) {
                break;
            }
        }
        assertEquals(expectedEvaluations, evaluated);
        if ("pass".equals(result)) {
            assertTrue(errors.isEmpty(), "A successful option must discard earlier failed-option diagnostics");
        } else {
            ValidationError error = assertSingleErrorAt(
                    errors,
                    "#",
                    "choice.invalid"
            );
            int[] indexes = new int[expectedEvaluations.size()];
            for (int index = 0; index < indexes.length; index++) {
                indexes[index] = index;
            }
            assertAnyOfOptionIndexes(error, indexes);
            for (int index = 0; index < indexes.length; index++) {
                List<ValidationError> branch = anyOfOptionErrors(error, index);
                assertEquals(1, branch.size());
                if ("runtime".equals(outcomes.get(index))) {
                    assertSingleErrorAt(branch, "#", "validation.executionFailed");
                    assertExceptionDetails(branch.get(0), IllegalStateException.class, "Broken option " + index);
                } else {
                    assertAddedMetadata(
                            branch.get(0),
                            "#",
                            "option." + index,
                            "Option " + index,
                            Collections.emptyMap()
                    );
                }
            }
            assertEquals("Choose a valid alternative", error.getMessage());
            assertAnyOfDiagnosticsImmutable(error);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "pass", "fail", "runtime" })
    void givenIndependentRulesAroundAnyOfWhenItPassesOrFailsThenStillCollectsEveryIndependentFailure(String outcome) {
        AtomicInteger independentEvaluations = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .rule("before", value -> {
                    independentEvaluations.incrementAndGet();
                    return false;
                })
                .anyOf(choices -> choices.option(rules -> rules.rule(value -> {
                    if ("runtime".equals(outcome)) {
                        throw new IllegalStateException("Broken option");
                    }
                    return "pass".equals(outcome);
                })))
                .rule("after", value -> {
                    independentEvaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, independentEvaluations.get());
        if ("pass".equals(outcome)) {
            assertErrorsAt(errors, "# [before]", "# [after]");
        } else {
            assertErrorsAt(
                    errors,
                    "# [before]",
                    "# [after]",
                    "# [anyOf.unsatisfied]"
            );
        }
    }

    @Test
    void givenAnOptionWithOrdinaryAndExecutionFailuresWhenALaterOptionPassesThenEvaluatesItsIndependentRulesAndReturnsSuccess() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger laterOptions = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        "choice.invalid",
                        "Outer ordinary message",
                        choices -> choices.option(
                                rules -> rules
                                        .rule("first.invalid", "First failure", value -> {
                                            evaluations.incrementAndGet();
                                            return false;
                                        })
                                        .rule(value -> {
                                            evaluations.incrementAndGet();
                                            throw new IllegalStateException("Broken independent rule");
                                        })
                                        .rule("last.invalid", "Last failure", value -> {
                                            evaluations.incrementAndGet();
                                            return false;
                                        })
                        )
                                .option(rules -> rules.rule(value -> {
                                    laterOptions.incrementAndGet();
                                    return true;
                                }))
                )
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(3, evaluations.get());
        assertEquals(1, laterOptions.get());
        assertTrue(errors.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void givenAnyOfMetadataOverridesWhenAnOptionThrowsAndNonePassThenKeepsOuterMetadataAndDiagnosticExecutionDetails(
            int overload
    ) {
        Validator.Builder<String> builder = Validator.builder(String.class);
        registerAnyOf(builder, overload, choices -> choices.option(rules -> rules.rule(value -> {
            throw new IllegalArgumentException("Execution failed");
        })));

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(builder.build(), true, null),
                "#.name",
                overload == 2 ? "anyOf.unsatisfied" : "choice.invalid"
        );

        assertEquals(
                overload == 0 ? "Choose a valid alternative" : "At least one alternative must be satisfied",
                error.getMessage()
        );
        assertAnyOfOptionIndexes(error, 0);
        ValidationError diagnostic = assertSingleErrorAt(
                anyOfOptionErrors(error, 0),
                "#.name",
                "validation.executionFailed"
        );
        assertExceptionDetails(diagnostic, IllegalArgumentException.class, "Execution failed");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("anyOfExecutionSources")
    void givenExecutionFailureFromAComposedOptionWhenALaterOptionPassesThenAttemptsItAndReturnsSuccess(
            String source,
            Consumer<Validator.Builder<Person>> failingRules,
            String diagnosticPath,
            String failureMessage
    ) {
        AtomicInteger later = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .anyOf(choices -> choices.option(failingRules).option(rules -> rules.rule(person -> {
                    later.incrementAndGet();
                    return true;
                })))
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("person", person -> person, node -> node.include(shared))
                .build();

        assertTrue(parent.validate(new Person()).isEmpty());
        assertEquals(1, later.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("anyOfExecutionSources")
    void givenExecutionFailureFromAComposedOptionWhenNoOptionPassesThenKeepsItsResolvedPathAndMessageInsideDiagnostics(
            String source,
            Consumer<Validator.Builder<Person>> failingRules,
            String diagnosticPath,
            String failureMessage
    ) {
        AtomicInteger later = new AtomicInteger();
        Validator<Person> shared = Validator.builder(Person.class)
                .anyOf(choices -> choices.option(failingRules).option(rules -> rules.rule(person -> {
                    later.incrementAndGet();
                    return false;
                })))
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field("person", person -> person, node -> node.include(shared))
                .build();

        ValidationError error = assertSingleErrorAt(parent.validate(new Person()), "#.person", "anyOf.unsatisfied");

        assertEquals(1, later.get());
        assertEquals("At least one alternative must be satisfied", error.getMessage());
        assertAnyOfOptionIndexes(error, 0, 1);
        ValidationError diagnostic = assertSingleErrorAt(
                anyOfOptionErrors(error, 0),
                "#.person" + diagnosticPath,
                "validation.executionFailed"
        );
        assertExceptionDetails(diagnostic, IllegalStateException.class, failureMessage);
        assertErrorsAt(anyOfOptionErrors(error, 1), "#.person [rule.unsatisfied]");
        assertAnyOfDiagnosticsImmutable(error);
    }

    @Test
    void givenAnonymousRuntimeExceptionInAnOptionWhenNoOptionPassesThenPreservesItsBinaryClassNameOnlyInDiagnostics() {
        RuntimeException failure = new RuntimeException("Anonymous option failure") {
            private static final long serialVersionUID = 1L;
        };
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices.option(
                                rules -> rules
                                        .rule(value -> {
                                            throw failure;
                                        })
                        )
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "anyOf.unsatisfied");

        assertEquals("At least one alternative must be satisfied", error.getMessage());
        ValidationError diagnostic = assertSingleErrorAt(
                anyOfOptionErrors(error, 0),
                "#",
                "validation.executionFailed"
        );
        assertExceptionDetails(diagnostic, failure.getClass(), "Anonymous option failure");
    }

    @Test
    void givenOptionExceptionWhoseMessageAccessorThrowsWhenValidatingThenReportsTheOriginalExceptionAndContinuesIndependentRules() {
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices.option(
                                rules -> rules
                                        .rule(value -> {
                                            throw new ThrowingMessageException();
                                        })
                        )
                )
                .rule("independent", value -> false)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [anyOf.unsatisfied]", "# [independent]");
        ValidationError error = errors.stream()
                .filter(item -> "anyOf.unsatisfied".equals(item.getCode()))
                .findFirst()
                .get();
        assertEquals("At least one alternative must be satisfied", error.getMessage());
        ValidationError diagnostic = assertSingleErrorAt(
                anyOfOptionErrors(error, 0),
                "#",
                "validation.executionFailed"
        );
        assertExceptionDetails(diagnostic, ThrowingMessageException.class, null);
    }

    @Test
    void givenJvmErrorInAnOptionWhenValidatingThenPropagatesItWithoutEvaluatingLaterOptions() {
        AssertionError failure = new AssertionError("Fatal option failure");
        AtomicInteger later = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(choices -> choices.option(rules -> rules.rule(value -> {
                    throw failure;
                })).option(rules -> rules.rule(value -> {
                    later.incrementAndGet();
                    return true;
                })))
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(null)));
        assertEquals(0, later.get());
    }

    @Test
    void givenCustomOptionReportsThenThrowsWhenValidatingThenRetainsItsReportsOnlyInDiagnosticsAndExpiresEverySinkView() {
        AtomicReference<Validator.ErrorSink> root = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> target = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(choices -> choices.option(rules -> rules.custom((value, errors) -> {
                    root.set(errors);
                    Validator.ErrorSink child = errors.field("lines").atIndex(2).field("sku");
                    target.set(child);
                    child.add("sku.invalid", "Invalid SKU", Collections.singletonMap("min", 3));
                    throw new IllegalStateException("Broken custom option");
                })))
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, true, null),
                "#.name",
                "anyOf.unsatisfied"
        );

        assertErrorsAt(
                anyOfOptionErrors(error, 0),
                "#.name.lines[2].sku [sku.invalid]",
                "#.name [validation.executionFailed]"
        );
        ValidationError report = anyOfOptionErrors(error, 0).stream()
                .filter(item -> "sku.invalid".equals(item.getCode()))
                .findFirst()
                .get();
        assertAddedMetadata(
                report,
                "#.name.lines[2].sku",
                "sku.invalid",
                "Invalid SKU",
                Collections.singletonMap("min", 3)
        );
        assertExpiredSinkOperations(root.get());
        assertExpiredSinkOperations(target.get());
    }

    @Test
    void givenCustomOptionReportsThenThrowsWhenALaterOptionRunsThenItsCapturedSinksAreAlreadyExpiredAndSuccessDiscardsAllReports() {
        AtomicReference<Validator.ErrorSink> root = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> target = new AtomicReference<>();
        AtomicInteger fallback = new AtomicInteger();
        AtomicInteger unvisited = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.custom((value, errors) -> {
                                    root.set(errors);
                                    target.set(errors.field("child").atIndex(1));
                                    target.get().add("discarded");
                                    throw new IllegalStateException("Failed custom option");
                                }))
                                .option(rules -> rules.custom((value, errors) -> {
                                    fallback.incrementAndGet();
                                    assertExpiredSinkOperations(root.get());
                                    assertExpiredSinkOperations(target.get());
                                }))
                                .option(rules -> rules.rule(value -> {
                                    unvisited.incrementAndGet();
                                    return false;
                                }))
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(1, fallback.get());
        assertEquals(0, unvisited.get());
        assertExpiredSinkOperations(root.get());
        assertExpiredSinkOperations(target.get());
    }

    @Test
    void givenCustomOptionThrowsJvmErrorWhenValidatingThenStillExpiresItsRootAndDerivedTargets() {
        AtomicReference<Validator.ErrorSink> root = new AtomicReference<>();
        AtomicReference<Validator.ErrorSink> target = new AtomicReference<>();
        AssertionError failure = new AssertionError("Fatal custom failure");
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices.option(
                                rules -> rules
                                        .custom((value, errors) -> {
                                            root.set(errors);
                                            target.set(errors.field("child"));
                                            throw failure;
                                        })
                        )
                )
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(null)));
        assertExpiredSinkOperations(root.get());
        assertExpiredSinkOperations(target.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "valid" })
    void givenNullableCurrentNodeWhenAllOptionsFailThenEachOptionReceivesTheExactSameValue(String input) {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.rule(value -> {
                                    assertSame(input, value);
                                    evaluations.incrementAndGet();
                                    return false;
                                }))
                                .option(rules -> rules.rule(value -> {
                                    assertSame(input, value);
                                    evaluations.incrementAndGet();
                                    return false;
                                }))
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validateAtRootOrNameField(validator, true, input),
                "#.name",
                "anyOf.unsatisfied"
        );

        assertEquals(2, evaluations.get());
        assertAnyOfOptionIndexes(error, 0, 1);
        assertErrorsAt(anyOfOptionErrors(error, 0), "#.name [rule.unsatisfied]");
        assertErrorsAt(anyOfOptionErrors(error, 1), "#.name [rule.unsatisfied]");
    }

    @Test
    void givenNullRootAndNullAwareOptionsWhenValidatingThenAnOrdinaryNotNullFailureCanBeSatisfiedByALaterOption() {
        AtomicInteger fallback = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.notNull())
                                .option(rules -> rules.rule(value -> {
                                    fallback.incrementAndGet();
                                    return value == null;
                                }))
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(1, fallback.get());
    }

    @Test
    void givenNullSensitiveOptionOnANullRootWhenALaterOptionPassesThenDiscardsTheExecutionFailureAndReturnsSuccess() {
        AtomicInteger fallback = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.rule(value -> !value.isEmpty()))
                                .option(rules -> rules.rule(value -> {
                                    fallback.incrementAndGet();
                                    return true;
                                }))
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(1, fallback.get());
    }

    @Test
    void givenSkippedFieldTraversalInAnOptionWhenALaterOptionPassesThenTreatsTheSkipAsAnOrdinaryOptionFailure() {
        AtomicInteger fallback = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf(
                        choices -> choices
                                .option(
                                        rules -> rules
                                                .field("address", Person::getAddress, address -> address.notNull())
                                )
                                .option(rules -> rules.rule(person -> {
                                    fallback.incrementAndGet();
                                    return person == null;
                                }))
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(1, fallback.get());
    }

    @Test
    void givenSkippedFieldTraversalAndNoPassingAlternativeWhenValidatingThenReturnsOrdinaryAnyOfFailureWithTheSkippedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf(
                        choices -> choices
                                .option(
                                        rules -> rules
                                                .field("address", Person::getAddress, address -> address.notNull())
                                )
                                .option(rules -> rules.notNull())
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "anyOf.unsatisfied");

        assertAnyOfOptionIndexes(error, 0, 1);
        assertErrorsAt(anyOfOptionErrors(error, 0), "#.address [validation.skipped]");
        assertErrorsAt(anyOfOptionErrors(error, 1), "# [notNull]");
    }

    @Test
    void givenAnyOfInsideAFieldWhenTheParentIsNullThenReportsOnlyTheBlockedFieldWithoutEvaluatingOptions() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.anyOf(
                                choices -> choices.option(
                                        rules -> rules
                                                .rule(value -> {
                                                    evaluations.incrementAndGet();
                                                    return false;
                                                })
                                )
                        )
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");

        assertEquals(0, evaluations.get());
        assertEmptyImmutableAttributes(error);
    }

    @Test
    void givenExtractedNullFieldWhenOptionsAreNullAwareThenValidatesTheNullNodeWithoutTreatingItAsBlockedTraversal() {
        AtomicReference<Address> seen = new AtomicReference<>();
        AtomicInteger fallback = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address.anyOf(
                                choices -> choices
                                        .option(rules -> rules.notNull())
                                        .option(rules -> rules.rule(value -> {
                                            seen.set(value);
                                            fallback.incrementAndGet();
                                            return value == null;
                                        }))
                        )
                )
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
        assertEquals(1, fallback.get());
        assertNull(seen.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenAnyOfInsideOnlyIfWhenValidatingThenTheGuardControlsRuntimeWithoutSuppressingConfiguration(
            boolean active
    ) {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .onlyIf(value -> active, guarded -> guarded.anyOf(choices -> choices.option(rules -> {
                    configurations.incrementAndGet();
                    rules.rule(value -> {
                        evaluations.incrementAndGet();
                        return false;
                    });
                })))
                .build();

        List<ValidationError> errors = validateAtRootOrNameField(validator, true, null);

        assertEquals(1, configurations.get());
        assertEquals(active ? 1 : 0, evaluations.get());
        if (active) {
            assertSingleErrorAt(errors, "#.name", "anyOf.unsatisfied");
        } else {
            assertTrue(errors.isEmpty());
        }
    }

    @Test
    void givenAnOptionWithOnlyAFalseGuardWhenValidatingThenItsEmptyResultSatisfiesAnyOfAndStopsLaterOptions() {
        AtomicInteger guarded = new AtomicInteger();
        AtomicInteger later = new AtomicInteger();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.rule(value -> false))
                                .option(rules -> rules.onlyIf(value -> false, block -> block.rule(value -> {
                                    guarded.incrementAndGet();
                                    return false;
                                })))
                                .option(rules -> rules.rule(value -> {
                                    later.incrementAndGet();
                                    return true;
                                }))
                )
                .build();

        assertTrue(validator.validate(null).isEmpty());
        assertEquals(0, guarded.get());
        assertEquals(0, later.get());
    }

    @Test
    void givenSeveralErrorsAndDuplicateMetadataInOneOptionWhenItFailsThenRetainsEveryDiagnosticWithoutAddingBranchErrorsToTheResult() {
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices.option(
                                rules -> rules
                                        .rule("duplicate", "Same message", value -> false)
                                        .rule("duplicate", "Same message", value -> false)
                                        .custom((value, errors) -> errors.add("duplicate", "Same message"))
                        )
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "anyOf.unsatisfied");

        assertAnyOfOptionIndexes(error, 0);
        assertErrorsAt(anyOfOptionErrors(error, 0), "# [duplicate]", "# [duplicate]", "# [duplicate]");
        anyOfOptionErrors(error, 0).forEach(
                item -> assertAddedMetadata(
                        item,
                        "#",
                        "duplicate",
                        "Same message",
                        Collections.emptyMap()
                )
        );
    }

    @Test
    void givenReusableSupertypeValidatorInAnOptionWhenIncludedAtDifferentScopesThenRebasesEachDiagnosticExactlyOnce() {
        Validator<Object> reusable = Validator.builder(Object.class)
                .custom(
                        (value, errors) -> errors.field("detail")
                                .atIndex(2)
                                .add(
                                        "detail.invalid",
                                        "Invalid detail",
                                        Collections.singletonMap("min", 3)
                                )
                )
                .build();
        Validator<String> shared = Validator.builder(String.class)
                .anyOf(choices -> choices.option(rules -> rules.include(reusable)))
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field(
                        "name",
                        Person::getName,
                        rules -> rules
                                .onlyIf(value -> true, active -> active.include(shared))
                )
                .build();

        ValidationError original = assertSingleErrorAt(shared.validate(null), "#", "anyOf.unsatisfied");
        ValidationError nested = assertSingleErrorAt(parent.validate(new Person()), "#.name", "anyOf.unsatisfied");

        assertAddedMetadata(
                anyOfOptionErrors(original, 0).get(0),
                "#.detail[2]",
                "detail.invalid",
                "Invalid detail",
                Collections.singletonMap("min", 3)
        );
        assertAddedMetadata(
                anyOfOptionErrors(nested, 0).get(0),
                "#.name.detail[2]",
                "detail.invalid",
                "Invalid detail",
                Collections.singletonMap("min", 3)
        );
        assertSingleErrorAt(reusable.validate(null), "#.detail[2]", "detail.invalid");
        assertEquals("#.detail[2]", anyOfOptionErrors(original, 0).get(0).getPath());
    }

    @Test
    void givenRepeatedUseOfTheSameFailingValidatorWhenEvaluatingOptionsThenPreservesEachOptionIndexAndReevaluatesEachUse() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<String> reusable = Validator.builder(String.class).rule("shared.invalid", value -> {
            evaluations.incrementAndGet();
            return false;
        }).build();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.include(reusable))
                                .option(rules -> rules.include(reusable))
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#", "anyOf.unsatisfied");

        assertEquals(2, evaluations.get());
        assertAnyOfOptionIndexes(error, 0, 1);
        assertErrorsAt(anyOfOptionErrors(error, 0), "# [shared.invalid]");
        assertErrorsAt(anyOfOptionErrors(error, 1), "# [shared.invalid]");
    }

    @Test
    void givenMultipleAnyOfRulesAtTheSameNodeWhenBothFailThenReturnsSeparateOuterErrorsWithIndependentOptions() {
        List<Validator.AnyOfOptions<String>> configured = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf("first.choice", "First choice", choices -> {
                    configured.add(choices);
                    choices.option(rules -> rules.rule("first.branch", value -> false));
                })
                .anyOf("second.choice", "Second choice", choices -> {
                    configured.add(choices);
                    choices.option(rules -> rules.rule("second.branch", value -> false));
                    choices.option(rules -> rules.rule("third.branch", value -> false));
                })
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertErrorsAt(errors, "# [first.choice]", "# [second.choice]");
        assertEquals(2, configured.size());
        assertNotSame(configured.get(0), configured.get(1));
        ValidationError first = errors.stream().filter(item -> "first.choice".equals(item.getCode())).findFirst().get();
        ValidationError second = errors.stream()
                .filter(item -> "second.choice".equals(item.getCode()))
                .findFirst()
                .get();
        assertAnyOfOptionIndexes(first, 0);
        assertAnyOfOptionIndexes(second, 0, 1);
        assertErrorsAt(anyOfOptionErrors(first, 0), "# [first.branch]");
        assertErrorsAt(anyOfOptionErrors(second, 0), "# [second.branch]");
        assertErrorsAt(anyOfOptionErrors(second, 1), "# [third.branch]");
    }

    @Test
    void givenNestedOrdinaryAnyOfFailureWhenValidatingThenKeepsNestedErrorsInsideDiagnosticsAtTheirResolvedNodePaths() {
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf(
                        "outer.invalid",
                        "Outer message",
                        choices -> choices.option(
                                rules -> rules
                                        .field(
                                                "address",
                                                Person::getAddress,
                                                address -> address.anyOf(
                                                        "inner.invalid",
                                                        "Inner message",
                                                        inner -> inner.option(
                                                                branch -> branch.field(
                                                                        "streetName",
                                                                        Address::getStreetName,
                                                                        street -> street.notNull()
                                                                )
                                                        )
                                                )
                                        )
                        )
                )
                .build();

        ValidationError outer = assertSingleErrorAt(
                validator.validate(new Person(new Address(null))),
                "#",
                "outer.invalid"
        );
        ValidationError inner = assertSingleErrorAt(anyOfOptionErrors(outer, 0), "#.address", "inner.invalid");

        assertEquals("Outer message", outer.getMessage());
        assertEquals("Inner message", inner.getMessage());
        assertAnyOfOptionIndexes(outer, 0);
        assertAnyOfOptionIndexes(inner, 0);
        assertErrorsAt(anyOfOptionErrors(inner, 0), "#.address.streetName [notNull]");
        assertAnyOfDiagnosticsImmutable(outer);
        assertAnyOfDiagnosticsImmutable(inner);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void givenNestedExecutionFailureWhenAnInnerOrOuterFallbackPassesThenReturnsSuccessAndStopsAfterThatSuccess(
            boolean innerPasses
    ) {
        AtomicInteger innerFallback = new AtomicInteger();
        AtomicInteger outerFallback = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf(
                        choices -> choices
                                .option(
                                        rules -> rules.field(
                                                "address",
                                                Person::getAddress,
                                                address -> address.anyOf(
                                                        inner -> inner
                                                                .option(
                                                                        branch -> branch.field(
                                                                                "streetName",
                                                                                Address::getStreetName,
                                                                                street -> street.rule(value -> {
                                                                                    throw new IllegalStateException(
                                                                                            "Broken nested rule"
                                                                                    );
                                                                                })
                                                                        )
                                                                )
                                                                .option(branch -> branch.rule(value -> {
                                                                    innerFallback.incrementAndGet();
                                                                    return innerPasses;
                                                                }))
                                                )
                                        )
                                )
                                .option(rules -> rules.rule(value -> {
                                    outerFallback.incrementAndGet();
                                    return true;
                                }))
                )
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertEquals(1, innerFallback.get());
        assertEquals(innerPasses ? 0 : 1, outerFallback.get());
    }

    @Test
    void givenNestedExecutionFailureWhenNoInnerOrOuterOptionPassesThenKeepsExecutionDetailsOnlyInNestedDiagnostics() {
        AtomicInteger evaluatedFallbacks = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .anyOf(
                        choices -> choices
                                .option(
                                        rules -> rules.field(
                                                "address",
                                                Person::getAddress,
                                                address -> address
                                                        .anyOf(
                                                                "inner.invalid",
                                                                "Inner alternatives failed",
                                                                inner -> inner
                                                                        .option(
                                                                                branch -> branch.field(
                                                                                        "streetName",
                                                                                        Address::getStreetName,
                                                                                        street -> street.rule(value -> {
                                                                                            throw new IllegalStateException(
                                                                                                    "Broken nested rule"
                                                                                            );
                                                                                        })
                                                                                )
                                                                        )
                                                                        .option(branch -> branch.rule(value -> {
                                                                            evaluatedFallbacks.incrementAndGet();
                                                                            return false;
                                                                        }))
                                                        )
                                        )
                                )
                                .option(rules -> rules.rule(value -> {
                                    evaluatedFallbacks.incrementAndGet();
                                    return false;
                                }))
                )
                .build();

        ValidationError outer = assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#",
                "anyOf.unsatisfied"
        );
        assertAnyOfOptionIndexes(outer, 0, 1);
        ValidationError inner = assertSingleErrorAt(anyOfOptionErrors(outer, 0), "#.address", "inner.invalid");

        assertEquals(2, evaluatedFallbacks.get());
        assertEquals("At least one alternative must be satisfied", outer.getMessage());
        assertEquals("Inner alternatives failed", inner.getMessage());
        assertAnyOfOptionIndexes(inner, 0, 1);
        ValidationError execution = assertSingleErrorAt(
                anyOfOptionErrors(inner, 0),
                "#.address.streetName",
                "validation.executionFailed"
        );
        assertExceptionDetails(execution, IllegalStateException.class, "Broken nested rule");
        assertErrorsAt(anyOfOptionErrors(inner, 1), "#.address [rule.unsatisfied]");
        assertErrorsAt(anyOfOptionErrors(outer, 1), "# [rule.unsatisfied]");
        assertAnyOfDiagnosticsImmutable(outer);
        assertAnyOfDiagnosticsImmutable(inner);
    }

    @Test
    void givenRetainedInnerAnyOfOptionBuilderWhenEditedAcrossOuterBuildsThenRecursivelySnapshotsNestedAlternatives() {
        AtomicReference<Validator.Builder<String>> retained = new AtomicReference<>();
        Validator.Builder<String> parent = Validator.builder(String.class)
                .anyOf(
                        choices -> choices.option(
                                rules -> rules
                                        .anyOf(inner -> inner.option(branch -> {
                                            retained.set(branch);
                                            branch.rule("initial", value -> false);
                                        }))
                        )
                );
        assertNotNull(retained.get());
        retained.get().rule("before.build", value -> false);
        Validator<String> first = parent.build();
        retained.get().rule("after.build", value -> false);
        Validator<String> second = parent.build();

        ValidationError firstInner = anyOfOptionErrors(
                assertSingleErrorAt(first.validate(null), "#", "anyOf.unsatisfied"),
                0
        )
                .get(0);
        ValidationError secondInner = anyOfOptionErrors(
                assertSingleErrorAt(second.validate(null), "#", "anyOf.unsatisfied"),
                0
        )
                .get(0);
        assertErrorsAt(anyOfOptionErrors(firstInner, 0), "# [initial]", "# [before.build]");
        assertErrorsAt(anyOfOptionErrors(secondInner, 0), "# [initial]", "# [before.build]", "# [after.build]");
    }

    @Test
    void givenMutableNestedCustomAttributesWhenAnyOfFailsThenShallowCopiesTheMapAndRetainsCallerOwnedValueReferences() {
        Map<String, Object> constraint = new LinkedHashMap<>();
        List<Object> values = new ArrayList<>();
        values.add("original");
        values.add(null);
        constraint.put("values", values);
        constraint.put("min", 3);
        Object marker = new Object();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("constraint", constraint);
        attributes.put("marker", marker);
        Validator<String> shared = Validator.builder(String.class)
                .custom((value, errors) -> errors.add("custom.invalid", "Custom message", attributes))
                .build();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.include(shared))
                )
                .build();

        ValidationError first = assertSingleErrorAt(validator.validate(null), "#", "anyOf.unsatisfied");
        ValidationError firstDiagnostic = assertSingleErrorAt(anyOfOptionErrors(first, 0), "#", "custom.invalid");

        assertEquals("Custom message", firstDiagnostic.getMessage());
        assertEquals(attributes, firstDiagnostic.getAttributes());
        assertSame(constraint, firstDiagnostic.getAttributes().get("constraint"));
        assertSame(marker, firstDiagnostic.getAttributes().get("marker"));
        assertAnyOfDiagnosticsImmutable(first);

        values.set(0, "changed");
        constraint.put("min", 9);
        attributes.put("constraint", "replacement");
        attributes.remove("marker");
        attributes.put("later", true);

        // The outer map is a snapshot. Its values remain caller-owned references.
        assertEquals(2, firstDiagnostic.getAttributes().size());
        assertSame(constraint, firstDiagnostic.getAttributes().get("constraint"));
        assertSame(marker, firstDiagnostic.getAttributes().get("marker"));
        assertSame(values, constraint.get("values"));
        assertEquals(Arrays.asList("changed", null), values);
        assertEquals(9, constraint.get("min"));
        assertFalse(firstDiagnostic.getAttributes().containsKey("later"));

        ValidationError second = assertSingleErrorAt(validator.validate("again"), "#", "anyOf.unsatisfied");
        assertAddedMetadata(anyOfOptionErrors(second, 0).get(0), "#", "custom.invalid", "Custom message", attributes);
        assertSame(constraint, firstDiagnostic.getAttributes().get("constraint"));
        assertAnyOfDiagnosticsImmutable(first);
        assertAnyOfDiagnosticsImmutable(second);
    }

    @Test
    void givenFailedOptionSinkWhenALaterOptionSucceedsThenDiscardsItsReportsButKeepsCapturedTargetsExpired() {
        AtomicReference<Validator.ErrorSink> retained = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.custom((value, errors) -> {
                                    Validator.ErrorSink target = errors.atIndex(2).field("value");
                                    retained.set(target);
                                    target.add("discarded");
                                }))
                                .option(rules -> {
                                })
                )
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertTrue(errors.isEmpty());
        assertExpiredSinkOperations(retained.get());
        assertTrue(errors.isEmpty());
    }

    @Test
    void givenDifferentOutcomesAcrossCallsWhenReusingAnyOfThenDoesNotRetainEarlierDiagnosticsOrShortCircuitState() {
        List<Integer> evaluated = new ArrayList<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.rule("first", value -> {
                                    evaluated.add(0);
                                    if ("runtime".equals(value)) {
                                        throw new IllegalStateException("Broken first option");
                                    }
                                    return "first".equals(value);
                                }))
                                .option(rules -> rules.rule("second", value -> {
                                    evaluated.add(1);
                                    return "second".equals(value);
                                }))
                )
                .build();

        ValidationError ordinary = assertSingleErrorAt(validator.validate("neither"), "#", "anyOf.unsatisfied");
        assertEquals(Arrays.asList(0, 1), evaluated);
        evaluated.clear();
        assertTrue(validator.validate("first").isEmpty());
        assertEquals(Collections.singletonList(0), evaluated);
        evaluated.clear();
        ValidationError withExecution = assertSingleErrorAt(
                validator.validate("runtime"),
                "#",
                "anyOf.unsatisfied"
        );
        assertEquals(Arrays.asList(0, 1), evaluated);
        evaluated.clear();
        assertTrue(validator.validate("second").isEmpty());
        assertEquals(Arrays.asList(0, 1), evaluated);
        assertAnyOfOptionIndexes(ordinary, 0, 1);
        assertAnyOfOptionIndexes(withExecution, 0, 1);
        ValidationError execution = assertSingleErrorAt(
                anyOfOptionErrors(withExecution, 0),
                "#",
                "validation.executionFailed"
        );
        assertExceptionDetails(execution, IllegalStateException.class, "Broken first option");
        assertAnyOfDiagnosticsImmutable(ordinary);
        assertAnyOfDiagnosticsImmutable(withExecution);
    }

    @Test
    void givenReentrantValidationFromAnOptionWhenInnerAndOuterCallsFailThenKeepsTheirPathsAndDiagnosticsIndependent() {
        AtomicReference<Validator<String>> reusable = new AtomicReference<>();
        AtomicReference<List<ValidationError>> innerResults = new AtomicReference<>();
        Validator<String> validator = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.custom((value, errors) -> {
                                    if ("outer".equals(value)) {
                                        innerResults.set(reusable.get().validate("inner"));
                                    }
                                    errors.field("detail")
                                            .add(
                                                    "custom.invalid",
                                                    "Invalid detail",
                                                    Collections.singletonMap("value", value)
                                            );
                                }))
                                .option(rules -> rules.rule(value -> false))
                )
                .build();
        reusable.set(validator);

        ValidationError outer = assertSingleErrorAt(
                validateAtRootOrNameField(validator, true, "outer"),
                "#.name",
                "anyOf.unsatisfied"
        );
        assertNotNull(innerResults.get());
        ValidationError inner = assertSingleErrorAt(innerResults.get(), "#", "anyOf.unsatisfied");

        assertAnyOfOptionIndexes(outer, 0, 1);
        assertAnyOfOptionIndexes(inner, 0, 1);
        assertAddedMetadata(
                anyOfOptionErrors(outer, 0).get(0),
                "#.name.detail",
                "custom.invalid",
                "Invalid detail",
                Collections.singletonMap("value", "outer")
        );
        assertAddedMetadata(
                anyOfOptionErrors(inner, 0).get(0),
                "#.detail",
                "custom.invalid",
                "Invalid detail",
                Collections.singletonMap("value", "inner")
        );
        assertAnyOfDiagnosticsImmutable(outer);
        assertAnyOfDiagnosticsImmutable(inner);
    }

    @Test
    void givenSharedAnyOfWithDifferentOutcomesWhenValidatingConcurrentlyThenKeepsPathsDiagnosticsAndShortCircuitStatePerCall()
            throws Exception {
        List<Person> inputs = Arrays.asList(
                new Person("pass", null, 18, Collections.emptyList()),
                new Person("bad", null, 18, Collections.emptyList()),
                new Person("runtime", null, 18, Collections.emptyList()),
                new Person(null, null, 18, Collections.emptyList())
        );
        String[][] expected = { {}, { "#.name [anyOf.unsatisfied]" }, { "#.name [anyOf.unsatisfied]" },
                { "#.name [anyOf.unsatisfied]" } };
        CyclicBarrier barrier = new CyclicBarrier(inputs.size());
        Validator<String> shared = Validator.builder(String.class)
                .anyOf(
                        choices -> choices
                                .option(rules -> rules.custom((value, errors) -> {
                                    awaitConcurrentValidation(barrier);
                                    errors.field("detail")
                                            .add(
                                                    "first.invalid",
                                                    "First option failed",
                                                    Collections
                                                            .singletonMap("length", value == null ? -1 : value.length())
                                            );
                                }))
                                .option(rules -> rules.rule(value -> {
                                    if ("runtime".equals(value)) {
                                        throw new IllegalStateException("Concurrent option failure");
                                    }
                                    return "pass".equals(value);
                                }))
                                .option(rules -> rules.rule(value -> false))
                )
                .rule(value -> {
                    awaitConcurrentValidation(barrier);
                    return true;
                })
                .build();
        Validator<Person> parent = Validator.builder(Person.class)
                .field(
                        "name",
                        Person::getName,
                        name -> name.include(shared)
                )
                .build();

        assertConcurrentResults(parent, inputs, expected, (person, errors) -> {
            if (!"pass".equals(person.getName())) {
                ValidationError error = errors.get(0);
                assertAnyOfOptionIndexes(error, 0, 1, 2);
                if ("runtime".equals(person.getName())) {
                    ValidationError execution = assertSingleErrorAt(
                            anyOfOptionErrors(error, 1),
                            "#.name",
                            "validation.executionFailed"
                    );
                    assertExceptionDetails(execution, IllegalStateException.class, "Concurrent option failure");
                }
                assertAddedMetadata(
                        anyOfOptionErrors(error, 0).get(0),
                        "#.name.detail",
                        "first.invalid",
                        "First option failed",
                        Collections.singletonMap("length", person.getName() == null ? -1 : person.getName().length())
                );
                assertAnyOfDiagnosticsImmutable(error);
            }
        });
    }

    // ValidationError: constructor metadata guarantees.

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" })
    void givenNullOrBlankPathWhenCreatingValidationErrorThenRejectsInvalidMetadata(String path) {
        assertThrows(
                RuntimeException.class,
                () -> new ValidationError(path, "person.required", "Person is required")
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" })
    void givenNullOrBlankCodeWhenCreatingValidationErrorThenRejectsInvalidMetadata(String code) {
        assertThrows(
                RuntimeException.class,
                () -> new ValidationError("#", code, "Person is required")
        );
    }

    @Test
    void givenNullMessageWhenCreatingValidationErrorThenRejectsInvalidMetadata() {
        assertThrows(
                RuntimeException.class,
                () -> new ValidationError("#", "person.required", null)
        );
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = { "   ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" })
    void givenBlankMessageWhenCreatingValidationErrorThenPreservesExplicitMessage(String message) {
        ValidationError error = new ValidationError("#", "person.required", message);

        assertEquals(message, error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "#", "#.address", "#.lines[0].sku", "#.contacts[1].value.number" })
    void givenValidRootOrNestedPathWhenCreatingValidationErrorThenPreservesAllMetadata(String path) {
        ValidationError error = new ValidationError(path, "value.invalid", "Value is invalid");

        assertAll(
                () -> assertEquals(path, error.getPath()),
                () -> assertEquals("value.invalid", error.getCode()),
                () -> assertEquals("Value is invalid", error.getMessage())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { "#. postalAddress ", "#.\u2003postalAddress\u3000", "#.postal address" })
    void givenNonblankPathContainingWhitespaceWhenCreatingValidationErrorThenPreservesExactPath(String path) {
        ValidationError error = new ValidationError(path, "address.invalid", "Address is invalid");

        assertEquals(path, error.getPath());
    }

    @ParameterizedTest
    @ValueSource(strings = { " person.required ", "\u2003person.required\u3000", "person required" })
    void givenNonblankCodeContainingWhitespaceWhenCreatingValidationErrorThenPreservesExactCode(String code) {
        ValidationError error = new ValidationError("#", code, "Person is required");

        assertEquals(code, error.getCode());
    }

    private static Validator.Builder<String> registerAnyOf(
            Validator.Builder<String> builder,
            int overload,
            Consumer<? super Validator.AnyOfOptions<String>> options
    ) {
        if (overload == 0) {
            return builder.anyOf("choice.invalid", "Choose a valid alternative", options);
        }
        if (overload == 1) {
            return builder.anyOf("choice.invalid", options);
        }
        if (overload == 2) {
            return builder.anyOf(options);
        }
        throw new AssertionError("Unknown anyOf overload: " + overload);
    }

    private static Stream<Arguments> invalidAnyOfCodes() {
        List<Arguments> cases = new ArrayList<>();
        String[] codes = { null, "", " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" };
        for (int overload = 0; overload < 2; overload++) {
            for (String code : codes) {
                cases.add(Arguments.of(overload, code));
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> orderedAnyOfOutcomes() {
        List<Arguments> cases = new ArrayList<>();
        String[] outcomes = { "fail", "pass", "runtime" };
        for (String first : outcomes) {
            cases.add(Arguments.of(first, Collections.singletonList(first)));
            for (String second : outcomes) {
                for (String third : outcomes) {
                    List<String> sequence = Arrays.asList(first, second, third);
                    cases.add(Arguments.of(String.join(" -> ", sequence), sequence));
                }
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> anyOfExecutionSources() {
        Consumer<Validator.Builder<Person>> predicate = rules -> rules.rule(person -> {
            throw new IllegalStateException("Broken predicate");
        });
        Consumer<Validator.Builder<Person>> extractor = rules -> rules.field("address", person -> {
            throw new IllegalStateException("Broken extractor");
        }, address -> address.notNull());
        Consumer<Validator.Builder<Person>> guard = rules -> rules.onlyIf(person -> {
            throw new IllegalStateException("Broken guard");
        }, guarded -> guarded.notNull());
        Consumer<Validator.Builder<Person>> custom = rules -> rules.custom((person, errors) -> {
            throw new IllegalStateException("Broken callback");
        });
        Validator<Object> shared = Validator.builder(Object.class).rule(value -> {
            throw new IllegalStateException("Broken inclusion");
        }).build();
        Consumer<Validator.Builder<Person>> inclusion = rules -> rules.include(shared);
        return Stream.of(
                Arguments.of("predicate", predicate, "", "Broken predicate"),
                Arguments.of("extractor", extractor, ".address", "Broken extractor"),
                Arguments.of("guard", guard, "", "Broken guard"),
                Arguments.of("custom callback", custom, "", "Broken callback"),
                Arguments.of("included supertype validator", inclusion, "", "Broken inclusion")
        );
    }

    private static List<?> anyOfOptionFailures(ValidationError error) {
        assertEquals(Collections.singleton("optionFailures"), error.getAttributes().keySet());
        Object failures = error.getAttributes().get("optionFailures");
        assertTrue(failures instanceof List<?>, "optionFailures must be a list in option-definition order");
        return (List<?>) failures;
    }

    private static Map<?, ?> anyOfOptionFailure(ValidationError error, int entryIndex) {
        Object entry = anyOfOptionFailures(error).get(entryIndex);
        assertTrue(entry instanceof Map<?, ?>, "Each failed-option entry must contain optionIndex and errors");
        Map<?, ?> fields = (Map<?, ?>) entry;
        assertEquals(2, fields.size());
        assertTrue(fields.containsKey("optionIndex"));
        assertTrue(fields.containsKey("errors"));
        return fields;
    }

    private static void assertAnyOfOptionIndexes(ValidationError error, int... indexes) {
        assertEquals(indexes.length, anyOfOptionFailures(error).size());
        for (int entry = 0; entry < indexes.length; entry++) {
            assertEquals(indexes[entry], anyOfOptionFailure(error, entry).get("optionIndex"));
        }
    }

    private static List<ValidationError> anyOfOptionErrors(ValidationError error, int entryIndex) {
        Object errors = anyOfOptionFailure(error, entryIndex).get("errors");
        assertTrue(errors instanceof List<?>, "Each failed option must contain a list of ValidationError objects");
        List<?> entries = (List<?>) errors;
        assertFalse(entries.isEmpty(), "A failed option must have at least one diagnostic error");
        List<ValidationError> typed = new ArrayList<>();
        for (Object entry : entries) {
            assertTrue(entry instanceof ValidationError, "Reuse ValidationError for option diagnostics");
            typed.add((ValidationError) entry);
        }
        return typed;
    }

    private static void assertAnyOfDiagnosticsImmutable(ValidationError error) {
        assertImmutableAttributes(error);
        List<?> failures = anyOfOptionFailures(error);
        assertUnmodifiableDiagnosticList(failures);
        for (int index = 0; index < failures.size(); index++) {
            Map<?, ?> entry = anyOfOptionFailure(error, index);
            assertThrows(UnsupportedOperationException.class, () -> entry.put(null, null));
            List<?> diagnosticErrors = (List<?>) entry.get("errors");
            assertUnmodifiableDiagnosticList(diagnosticErrors);
            for (ValidationError diagnostic : anyOfOptionErrors(error, index)) {
                // Custom attribute values are deliberately not traversed or frozen.
                assertImmutableAttributes(diagnostic);
            }
        }
    }

    private static void assertUnmodifiableDiagnosticList(List<?> list) {
        assertThrows(UnsupportedOperationException.class, () -> list.add(null));
        if (!list.isEmpty()) {
            assertThrows(UnsupportedOperationException.class, () -> list.set(0, null));
            assertThrows(UnsupportedOperationException.class, () -> list.remove(0));
        }
    }

    private static Stream<Arguments> derivedSinkTargets() {
        Function<Validator.ErrorSink, Validator.ErrorSink> field = errors -> errors.field("child");
        Function<Validator.ErrorSink, Validator.ErrorSink> index = errors -> errors.atIndex(2);
        Function<Validator.ErrorSink, Validator.ErrorSink> nested = errors -> errors.field("lines")
                .atIndex(2)
                .field("sku");
        return Stream.of(
                Arguments.of("field", field, ".child"),
                Arguments.of("index", index, "[2]"),
                Arguments.of("nested field/index", nested, ".lines[2].sku")
        );
    }

    private static Stream<Arguments> composedSinkPathCases() {
        List<Arguments> cases = new ArrayList<>();
        Function<Validator.ErrorSink, Validator.ErrorSink> fieldIndexField = errors -> errors.field("lines")
                .atIndex(2)
                .field("sku");
        Function<Validator.ErrorSink, Validator.ErrorSink> indexFieldIndex = errors -> errors.atIndex(1)
                .field("codes")
                .atIndex(0);
        Function<Validator.ErrorSink, Validator.ErrorSink> fields = errors -> errors.field("address").field("street");
        Function<Validator.ErrorSink, Validator.ErrorSink> indexes = errors -> errors.atIndex(1).atIndex(0);
        for (boolean fieldScope : new boolean[] { false, true }) {
            String scope = fieldScope ? "extracted node" : "root";
            cases.add(Arguments.of("field/index/field at " + scope, fieldIndexField, ".lines[2].sku", fieldScope));
            cases.add(Arguments.of("index/field/index at " + scope, indexFieldIndex, "[1].codes[0]", fieldScope));
            cases.add(Arguments.of("fields at " + scope, fields, ".address.street", fieldScope));
            cases.add(Arguments.of("indexes at " + scope, indexes, "[1][0]", fieldScope));
        }
        return cases.stream();
    }

    private static Stream<Arguments> derivedSinkReportingCases() {
        List<Arguments> cases = new ArrayList<>();
        Function<Validator.ErrorSink, Validator.ErrorSink> field = errors -> errors.field("child");
        Function<Validator.ErrorSink, Validator.ErrorSink> index = errors -> errors.atIndex(2);
        List<Function<Validator.ErrorSink, Validator.ErrorSink>> targets = Arrays.asList(field, index);
        String[] suffixes = { ".child", "[2]" };
        Consumer<Validator.ErrorSink> generic = errors -> errors.add();
        Consumer<Validator.ErrorSink> coded = errors -> errors.add("code.only");
        Consumer<Validator.ErrorSink> messaged = errors -> errors.add("code.message", "Explicit message");
        Map<String, Object> attributes = Collections.singletonMap("min", 18);
        Consumer<Validator.ErrorSink> rich = errors -> errors.add("code.rich", "Rich message", attributes);
        String defaultMessage = defaultCustomMessage();
        for (int i = 0; i < targets.size(); i++) {
            Function<Validator.ErrorSink, Validator.ErrorSink> select = targets.get(i);
            String suffix = suffixes[i];
            cases.add(
                    Arguments.of(
                            suffix + " add()",
                            select,
                            suffix,
                            generic,
                            "rule.unsatisfied",
                            defaultMessage,
                            Collections.emptyMap()
                    )
            );
            cases.add(
                    Arguments.of(
                            suffix + " add(code)",
                            select,
                            suffix,
                            coded,
                            "code.only",
                            defaultMessage,
                            Collections.emptyMap()
                    )
            );
            cases.add(
                    Arguments.of(
                            suffix + " add(code, message)",
                            select,
                            suffix,
                            messaged,
                            "code.message",
                            "Explicit message",
                            Collections.emptyMap()
                    )
            );
            cases.add(
                    Arguments.of(
                            suffix + " add(code, message, attributes)",
                            select,
                            suffix,
                            rich,
                            "code.rich",
                            "Rich message",
                            attributes
                    )
            );
        }
        return cases.stream();
    }

    private static void assertExpiredSinkOperations(Validator.ErrorSink sink) {
        assertNotNull(sink);
        assertAll(
                () -> assertThrows(IllegalStateException.class, sink::add),
                () -> assertThrows(IllegalStateException.class, () -> sink.add("late")),
                () -> assertThrows(IllegalStateException.class, () -> sink.add("late", "Late report")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> sink.add("late", "Late report", Collections.singletonMap("min", 18))
                ),
                () -> assertThrows(IllegalStateException.class, () -> sink.add((String) null)),
                () -> assertThrows(IllegalStateException.class, () -> sink.add("late", null)),
                () -> assertThrows(IllegalStateException.class, () -> sink.add("late", "Late report", null)),
                () -> assertThrows(IllegalStateException.class, () -> sink.field("late")),
                () -> assertThrows(IllegalStateException.class, () -> sink.atIndex(0)),
                () -> assertThrows(IllegalStateException.class, () -> sink.field(null)),
                () -> assertThrows(IllegalStateException.class, () -> sink.atIndex(-1))
        );
    }

    private static Stream<Arguments> metadataAddOverloads() {
        Consumer<Validator.ErrorSink> coded = errors -> errors.add("code.only");
        Consumer<Validator.ErrorSink> messaged = errors -> errors.add("code.message", "Explicit message");
        Map<String, Object> attributes = Collections.singletonMap("min", 18);
        Consumer<Validator.ErrorSink> rich = errors -> errors.add("code.attributes", "Rich message", attributes);

        return Stream.of(
                Arguments.of("add(code)", coded, "code.only", defaultCustomMessage(), Collections.emptyMap()),
                Arguments
                        .of("add(code, message)", messaged, "code.message", "Explicit message", Collections.emptyMap()),
                Arguments.of("add(code, message, attributes)", rich, "code.attributes", "Rich message", attributes)
        );
    }

    private static Stream<Arguments> invalidAddMetadata() {
        List<Arguments> cases = new ArrayList<>();
        String[] invalidCodes = { null, "", " ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" };
        for (int codeIndex = 0; codeIndex < invalidCodes.length; codeIndex++) {
            String code = invalidCodes[codeIndex];
            List<Consumer<Validator.ErrorSink>> reports = Arrays.asList(
                    errors -> errors.add(code),
                    errors -> errors.add(code, "Explicit message"),
                    errors -> errors.add(code, "Rich message", Collections.singletonMap("min", 18))
            );
            for (int overload = 0; overload < reports.size(); overload++) {
                cases.add(
                        Arguments
                                .of("overload " + (overload + 1) + ", invalid code " + codeIndex, reports.get(overload))
                );
            }
        }
        Consumer<Validator.ErrorSink> nullMessage = errors -> errors.add("custom.code", null);
        Consumer<Validator.ErrorSink> richNullMessage = errors -> errors
                .add("custom.code", null, Collections.singletonMap("min", 18));
        Consumer<Validator.ErrorSink> nullAttributes = errors -> errors.add("custom.code", "Custom message", null);
        cases.add(Arguments.of("add(code, null)", nullMessage));
        cases.add(Arguments.of("add(code, null, attributes)", richNullMessage));
        cases.add(Arguments.of("add(code, message, null)", nullAttributes));
        return cases.stream();
    }

    private static Stream<Arguments> nullAddMetadata() {
        Consumer<Validator.ErrorSink> nullCode = errors -> errors.add(null);
        Consumer<Validator.ErrorSink> messagedNullCode = errors -> errors.add(null, "Custom message");
        Consumer<Validator.ErrorSink> richNullCode = errors -> errors
                .add(null, "Custom message", Collections.singletonMap("min", 18));
        Consumer<Validator.ErrorSink> nullMessage = errors -> errors.add("custom.code", null);
        Consumer<Validator.ErrorSink> richNullMessage = errors -> errors
                .add("custom.code", null, Collections.singletonMap("min", 18));
        Consumer<Validator.ErrorSink> nullAttributes = errors -> errors.add("custom.code", "Custom message", null);
        return Stream.of(
                Arguments.of("add(null)", nullCode),
                Arguments.of("add(null, message)", messagedNullCode),
                Arguments.of("add(null, message, attributes)", richNullCode),
                Arguments.of("add(code, null)", nullMessage),
                Arguments.of("add(code, null, attributes)", richNullMessage),
                Arguments.of("add(code, message, null)", nullAttributes)
        );
    }

    private static Stream<Arguments> expiredAddOverloadsWithInvalidMetadata() {
        Consumer<Validator.ErrorSink> coded = errors -> errors.add(null);
        Consumer<Validator.ErrorSink> messaged = errors -> errors.add("custom.code", null);
        Consumer<Validator.ErrorSink> rich = errors -> errors.add("custom.code", "Custom message", null);
        return Stream.of(
                Arguments.of("add(null)", coded),
                Arguments.of("add(code, null)", messaged),
                Arguments.of("add(code, message, null)", rich)
        );
    }

    private static Stream<Arguments> addOverloadsWithNonblankCodes() {
        List<Arguments> cases = new ArrayList<>();
        String[] codes = { " custom.code ", "\u2003custom.code\u3000", "custom code", "#.elsewhere[2]" };
        Map<String, Object> attributes = Collections.singletonMap("min", 18);
        String defaultMessage = defaultCustomMessage();
        for (String code : codes) {
            Consumer<Validator.ErrorSink> coded = errors -> errors.add(code);
            Consumer<Validator.ErrorSink> messaged = errors -> errors.add(code, "Explicit message");
            Consumer<Validator.ErrorSink> rich = errors -> errors.add(code, "Rich message", attributes);
            cases.add(Arguments.of("add(code): " + code, coded, code, defaultMessage, Collections.emptyMap()));
            cases.add(
                    Arguments.of(
                            "add(code, message): " + code,
                            messaged,
                            code,
                            "Explicit message",
                            Collections.emptyMap()
                    )
            );
            cases.add(Arguments.of("add(code, message, attributes): " + code, rich, code, "Rich message", attributes));
        }
        return cases.stream();
    }

    private static Stream<Arguments> addOverloadsWithBlankMessages() {
        List<Arguments> cases = new ArrayList<>();
        String[] messages = { "", "   ", "\t\r\n", "\u2003", "\u3000", " \t\u2003\u3000\r\n" };
        Map<String, Object> attributes = Collections.singletonMap("min", 18);
        for (int messageIndex = 0; messageIndex < messages.length; messageIndex++) {
            String message = messages[messageIndex];
            Consumer<Validator.ErrorSink> messaged = errors -> errors.add("message.override", message);
            Consumer<Validator.ErrorSink> rich = errors -> errors.add("message.override", message, attributes);
            cases.add(
                    Arguments.of("add(code, message), blank " + messageIndex, messaged, message, Collections.emptyMap())
            );
            cases.add(Arguments.of("add(code, message, attributes), blank " + messageIndex, rich, message, attributes));
        }
        return cases.stream();
    }

    private static String defaultCustomMessage() {
        ValidationError error = Validator.builder(String.class)
                .custom((value, errors) -> errors.add())
                .build()
                .validate(null)
                .get(0);
        assertNonblankMessage(error);
        return error.getMessage();
    }

    private static void assertAddedMetadata(
            ValidationError error,
            String path,
            String code,
            String message,
            Map<String, ?> attributes
    ) {
        assertAll(
                () -> assertEquals(path, error.getPath()),
                () -> assertEquals(code, error.getCode()),
                () -> assertEquals(message, error.getMessage()),
                () -> assertEquals(attributes, error.getAttributes())
        );
        assertImmutableAttributes(error);
    }

    private static void assertDefaultCustomError(ValidationError error, String path) {
        assertEquals(path, error.getPath());
        assertEquals("rule.unsatisfied", error.getCode());
        assertNonblankMessage(error);
        assertEmptyImmutableAttributes(error);
    }

    private static void assertDistinctCustomSinks(List<Validator.ErrorSink> sinks) {
        Set<Validator.ErrorSink> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Validator.ErrorSink sink : sinks) {
            assertNotNull(sink);
            assertTrue(distinct.add(sink), "Each custom callback invocation must receive a fresh sink");
        }
    }

    private static Stream<Arguments> attributeFreeRuleConfigurations() {
        Consumer<Validator.Builder<String>> defaultRule = rules -> rules.rule(value -> false);
        Consumer<Validator.Builder<String>> codedRule = rules -> rules.rule("text.invalid", value -> false);
        Consumer<Validator.Builder<String>> messagedRule = rules -> rules
                .rule("text.invalid", "Text is invalid", value -> false);
        Consumer<Validator.Builder<String>> defaultNotNull = rules -> rules.notNull();
        Consumer<Validator.Builder<String>> codedNotNull = rules -> rules.notNull("text.required");
        Consumer<Validator.Builder<String>> messagedNotNull = rules -> rules
                .notNull("text.required", "Text is required");

        return Stream.of(
                Arguments.of("rule(predicate)", defaultRule, "rule.unsatisfied"),
                Arguments.of("rule(code, predicate)", codedRule, "text.invalid"),
                Arguments.of("rule(code, message, predicate)", messagedRule, "text.invalid"),
                Arguments.of("notNull()", defaultNotNull, "notNull"),
                Arguments.of("notNull(code)", codedNotNull, "text.required"),
                Arguments.of("notNull(code, message)", messagedNotNull, "text.required")
        );
    }

    private static void assertEmptyImmutableAttributes(ValidationError error) {
        assertImmutableAttributes(error);
        Map<String, Object> attributes = error.getAttributes();

        assertEquals(Collections.emptyMap(), attributes);
        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.computeIfAbsent("test.attribute", key -> "unexpected")
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.merge("test.attribute", "unexpected", (oldValue, newValue) -> newValue)
                )
        );
        assertEquals(Collections.emptyMap(), error.getAttributes());
    }

    private static void assertImmutableAttributes(ValidationError error) {
        Map<String, Object> attributes = error.getAttributes();
        assertNotNull(attributes);
        Map<String, Object> snapshot = new HashMap<>(attributes);
        Object unexpectedValue = new Object();

        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.put("test.attribute", unexpectedValue)
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> attributes.putAll(Collections.singletonMap("test.attribute", unexpectedValue))
                )
        );
        assertEquals(snapshot, attributes);
        assertEquals(snapshot, error.getAttributes());
    }

    private static <T> void assertConcurrentResults(
            Validator<T> validator,
            List<T> inputs,
            String[][] expectedErrors
    ) throws Exception {
        assertConcurrentResults(validator, inputs, expectedErrors, (input, errors) -> {
        });
    }

    private static <T> void assertConcurrentResults(
            Validator<T> validator,
            List<T> inputs,
            String[][] expectedErrors,
            BiConsumer<T, List<ValidationError>> additionalAssertions
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(inputs.size());

        try {
            List<Future<List<List<ValidationError>>>> results = new ArrayList<>();
            for (T input : inputs) {
                results.add(executor.submit(() -> {
                    List<List<ValidationError>> snapshots = new ArrayList<>();
                    for (int iteration = 0; iteration < CONCURRENT_VALIDATIONS_PER_INPUT; iteration++) {
                        snapshots.add(validator.validate(input));
                    }
                    return snapshots;
                }));
            }

            ValidationError extraError = new ValidationError("#", "unexpected", "Unexpected error");
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                List<List<ValidationError>> snapshots = results.get(inputIndex).get(15, TimeUnit.SECONDS);
                assertEquals(CONCURRENT_VALIDATIONS_PER_INPUT, snapshots.size());
                for (List<ValidationError> errors : snapshots) {
                    assertErrorsAt(errors, expectedErrors[inputIndex]);
                    assertThrows(UnsupportedOperationException.class, () -> errors.add(extraError));
                    errors.forEach(ValidatorTest::assertImmutableAttributes);
                    additionalAssertions.accept(inputs.get(inputIndex), errors);
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Validation workers did not stop");
        }
    }

    private static List<ValidationError> validateAtRootOrNameField(
            Validator<String> validator,
            boolean fieldScope,
            String value
    ) {
        if (!fieldScope) {
            return validator.validate(value);
        }
        Validator<Person> parent = Validator.builder(Person.class)
                .field("name", Person::getName, name -> name.include(validator))
                .build();
        return parent.validate(new Person(value, null, 18, Collections.emptyList()));
    }

    private static void awaitConcurrentValidation(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent validation was interrupted", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new AssertionError("Concurrent validations did not reach the barrier", e);
        }
    }

    private static ValidationError assertSingleErrorAt(List<ValidationError> errors, String path, String code) {
        assertEquals(1, errors.size());
        ValidationError error = errors.get(0);
        assertAll(
                () -> assertEquals(path, error.getPath()),
                () -> assertEquals(code, error.getCode())
        );
        return error;
    }

    private static void assertErrorsAt(List<ValidationError> errors, String... expected) {
        List<String> actual = new ArrayList<>();
        for (ValidationError error : errors) {
            actual.add(error.getPath() + " [" + error.getCode() + "]");
        }
        List<String> expectedErrors = new ArrayList<>(Arrays.asList(expected));
        Collections.sort(actual);
        Collections.sort(expectedErrors);
        assertEquals(expectedErrors, actual);
    }

    private static void assertSingleError(List<ValidationError> errors, String code, String message) {
        assertSingleErrorWithCode(errors, code);
        assertEquals(message, errors.get(0).getMessage());
    }

    private static void assertSingleErrorWithCode(List<ValidationError> errors, String code) {
        assertEquals(1, errors.size());
        ValidationError error = errors.get(0);
        assertAll(
                () -> assertEquals("#", error.getPath()),
                () -> assertEquals(code, error.getCode())
        );
    }

    private static void assertNonblankMessage(ValidationError error) {
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().trim().isEmpty());
    }

    private static void assertExceptionDetails(
            ValidationError error,
            Class<? extends Throwable> exceptionType,
            String exceptionMessage
    ) {
        assertTrue(
                error.getMessage().startsWith(exceptionType.getName()),
                "Execution failure must start with the binary exception class name: " + exceptionType.getName()
        );
        if (exceptionMessage != null) {
            assertTrue(error.getMessage().contains(exceptionMessage));
        }
    }

    private static final class ThrowingMessageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new IllegalStateException("Exception message is unavailable");
        }
    }

    static class Person {
        String name;

        private final Address address;

        private final int age;

        private final List<String> aliases;

        private Person() {
            this(null, null, 0, Collections.emptyList());
        }

        private Person(Address address) {
            this("Ada", address, 18, Collections.singletonList("Ada"));
        }

        private Person(String name, Address address, int age, List<String> aliases) {
            this.name = name;
            this.address = address;
            this.age = age;
            this.aliases = aliases;
        }

        private String getName() {
            return name;
        }

        private Address getAddress() {
            return address;
        }

        private int getAge() {
            return age;
        }

        private List<String> getAliases() {
            return aliases;
        }
    }

    private static final class Address {
        private final String streetName;

        private Address(String streetName) {
            this.streetName = streetName;
        }

        private String getStreetName() {
            return streetName;
        }
    }
}
