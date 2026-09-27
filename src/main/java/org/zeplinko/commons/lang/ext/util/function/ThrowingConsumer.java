package org.zeplinko.commons.lang.ext.util.function;

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
}
