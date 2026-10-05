package org.zeplinko.commons.lang.ext.util.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorTest {

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

    @Test
    void givenBuilderChangesAfterBuildWhenValidatingThenEarlierValidatorKeepsOriginalRules() {
        Validator.Builder<Person> builder = Validator.builder(Person.class);
        builder.rule("name", "name.required", "Name is required", person -> false);
        Validator<Person> first = builder.build();

        builder.rule("age", "age.required", "Age is required", person -> false);
        Validator<Person> second = builder.build();

        List<ValidationError> firstErrors = first.validate(new Person());
        List<ValidationError> secondErrors = second.validate(new Person());
        assertEquals(1, firstErrors.size());
        assertEquals("name.required", firstErrors.get(0).getCode());
        assertEquals(2, secondErrors.size());
        assertTrue(secondErrors.stream().anyMatch(error -> "name.required".equals(error.getCode())));
        assertTrue(secondErrors.stream().anyMatch(error -> "age.required".equals(error.getCode())));
    }

    @Test
    void givenPassingAndFailingRulesWhenValidatingThenCollectsEveryApplicableFailure() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "first", "First rule failed", person -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .rule("name", "passing", "Passing rule", person -> {
                    evaluations.incrementAndGet();
                    return true;
                })
                .rule("name", "last", "Last rule failed", person -> {
                    evaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(3, evaluations.get());
        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(error -> "first".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "last".equals(error.getCode())));
    }

    @Test
    void givenDifferentInputsWhenValidatingThenReturnsIndependentImmutableErrorLists() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.required", "Name is required", person -> person.name != null)
                .build();

        List<ValidationError> first = validator.validate(new Person());
        Person namedPerson = new Person();
        namedPerson.name = "Ada";
        List<ValidationError> second = validator.validate(namedPerson);

        assertNotSame(first, second);
        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.add(new ValidationError("age", "age.required", "Age is required"))
        );
    }

    @Test
    void givenNoRulesWhenValidatingNullRootThenReturnsImmutableEmptyErrorList() {
        Validator<Person> validator = Validator.builder(Person.class).build();

        List<ValidationError> errors = validator.validate(null);

        assertTrue(errors.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> errors.add(new ValidationError("person", "person.required", "Person is required"))
        );
    }

    @Test
    void givenNullRootWhenRulesAreNullAwareThenReportsOnlyUnsatisfiedRule() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person", "person.required", "Person is required", Objects::nonNull)
                .rule("person", "person.absent", "Person must be absent", Objects::isNull)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("person", errors.get(0).getPath());
        assertEquals("person.required", errors.get(0).getCode());
    }

    @Test
    void givenPredicateThrowsRuntimeExceptionWhenValidatingThenRecordsFailureAndContinuesIndependentRules() {
        AtomicInteger independentRuleEvaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.required", "Name is required", person -> {
                    throw new IllegalStateException("broken predicate");
                })
                .rule("age", "age.invalid", "Age is invalid", person -> {
                    independentRuleEvaluations.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, independentRuleEvaluations.get());
        assertEquals(2, errors.size());
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing execution failure"));
        assertEquals("name", executionError.getPath());
        assertTrue(executionError.getMessage().contains("IllegalStateException"));
        assertTrue(executionError.getMessage().contains("broken predicate"));
        assertTrue(errors.stream().anyMatch(error -> "age.invalid".equals(error.getCode())));
    }

    @Test
    void givenNullRootWhenPredicateDereferencesItThenRecordsExecutionFailureAtRulePath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.required", "Name is required", person -> person.name != null)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("name", errors.get(0).getPath());
        assertEquals("validation.executionFailed", errors.get(0).getCode());
        assertTrue(errors.get(0).getMessage().contains("NullPointerException"));
    }

    @Test
    void givenPredicateThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal failure");
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person", "person.invalid", "Person is invalid", person -> {
                    throw failure;
                })
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person())));
    }

    @Test
    void givenExplicitMetadataWhenRuleFailsThenReportsPathCodeAndMessage() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.notNull", "name must not be null", person -> person.name != null)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("name", errors.get(0).getPath());
        assertEquals("name.notNull", errors.get(0).getCode());
        assertEquals("name must not be null", errors.get(0).getMessage());
    }

    @Test
    void givenExplicitMetadataWhenRulePassesThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.notNull", "name must not be null", value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullOrBlankPathWhenAddingRuleThenRejectsConfiguration() {
        Predicate<Person> validPredicate = person -> true;

        assertConfigurationRejected(null, "code", "message", validPredicate);
        assertConfigurationRejected("", "code", "message", validPredicate);
        assertConfigurationRejected("   ", "code", "message", validPredicate);
    }

    @Test
    void givenNullOrBlankCodeWhenAddingRuleThenRejectsConfiguration() {
        Predicate<Person> validPredicate = person -> true;

        assertConfigurationRejected("path", null, "message", validPredicate);
        assertConfigurationRejected("path", "", "message", validPredicate);
        assertConfigurationRejected("path", "   ", "message", validPredicate);
    }

    @Test
    void givenNullMessageWhenAddingRuleThenRejectsConfiguration() {
        Predicate<Person> validPredicate = person -> true;

        assertConfigurationRejected("path", "code", null, validPredicate);
    }

    @Test
    void givenNullPredicateWhenAddingRuleThenRejectsConfiguration() {
        assertConfigurationRejected("path", "code", "message", null);
    }

    @Test
    void givenPathAndCodeWhenThreeArgumentRuleFailsThenReportsBothWithDefaultMessage() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule("name", "name.notNull", p -> p.name != null)
                .build()
                .validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("name", errors.get(0).getPath());
        assertEquals("name.notNull", errors.get(0).getCode());
        assertEquals("Rule was not satisfied", errors.get(0).getMessage());
    }

    @Test
    void givenPassingThreeArgumentRuleWhenValidatingThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.notNull", value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenThreeArgumentRuleReturnsFalseThenReportsOrdinaryFailure() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person", "person.required", Objects::nonNull)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("person", errors.get(0).getPath());
        assertEquals("person.required", errors.get(0).getCode());
        assertEquals("Rule was not satisfied", errors.get(0).getMessage());
    }

    @Test
    void givenThrowingThreeArgumentRuleWhenValidatingThenReportsExecutionFailureAtSuppliedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", "name.notNull", value -> {
                    throw new IllegalStateException("lookup failed");
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("name", errors.get(0).getPath());
        assertEquals("validation.executionFailed", errors.get(0).getCode());
        assertTrue(errors.get(0).getMessage().contains("IllegalStateException"));
        assertTrue(errors.get(0).getMessage().contains("lookup failed"));
    }

    @Test
    void givenNullOrBlankPathWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        Predicate<Person> predicate = person -> true;

        assertThreeArgumentRuleConfigurationRejected(null, "name.required", predicate);
        assertThreeArgumentRuleConfigurationRejected("", "name.required", predicate);
        assertThreeArgumentRuleConfigurationRejected("   ", "name.required", predicate);
    }

    @Test
    void givenNullOrBlankCodeWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        Predicate<Person> predicate = person -> true;

        assertThreeArgumentRuleConfigurationRejected("name", null, predicate);
        assertThreeArgumentRuleConfigurationRejected("name", "", predicate);
        assertThreeArgumentRuleConfigurationRejected("name", "   ", predicate);
    }

    @Test
    void givenNullPredicateWhenAddingThreeArgumentRuleThenRejectsConfiguration() {
        assertThreeArgumentRuleConfigurationRejected("name", "name.required", null);
    }

    @Test
    void givenExplicitPathWhenTwoArgumentRuleFailsThenReportsPathAndDefaultCodeAndMessage() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person", p -> p.name != null)
                .build();
        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("person", errors.get(0).getPath());
        assertEquals("rule.unsatisfied", errors.get(0).getCode());
        assertEquals("Rule was not satisfied", errors.get(0).getMessage());
    }

    @Test
    void givenPassingTwoArgumentRuleWhenValidatingThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenTwoArgumentRuleReturnsFalseThenReportsOrdinaryFailureAtPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person", Objects::nonNull)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("person", errors.get(0).getPath());
        assertEquals("rule.unsatisfied", errors.get(0).getCode());
    }

    @Test
    void givenThrowingTwoArgumentRuleWhenValidatingThenReportsExecutionFailureAtExplicitPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("name", value -> {
                    throw new IllegalStateException("lookup failed");
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("name", errors.get(0).getPath());
        assertEquals("validation.executionFailed", errors.get(0).getCode());
        assertTrue(errors.get(0).getMessage().contains("IllegalStateException"));
        assertTrue(errors.get(0).getMessage().contains("lookup failed"));
    }

    @Test
    void givenNullOrBlankPathWhenAddingTwoArgumentRuleThenRejectsConfiguration() {
        Predicate<Person> predicate = person -> true;

        assertTwoArgumentRuleConfigurationRejected(null, predicate);
        assertTwoArgumentRuleConfigurationRejected("", predicate);
        assertTwoArgumentRuleConfigurationRejected("   ", predicate);
    }

    @Test
    void givenNullPredicateWhenAddingTwoArgumentRuleThenRejectsConfiguration() {
        assertTwoArgumentRuleConfigurationRejected("name", null);
    }

    @Test
    void givenUnnamedRuleWhenPredicateFailsThenReportsDefaultPathCodeAndMessage() {
        List<ValidationError> errors = Validator.builder(Person.class)
                .rule(p -> p.name != null)
                .build()
                .validate(new Person());

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("rule.unsatisfied", errors.get(0).getCode());
        assertEquals("Rule was not satisfied", errors.get(0).getMessage());
    }

    @Test
    void givenUnnamedRuleWhenPredicatePassesThenReportsNoErrors() {
        Person person = new Person();
        person.name = "Ada";
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(value -> value.name != null)
                .build();

        assertTrue(validator.validate(person).isEmpty());
    }

    @Test
    void givenNullRootWhenUnnamedRuleReturnsFalseThenReportsOrdinaryFailureAtUnspecifiedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(Objects::nonNull)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("rule.unsatisfied", errors.get(0).getCode());
    }

    @Test
    void givenNullRootWhenUnnamedRuleDereferencesItThenReportsExecutionFailureAtUnspecifiedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(value -> value.name != null)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("validation.executionFailed", errors.get(0).getCode());
        assertTrue(errors.get(0).getMessage().contains("NullPointerException"));
    }

    @Test
    void givenTwoFailingUnnamedRulesWhenValidatingThenReportsBothFailuresWithoutDeduplication() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule(value -> false)
                .rule(value -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person());

        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(error -> "unspecified".equals(error.getPath())));
        assertTrue(errors.stream().allMatch(error -> "rule.unsatisfied".equals(error.getCode())));
    }

    @Test
    void givenNullPredicateWhenAddingUnnamedRuleThenRejectsConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class)
                        .rule(null)
                        .build()
        );
    }

    @Test
    void givenNullRootWhenRootNotNullHasOverridesThenReportsUnspecifiedPathAndCustomMetadata() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("person.required", errors.get(0).getCode());
        assertEquals("Person is required", errors.get(0).getMessage());
    }

    @Test
    void givenNonNullRootWhenRootNotNullHasOverridesThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullRootAndIndependentFailingRuleWhenValidatingThenCollectsBothErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required", "Person is required")
                .rule("name", "name.required", "Name is required", person -> person != null)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(error -> "person.required".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "name.required".equals(error.getCode())));
    }

    @Test
    void givenNullOrBlankCodeWhenAddingRootNotNullWithOverridesThenRejectsConfiguration() {
        assertRootNotNullConfigurationRejected(null, "Person is required");
        assertRootNotNullConfigurationRejected("", "Person is required");
        assertRootNotNullConfigurationRejected("   ", "Person is required");
    }

    @Test
    void givenNullMessageWhenAddingRootNotNullWithOverridesThenRejectsConfiguration() {
        assertRootNotNullConfigurationRejected("person.required", null);
    }

    @Test
    void givenNullRootWhenRootNotNullHasCustomCodeThenReportsCodeAtUnspecifiedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required")
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("person.required", errors.get(0).getCode());
        assertNotNull(errors.get(0).getMessage());
        assertFalse(errors.get(0).getMessage().trim().isEmpty());
    }

    @Test
    void givenNullRootWhenRootNotNullUsesDefaultsThenReportsNotNullCodeAtUnspecifiedPath() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(1, errors.size());
        assertEquals("unspecified", errors.get(0).getPath());
        assertEquals("notNull", errors.get(0).getCode());
        assertNotNull(errors.get(0).getMessage());
        assertFalse(errors.get(0).getMessage().trim().isEmpty());
    }

    @Test
    void givenNonNullRootWhenRootNotNullUsesDefaultsThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullRootAndAnotherFailingRuleWhenUsingDefaultRootNotNullThenCollectsBothErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .rule("person", "person.invalid", "Person is invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(null);

        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(error -> "notNull".equals(error.getCode())));
        assertTrue(errors.stream().anyMatch(error -> "person.invalid".equals(error.getCode())));
    }

    @Test
    void givenNonNullRootWhenRootNotNullHasCustomCodeThenReportsNoErrors() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull("person.required")
                .build();

        assertTrue(validator.validate(new Person()).isEmpty());
    }

    @Test
    void givenNullOrBlankCodeWhenAddingRootNotNullWithCodeThenRejectsConfiguration() {
        assertRootNotNullWithCodeConfigurationRejected(null);
        assertRootNotNullWithCodeConfigurationRejected("");
        assertRootNotNullWithCodeConfigurationRejected("   ");
    }

    private static void assertConfigurationRejected(
            String path,
            String code,
            String message,
            Predicate<Person> predicate
    ) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule(path, code, message, predicate).build()
        );
    }

    private static void assertThreeArgumentRuleConfigurationRejected(
            String path,
            String code,
            Predicate<Person> predicate
    ) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule(path, code, predicate).build()
        );
    }

    private static void assertTwoArgumentRuleConfigurationRejected(
            String path,
            Predicate<Person> predicate
    ) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).rule(path, predicate).build()
        );
    }

    private static void assertRootNotNullConfigurationRejected(String code, String message) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).notNull(code, message).build()
        );
    }

    private static void assertRootNotNullWithCodeConfigurationRejected(String code) {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).notNull(code).build()
        );
    }

    static class Person {
        String name;
    }
}
