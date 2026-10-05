package org.zeplinko.commons.lang.ext.util.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

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

    // P003: validate(T). Field traversal checks are in ValidatorFieldTest (P014).

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
        assertTrue(executionError.getMessage().contains("IllegalStateException"));
        assertTrue(executionError.getMessage().contains("broken predicate"));
        assertTrue(errors.stream().anyMatch(error -> "age.invalid".equals(error.getCode())));
    }

    @Test
    void givenNullRootWhenPredicateDereferencesItThenRecordsExecutionFailureAtRoot() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name.required", "Name is required", person -> person.name != null)
                .build()
                .validate(null);

        assertSingleErrorWithCode(errors, "validation.executionFailed");
        assertTrue(errors.get(0).getMessage().contains("NullPointerException"));
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
        assertTrue(errors.get(0).getMessage().contains("IllegalStateException"));
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
        assertTrue(errors.get(0).getMessage().contains("NullPointerException"));
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
        assertTrue(errors.get(0).getMessage().contains("IllegalStateException"));
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
        assertTrue(errors.get(0).getMessage().contains("IllegalStateException"));
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

    static class Person {
        String name;
    }
}
