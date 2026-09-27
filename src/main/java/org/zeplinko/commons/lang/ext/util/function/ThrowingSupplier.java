package org.zeplinko.commons.lang.ext.util.function;

/**
 * A {@link java.util.function.Supplier}-like functional interface whose
 * {@link #get()} method is allowed to throw a checked {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code Supplier} would normally
 * be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of results supplied by this supplier
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {
    /**
     * Gets a result.
     *
     * @return a result
     * @throws Exception if the operation fails
     */
    T get() throws Exception;
}
