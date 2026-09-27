package org.zeplinko.commons.lang.ext.util.function;

import java.util.function.Supplier;

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

    /**
     * Returns a standard {@link Supplier} that delegates to this supplier, wrapping
     * any checked exception in a {@link RuntimeException}.
     *
     * @return a {@link Supplier} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default Supplier<T> toUnchecked() {
        return () -> {
            try {
                return this.get();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
