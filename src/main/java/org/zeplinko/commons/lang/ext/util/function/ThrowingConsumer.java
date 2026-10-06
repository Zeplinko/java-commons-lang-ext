package org.zeplinko.commons.lang.ext.util.function;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@link java.util.function.Consumer}-like functional interface whose
 * {@link #accept(Object)} method is allowed to throw a checked
 * {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code Consumer} would normally
 * be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of the input to the operation
 */
@FunctionalInterface
public interface ThrowingConsumer<T> {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     * @throws Exception if the operation fails
     */
    void accept(T t) throws Exception;

    /**
     * Returns a composed consumer that performs, in sequence, this operation
     * followed by the {@code after} operation. If this operation throws, the
     * {@code after} operation is not performed.
     *
     * @param after the operation to perform after this operation
     * @return a composed consumer
     */
    default ThrowingConsumer<T> andThen(ThrowingConsumer<T> after) {
        Objects.requireNonNull(after, "after must not be null");
        return t -> {
            this.accept(t);
            after.accept(t);
        };
    }

    /**
     * Returns a standard {@link Consumer} that delegates to this consumer, wrapping
     * any checked exception in a {@link RuntimeException}.
     *
     * @return a {@link Consumer} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default Consumer<T> toUnchecked() {
        return t -> {
            try {
                this.accept(t);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
