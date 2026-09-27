package org.zeplinko.commons.lang.ext.util.function;

import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link java.util.function.Function}-like functional interface whose
 * {@link #apply(Object)} method is allowed to throw a checked
 * {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code Function} would normally
 * be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of the input to the function
 * @param <R> the type of the result of the function
 */
@FunctionalInterface
public interface ThrowingFunction<T, R> {
    /**
     * Applies this function to the given argument.
     *
     * @param t the function argument
     * @return the function result
     * @throws Exception if the operation fails
     */
    R apply(T t) throws Exception;

    /**
     * Returns a composed function that first applies this function to its input,
     * and then applies the {@code after} function to the result.
     *
     * @param <V>   the type of output of the {@code after} function
     * @param after the function to apply after this function
     * @return a composed function
     */
    default <V> ThrowingFunction<T, V> andThen(ThrowingFunction<R, V> after) {
        Objects.requireNonNull(after, "after must not be null");
        return t -> after.apply(this.apply(t));
    }

    /**
     * Returns a composed function that first applies the {@code before} function to
     * its input, and then applies this function to the result.
     *
     * @param <V>    the type of input to the {@code before} function
     * @param before the function to apply before this function
     * @return a composed function
     */
    default <V> ThrowingFunction<V, R> compose(ThrowingFunction<V, T> before) {
        Objects.requireNonNull(before, "before must not be null");
        return v -> this.apply(before.apply(v));
    }

    /**
     * Returns a function that always returns its input argument.
     *
     * @param <T> the type of the input and output of the function
     * @return a function that always returns its input argument
     */
    static <T> ThrowingFunction<T, T> identity() {
        return t -> t;
    }

    /**
     * Returns a standard {@link Function} that delegates to this function, wrapping
     * any checked exception in a {@link RuntimeException}.
     *
     * <p>
     * This is useful for using this function in stream pipelines and other contexts
     * that require a standard {@code Function}.
     * </p>
     *
     * @return a {@link Function} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default Function<T, R> toUnchecked() {
        return t -> {
            try {
                return this.apply(t);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
