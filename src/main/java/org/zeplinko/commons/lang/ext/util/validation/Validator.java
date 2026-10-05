package org.zeplinko.commons.lang.ext.util.validation;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class Validator<T> {

    private final List<ValidationCondition<T>> conditions;

    private Validator(List<ValidationCondition<T>> conditions) {
        this.conditions = conditions;
    }

    public static <T> Validator.Builder<T> builder(Class<T> tClass) {
        Objects.requireNonNull(tClass);
        return new Builder<>();
    }

    public List<ValidationError> validate(T instance) {
        ArrayList<ValidationError> validationErrors = new ArrayList<>();

        for (ValidationCondition<T> condition : conditions) {
            List<ValidationError> nodeValidationErrors = validateSingleCondition("#", condition, instance);
            validationErrors.addAll(nodeValidationErrors);
        }

        return Collections.unmodifiableList(validationErrors);
    }

    private static <R, S> List<ValidationError> validateSingleCondition(
            String path,
            ValidationCondition<R> condition,
            R instance
    ) {
        ArrayList<ValidationError> validationErrors = new ArrayList<>();
        try {
            if (condition instanceof CurrentNodeValidationCondition) {
                CurrentNodeValidationCondition<R> nodeValidationCondition = (CurrentNodeValidationCondition<R>) condition;

                boolean test = nodeValidationCondition.getPredicate().test(instance);
                if (!test) {
                    validationErrors
                            .add(
                                    new ValidationError(
                                            path, nodeValidationCondition.getCode(),
                                            nodeValidationCondition.getMessage()
                                    )
                            );
                }
            } else if (condition instanceof FieldValidationCondition) {
                FieldValidationCondition<R, S> fieldValidationCondition = (FieldValidationCondition<R, S>) condition;
                path = path + "." + fieldValidationCondition.getName();
                if (instance == null) {
                    validationErrors.add(
                            new ValidationError(
                                    path, "validation.skipped",
                                    "Validation skipped as the parent instance is null"
                            )
                    );
                } else {
                    S fieldInstance = fieldValidationCondition.extractor.apply(instance);
                    List<ValidationCondition<S>> fieldConditions = fieldValidationCondition.getFieldConditions();
                    for (ValidationCondition<S> fieldCondition : fieldConditions) {
                        List<ValidationError> fieldValidationErrors = validateSingleCondition(
                                path,
                                fieldCondition,
                                fieldInstance
                        );
                        validationErrors.addAll(fieldValidationErrors);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported validation condition");
            }

        } catch (RuntimeException e) {
            validationErrors.add(
                    new ValidationError(
                            path, "validation.executionFailed",
                            e.getClass().getSimpleName() + ": " + e.getMessage()
                    )
            );
        }
        return validationErrors;
    }

    public static class Builder<T> {
        public static final String DEFAULT_CODE_RULE_UNSATISFIED = "rule.unsatisfied";

        public static final String DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED = "Rule was not satisfied";

        public static final String CODE_NOT_NULL = "notNull";

        private final List<ValidationCondition<T>> conditions = new ArrayList<>();

        public Validator<T> build() {
            return new Validator<>(new ArrayList<>(conditions));
        }

        public Builder<T> rule(Predicate<T> predicate) {
            return rule(
                    DEFAULT_CODE_RULE_UNSATISFIED,
                    DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED,
                    predicate
            );
        }

        public Builder<T> rule(String code, Predicate<T> predicate) {
            return rule(code, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED, predicate);
        }

        public Builder<T> rule(String code, String message, Predicate<T> predicate) {
            CurrentNodeValidationCondition<T> currentNodeValidationCondition = new CurrentNodeValidationCondition<>(
                    code, message, predicate
            );
            conditions.add(currentNodeValidationCondition);
            return this;
        }

        public Builder<T> notNull() {
            return notNull(CODE_NOT_NULL, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED);
        }

        public Builder<T> notNull(String code) {
            return notNull(code, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED);
        }

        public Builder<T> notNull(String code, String message) {
            return this.rule(code, message, Objects::nonNull);
        }

        public <R> Builder<T> field(String name, Function<T, R> extractor, Consumer<Builder<R>> ruleConsumer) {
            validateFieldName(name);
            Builder<R> fieldBuilder = new Builder<>();
            ruleConsumer.accept(fieldBuilder);
            List<ValidationCondition<R>> fieldConditions = new ArrayList<>(fieldBuilder.getConditions());
            FieldValidationCondition<T, R> validationCondition = new FieldValidationCondition<>(
                    name, extractor, ruleConsumer, fieldConditions
            );
            conditions.add(validationCondition);
            return this;
        }

        private void validateFieldName(String name) {
            String[] reservedCharacters = { "[", "]", "#", "." };
            for (String c : reservedCharacters) {
                if (name.contains(c)) {
                    throw new IllegalArgumentException("Field name cannot have reserved characters");
                }
            }
        }

        List<ValidationCondition<T>> getConditions() {
            return conditions;
        }
    }

    public interface ValidationCondition<T> {

        static String convertToNullIfBlank(String input) {
            return Optional.ofNullable(input).filter(value -> !value.trim().isEmpty()).orElse(null);
        }
    }

    public static class CurrentNodeValidationCondition<T> implements ValidationCondition<T> {

        private final String code;

        private final String message;

        private final Predicate<T> predicate;

        private CurrentNodeValidationCondition(String code, String message, Predicate<T> predicate) {
            code = ValidationCondition.convertToNullIfBlank(code);
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
            Objects.requireNonNull(predicate);
            this.code = code;
            this.message = message;
            this.predicate = predicate;
        }

        private String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        private Predicate<T> getPredicate() {
            return predicate;
        }

    }

    public static class FieldValidationCondition<T, R> implements ValidationCondition<T> {

        private final String name;

        private final Function<T, R> extractor;

        private final Consumer<Builder<R>> ruleConsumer;

        private final List<ValidationCondition<R>> fieldConditions;

        public FieldValidationCondition(
                String name,
                Function<T, R> extractor,
                Consumer<Builder<R>> ruleConsumer,
                List<ValidationCondition<R>> fieldConditions
        ) {
            this.fieldConditions = fieldConditions;
            name = ValidationCondition.convertToNullIfBlank(name);
            Objects.requireNonNull(name);
            Objects.requireNonNull(extractor);
            Objects.requireNonNull(ruleConsumer);
            Objects.requireNonNull(fieldConditions);

            this.name = name;
            this.extractor = extractor;
            this.ruleConsumer = ruleConsumer;
        }

        public String getName() {
            return name;
        }

        public Function<T, R> getExtractor() {
            return extractor;
        }

        public Consumer<Builder<R>> getRuleConsumer() {
            return ruleConsumer;
        }

        public List<ValidationCondition<R>> getFieldConditions() {
            return fieldConditions;
        }
    }
}
