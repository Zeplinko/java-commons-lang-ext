package org.zeplinko.commons.lang.ext.util.validation;

import org.zeplinko.commons.lang.ext.core.MutableContainer;
import org.zeplinko.commons.lang.ext.core.Try;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class Validator<T> {

    public static final String DEFAULT_MESSAGE_VALIDATION_EXECUTION_FAILED = "validation.executionFailed";

    private static final String DEFAULT_CODE_RULE_UNSATISFIED = "rule.unsatisfied";

    private static final String DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED = "Rule was not satisfied";

    private static final String CODE_NOT_NULL = "notNull";

    private final List<ValidationCondition<T>> conditions;

    private Validator(List<ValidationCondition<T>> conditions) {
        this.conditions = conditions;
    }

    public static <T> Validator.Builder<T> builder(Class<T> tClass) {
        Objects.requireNonNull(tClass);
        return new Builder<>();
    }

    private static String convertToNullIfBlank(String input) {
        return Optional.ofNullable(input).filter(value -> {
            for (int i = 0; i < value.length(); ++i) {
                if (!Character.isWhitespace(value.charAt(i))) {
                    return true;
                }
            }
            return false;
        }).orElse(null);
    }

    static void requireNonBlank(String input) {
        input = convertToNullIfBlank(input);
        Objects.requireNonNull(input);
    }

    private static void validateFieldName(String name) {
        String[] reservedCharacters = { "[", "]", "#", "." };
        for (String c : reservedCharacters) {
            if (name.contains(c)) {
                throw new IllegalArgumentException("Field name cannot have reserved characters");
            }
        }
    }

    public List<ValidationError> validate(T instance) {
        return validate(instance, "#");
    }

    private List<ValidationError> validate(T instance, String path) {
        ArrayList<ValidationError> validationErrors = new ArrayList<>();

        for (ValidationCondition<T> condition : conditions) {
            List<ValidationError> nodeValidationErrors = condition.validate(instance, path);
            validationErrors.addAll(nodeValidationErrors);
        }

        return Collections.unmodifiableList(validationErrors);
    }

    @SuppressWarnings("unused")
    private interface ValidationConditionBuilder<T> {
        ValidationCondition<T> build();
    }

    @SuppressWarnings("unused")
    private interface ValidationCondition<T> {
        List<ValidationError> validate(T instance, String path);
    }

    public static final class Builder<T> {

        private final List<ValidationConditionBuilder<T>> conditionBuilders = new ArrayList<>();

        private Builder() {
        }

        public Validator<T> build() {
            List<ValidationCondition<T>> conditions = conditionBuilders.stream()
                    .map(ValidationConditionBuilder::build)
                    .collect(Collectors.toList());
            return new Validator<>(conditions);
        }

        public Builder<T> rule(Predicate<? super T> predicate) {
            return rule(
                    DEFAULT_CODE_RULE_UNSATISFIED,
                    DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED,
                    predicate
            );
        }

        public Builder<T> rule(String code, Predicate<? super T> predicate) {
            return rule(code, DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED, predicate);
        }

        public Builder<T> rule(String code, String message, Predicate<? super T> predicate) {
            CurrentNodeValidationCondition<T> currentNodeValidationCondition = new CurrentNodeValidationCondition<>(
                    code, message, predicate
            );
            conditionBuilders.add(currentNodeValidationCondition);
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

        public <R> Builder<T> field(
                String name,
                Function<? super T, ? extends R> extractor,
                Consumer<? super Builder<R>> ruleConsumer
        ) {
            validateFieldName(name);
            FieldValidationConditionBuilder<T, R> validationCondition = new FieldValidationConditionBuilder<>(
                    name, extractor, ruleConsumer
            );
            conditionBuilders.add(validationCondition);
            return this;
        }

        public Builder<T> include(Validator<? super T> validator) {
            conditionBuilders.add(new IncludedValidatorValidationCondition<>(validator));
            return this;
        }

        public Builder<T> onlyIf(Predicate<? super T> predicate, Consumer<? super Builder<T>> ruleConsumer) {
            ConditionalValidationConditionBuilder<T> conditionBuilder = new ConditionalValidationConditionBuilder<>(
                    predicate, ruleConsumer
            );
            conditionBuilders.add(conditionBuilder);
            return this;
        }

        public Builder<T> custom(BiConsumer<? super T, ? super ErrorSink> callback) {
            CustomValidationCondition<T> validationCondition = new CustomValidationCondition<>(callback);
            conditionBuilders.add(validationCondition);
            return this;
        }
    }

    private static final class CurrentNodeValidationCondition<T>
            implements ValidationConditionBuilder<T>, ValidationCondition<T> {

        private final String code;

        private final String message;

        private final Predicate<? super T> predicate;

        private CurrentNodeValidationCondition(String code, String message, Predicate<? super T> predicate) {
            requireNonBlank(code);
            Objects.requireNonNull(message);
            Objects.requireNonNull(predicate);

            this.code = code;
            this.message = message;
            this.predicate = predicate;
        }

        @Override
        public List<ValidationError> validate(T instance, String path) {
            ArrayList<ValidationError> errors = new ArrayList<>();
            try {
                boolean test = this.predicate.test(instance);
                if (!test) {
                    errors.add(new ValidationError(path, this.code, this.message));
                }
            } catch (RuntimeException e) {
                String message = Try.to(e::getMessage).orElse(null);
                errors.add(
                        new ValidationError(
                                path, DEFAULT_MESSAGE_VALIDATION_EXECUTION_FAILED,
                                e.getClass().getName() + ": " + message
                        )
                );
            }
            return Collections.unmodifiableList(errors);
        }

        @Override
        public ValidationCondition<T> build() {
            return this;
        }
    }

    private static final class FieldValidationConditionBuilder<T, R> implements ValidationConditionBuilder<T> {

        private final String name;

        private final Function<? super T, ? extends R> extractor;

        private final Builder<R> builder;

        private FieldValidationConditionBuilder(
                String name,
                Function<? super T, ? extends R> extractor,
                Consumer<? super Builder<R>> ruleConsumer
        ) {
            requireNonBlank(name);
            Objects.requireNonNull(extractor);
            Objects.requireNonNull(ruleConsumer);

            this.name = name;
            this.extractor = extractor;
            Builder<R> builder = new Builder<>();
            ruleConsumer.accept(builder);
            this.builder = builder;
        }

        @Override
        public ValidationCondition<T> build() {
            Validator<R> childValidator = builder.build();
            return new FieldValidationCondition<>(this.name, this.extractor, childValidator);
        }

    }

    private static final class FieldValidationCondition<T, R> implements ValidationCondition<T> {

        private final String name;

        private final Function<? super T, ? extends R> extractor;

        private final Validator<R> childValidator;

        private FieldValidationCondition(
                String name,
                Function<? super T, ? extends R> extractor,
                Validator<R> childValidator
        ) {
            this.name = name;
            this.extractor = extractor;
            this.childValidator = childValidator;
        }

        @Override
        public List<ValidationError> validate(T instance, String path) {
            path = path + "." + this.name;
            try {
                if (instance == null) {
                    ArrayList<ValidationError> errors = new ArrayList<>();
                    errors.add(
                            new ValidationError(
                                    path, "validation.skipped",
                                    "Validation skipped as the parent instance is null"
                            )
                    );
                    return Collections.unmodifiableList(errors);
                }

                R fieldInstance = this.extractor.apply(instance);
                return this.childValidator.validate(fieldInstance, path);
            } catch (RuntimeException e) {
                String message = Try.to(e::getMessage).orElse(null);
                ArrayList<ValidationError> errors = new ArrayList<>();
                errors.add(
                        new ValidationError(
                                path, DEFAULT_MESSAGE_VALIDATION_EXECUTION_FAILED,
                                e.getClass().getName() + ": " + message
                        )
                );
                return Collections.unmodifiableList(errors);
            }
        }
    }

    private static final class IncludedValidatorValidationCondition<T>
            implements ValidationConditionBuilder<T>, ValidationCondition<T> {
        private final Validator<? super T> validator;

        private IncludedValidatorValidationCondition(Validator<? super T> validator) {
            Objects.requireNonNull(validator);
            this.validator = validator;
        }

        @Override
        public List<ValidationError> validate(T instance, String path) {
            return validator.validate(instance, path);
        }

        @Override
        public ValidationCondition<T> build() {
            return this;
        }
    }

    private static final class ConditionalValidationConditionBuilder<T> implements ValidationConditionBuilder<T> {

        private final Predicate<? super T> predicate;

        private final Builder<T> builder;

        private ConditionalValidationConditionBuilder(
                Predicate<? super T> predicate,
                Consumer<? super Builder<T>> ruleConsumer
        ) {
            Objects.requireNonNull(predicate);
            Objects.requireNonNull(ruleConsumer);

            this.predicate = predicate;
            Builder<T> builder = new Builder<>();
            ruleConsumer.accept(builder);
            this.builder = builder;
        }

        @Override
        public ValidationCondition<T> build() {
            Validator<T> validator = builder.build();
            return new ConditionalValidationCondition<>(this.predicate, validator);
        }
    }

    private static final class ConditionalValidationCondition<T> implements ValidationCondition<T> {

        private final Predicate<? super T> predicate;

        private final Validator<T> validator;

        private ConditionalValidationCondition(Predicate<? super T> predicate, Validator<T> validator) {
            this.predicate = predicate;
            this.validator = validator;
        }

        @Override
        public List<ValidationError> validate(T instance, String path) {
            try {
                if (this.predicate.test(instance)) {
                    return validator.validate(instance, path);
                }
            } catch (RuntimeException e) {
                String message = Try.to(e::getMessage).orElse(null);
                ArrayList<ValidationError> errors = new ArrayList<>();
                errors.add(
                        new ValidationError(
                                path, DEFAULT_MESSAGE_VALIDATION_EXECUTION_FAILED,
                                e.getClass().getName() + ": " + message
                        )
                );
                return Collections.unmodifiableList(errors);
            }
            return Collections.emptyList();
        }
    }

    private static final class CustomValidationCondition<T>
            implements ValidationConditionBuilder<T>, ValidationCondition<T> {

        private final BiConsumer<? super T, ? super ErrorSink> callback;

        private CustomValidationCondition(BiConsumer<? super T, ? super ErrorSink> callback) {
            Objects.requireNonNull(callback);
            this.callback = callback;
        }

        @Override
        public ValidationCondition<T> build() {
            return this;
        }

        @Override
        public List<ValidationError> validate(T instance, String path) {
            ArrayList<ValidationError> validationErrors = new ArrayList<>();
            ErrorSink errorSink = new ErrorSink(path, validationErrors::add);
            try {
                try {
                    callback.accept(instance, errorSink);
                } finally {
                    errorSink.markClosed();
                }
            } catch (RuntimeException e) {
                String message = Try.to(e::getMessage).orElse(null);
                validationErrors.add(
                        new ValidationError(
                                path, DEFAULT_MESSAGE_VALIDATION_EXECUTION_FAILED,
                                e.getClass().getName() + ": " + message
                        )
                );

            }
            return Collections.unmodifiableList(validationErrors);
        }
    }

    public static class ErrorSink {
        private final String path;

        private final Consumer<ValidationError> validationErrorConsumer;

        private final MutableContainer<Boolean> isClosed;

        private ErrorSink(String path, Consumer<ValidationError> validationErrorConsumer) {
            this(path, validationErrorConsumer, MutableContainer.of(false));
        }

        private ErrorSink(
                String path,
                Consumer<ValidationError> validationErrorConsumer,
                MutableContainer<Boolean> isClosed
        ) {
            requireNonBlank(path);
            Objects.requireNonNull(validationErrorConsumer);
            Objects.requireNonNull(isClosed);

            this.path = path;
            this.validationErrorConsumer = validationErrorConsumer;
            this.isClosed = isClosed;
        }

        public void add() {
            add(
                    Validator.DEFAULT_CODE_RULE_UNSATISFIED,
                    Validator.DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED,
                    new HashMap<>()
            );
        }

        public void add(String code) {
            add(
                    code,
                    Validator.DEFAULT_MESSAGE_RULE_WAS_NOT_SATISFIED,
                    new HashMap<>()
            );
        }

        public void add(String code, String message) {
            add(
                    code,
                    message,
                    new HashMap<>()
            );
        }

        public ErrorSink field(String name) {
            requireOpen();
            requireNonBlank(name);
            validateFieldName(name);
            return new ErrorSink(path + "." + name, validationErrorConsumer, isClosed);
        }

        public ErrorSink atIndex(int atIndex) {
            requireOpen();
            if (atIndex < 0) {
                throw new IllegalArgumentException("atIndex must be greater than or equal to zero");
            }
            return new ErrorSink(path + "[" + atIndex + "]", validationErrorConsumer, isClosed);
        }

        public void add(String code, String message, Map<String, ?> attributes) {
            requireOpen();
            validationErrorConsumer.accept(new ValidationError(path, code, message, attributes));
        }

        private void requireOpen() {
            if (this.isClosed.getValue()) {
                throw new IllegalStateException("ErrorSink is closed");
            }
        }

        private void markClosed() {
            this.isClosed.setValue(true);
        }
    }
}
