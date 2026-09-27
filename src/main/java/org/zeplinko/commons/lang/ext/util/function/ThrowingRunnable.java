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
}
