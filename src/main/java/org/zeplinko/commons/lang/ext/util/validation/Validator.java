package org.zeplinko.commons.lang.ext.util.validation;

import java.util.*;
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
            try {
                boolean test = condition.getPredicate().test(instance);
                if (!test) {
                    validationErrors
                            .add(new ValidationError(condition.getPath(), condition.getCode(), condition.getMessage()));
                }
            } catch (Exception e) {
                validationErrors.add(
                        new ValidationError(
                                condition.getPath(), "validation.executionFailed",
                                e.getClass().getSimpleName() + ": " + e.getMessage()
                        )
                );
            }
        }

        return Collections.unmodifiableList(validationErrors);
    }

    public static class Builder<T> {
        public static final String DEFAULT_CODE_RULE_UNSATISFIED = "rule.unsatisfied";

        public static final String DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED = "Rule was not satisfied";

        public static final String DEFAULT_PATH_UNSPECIFIED = "unspecified";

        public static final String CODE_NOT_NULL = "notNull";

        private final List<ValidationCondition<T>> conditions = new ArrayList<>();

        public Validator<T> build() {
            return new Validator<>(new ArrayList<>(conditions));
        }

        public Builder<T> rule(Predicate<T> predicate) {
            return rule(
                    DEFAULT_PATH_UNSPECIFIED,
                    DEFAULT_CODE_RULE_UNSATISFIED,
                    DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED,
                    predicate
            );
        }

        public Builder<T> rule(String path, Predicate<T> predicate) {
            return rule(path, DEFAULT_CODE_RULE_UNSATISFIED, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED, predicate);
        }

        public Builder<T> rule(String path, String code, Predicate<T> predicate) {
            return rule(path, code, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED, predicate);
        }

        public Builder<T> rule(String path, String code, String message, Predicate<T> predicate) {
            ValidationCondition<T> validationCondition = new ValidationCondition<>(path, code, message, predicate);
            conditions.add(validationCondition);
            return this;
        }

        public Builder<T> notNull() {
            return notNull(CODE_NOT_NULL, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED);
        }

        public Builder<T> notNull(String code) {
            return notNull(code, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED);
        }

        public Builder<T> notNull(String code, String message) {
            // TODO: Revisit the root "unspecified" path when nested validator paths are
            // composed.
            return this.rule(DEFAULT_PATH_UNSPECIFIED, code, message, Objects::nonNull);
        }
    }

    public static class ValidationCondition<T> {
        private final String path;

        private final String code;

        private final String message;

        private final Predicate<T> predicate;

        private ValidationCondition(String path, String code, String message, Predicate<T> predicate) {
            path = convertToNullIfBlank(path);
            code = convertToNullIfBlank(code);
            Objects.requireNonNull(path);
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
            Objects.requireNonNull(predicate);
            this.path = path;
            this.code = code;
            this.message = message;
            this.predicate = predicate;
        }

        private String getPath() {
            return path;
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

        private static String convertToNullIfBlank(String input) {
            return Optional.ofNullable(input).filter(value -> !value.trim().isEmpty()).orElse(null);
        }
    }
}
