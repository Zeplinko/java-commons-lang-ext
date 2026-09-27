package org.zeplinko.commons.lang.ext.util.function;

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
}
