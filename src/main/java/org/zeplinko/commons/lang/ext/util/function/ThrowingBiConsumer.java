package org.zeplinko.commons.lang.ext.util.function;

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
}
