package org.zeplinko.commons.lang.ext.util.function;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A {@link java.util.function.BiConsumer}-like functional interface whose
 * {@link #accept(Object, Object)} method is allowed to throw a checked
 * {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code BiConsumer} would
 * normally be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of the first argument to the operation
 * @param <U> the type of the second argument to the operation
 */
@FunctionalInterface
public interface ThrowingBiConsumer<T, U> {
    /**
     * Performs this operation on the given arguments.
     *
     * @param t the first input argument
     * @param u the second input argument
     * @throws Exception if the operation fails
     */
    void accept(T t, U u) throws Exception;

    /**
     * Returns a composed consumer that performs, in sequence, this operation
     * followed by the {@code after} operation. If this operation throws, the
     * {@code after} operation is not performed.
     *
     * @param after the operation to perform after this operation
     * @return a composed consumer
     */
    default ThrowingBiConsumer<T, U> andThen(ThrowingBiConsumer<T, U> after) {
        Objects.requireNonNull(after, "after must not be null");
        return (t, u) -> {
            this.accept(t, u);
            after.accept(t, u);
        };
    }

    /**
     * Returns a standard {@link BiConsumer} that delegates to this consumer,
     * wrapping any checked exception in a {@link RuntimeException}.
     *
     * @return a {@link BiConsumer} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default BiConsumer<T, U> toUnchecked() {
        return (t, u) -> {
            try {
                this.accept(t, u);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
