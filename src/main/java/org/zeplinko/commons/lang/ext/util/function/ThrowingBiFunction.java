package org.zeplinko.commons.lang.ext.util.function;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A {@link java.util.function.BiFunction}-like functional interface whose
 * {@link #apply(Object, Object)} method is allowed to throw a checked
 * {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code BiFunction} would
 * normally be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of the first argument to the function
 * @param <U> the type of the second argument to the function
 * @param <R> the type of the result of the function
 */
@FunctionalInterface
public interface ThrowingBiFunction<T, U, R> {
    /**
     * Applies this function to the given arguments.
     *
     * @param t the first function argument
     * @param u the second function argument
     * @return the function result
     * @throws Exception if the operation fails
     */
    R apply(T t, U u) throws Exception;

    /**
     * Returns a composed function that first applies this function to its inputs,
     * and then applies the {@code after} function to the result.
     *
     * @param <V>   the type of output of the {@code after} function
     * @param after the function to apply after this function
     * @return a composed function
     */
    default <V> ThrowingBiFunction<T, U, V> andThen(ThrowingFunction<R, V> after) {
        Objects.requireNonNull(after, "after must not be null");
        return (t, u) -> after.apply(this.apply(t, u));
    }

    /**
     * Returns a standard {@link BiFunction} that delegates to this function,
     * wrapping any checked exception in a {@link RuntimeException}.
     *
     * @return a {@link BiFunction} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default BiFunction<T, U, R> toUnchecked() {
        return (t, u) -> {
            try {
                return this.apply(t, u);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
