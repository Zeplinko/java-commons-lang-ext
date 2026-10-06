package org.zeplinko.commons.lang.ext.util.function;

/**
 * A {@link java.lang.Runnable}-like functional interface whose {@link #run()}
 * method is allowed to throw a checked {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code Runnable} would normally
 * be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 */
@FunctionalInterface
public interface ThrowingRunnable {
    /**
     * Executes this runnable operation.
     *
     * @throws Exception if the operation fails
     */
    void run() throws Exception;

    /**
     * Returns a standard {@link Runnable} that delegates to this runnable, wrapping
     * any checked exception in a {@link RuntimeException}.
     *
     * @return a {@link Runnable} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default Runnable toUnchecked() {
        return () -> {
            try {
                this.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
