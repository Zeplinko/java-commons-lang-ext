package org.zeplinko.commons.lang.ext.core;

import jakarta.annotation.Nonnull;
import org.zeplinko.commons.lang.ext.annotations.Preview;
import org.zeplinko.commons.lang.ext.util.function.ThrowingBiFunction;
import org.zeplinko.commons.lang.ext.util.function.ThrowingConsumer;
import org.zeplinko.commons.lang.ext.util.function.ThrowingFunction;
import org.zeplinko.commons.lang.ext.util.function.ThrowingRunnable;
import org.zeplinko.commons.lang.ext.util.function.ThrowingSupplier;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An immutable container that captures the <em>successful</em> result of a
 * computation or the <em>exception</em> it threw, allowing users to deal with
 * errors as ordinary values instead of relying on the Java {@code try / catch}
 * statement.<br>
 * <p>
 * Conceptually this type is the Java-centric equivalent of <a href=
 * "https://scala-lang.org/api/2.13.13/scala/util/Try.html">Scala&nbsp;{@code Try}</a>:
 * </p>
 *
 * <ul>
 * <li>If the computation succeeds, a {@linkplain #success(Object) success-case}
 * instance carries the returned value.</li>
 * <li>If it throws, a {@linkplain #failure(Exception) failure-case} instance
 * carries the thrown {@link Exception} (checked <strong>or</strong>
 * unchecked).</li>
 * </ul>
 *
 * <h2>Relationship to {@code AbstractOutcome} &amp; {@code Result}</h2>
 * {@code Try} specialises the more general {@link AbstractOutcome} by
 * hard-coding the <em>error</em> side to {@code Exception}. If you need to
 * model domain-specific error types instead, use {@link Result}{@code <D,E>}
 * where {@code E} is not limited to {@code Exception}.
 *
 * <h2>Key Features</h2>
 * <table summary="key combinators in Try">
 * <tr>
 * <th>Method</th>
 * <th>Purpose</th>
 * </tr>
 * <tr>
 * <td>{@link #to}</td>
 * <td>Wraps any <strong>synchronous</strong> code block and captures its
 * success or the exception it throws.</td>
 * </tr>
 * <tr>
 * <td>{@link #map}</td>
 * <td>Transforms the success value; propagates failure unchanged.</td>
 * </tr>
 * <tr>
 * <td>{@link #flatMap}</td>
 * <td>Chains multiple computations that themselves return {@code Try}.</td>
 * </tr>
 * <tr>
 * <td>{@link #recover}</td>
 * <td>Turns a failure into a success (fallback or compensation logic).</td>
 * </tr>
 * <tr>
 * <td>{@link #orElseThrow}</td>
 * <td>Escapes back into the imperative world, re-throwing if necessary.</td>
 * </tr>
 * </table>
 *
 * <h2>Thread-safety &amp; Immutability</h2> State is stored in {@code final}
 * fields and never modified after construction, making every {@code Try}
 * instance inherently thread-safe as long as the wrapped value is itself
 * thread-safe.
 *
 * @param <T> the type of the successful value
 * @author Shivam&nbsp;Nagpal
 */
public class Try<T> extends AbstractOutcome<T, Exception> {

    private Try(T data, Exception error) {
        super(data, error);
    }

    /**
     * Creates a successful Try containing the provided data.
     *
     * @param data The data to be contained in the Try.
     * @param <T>  The type of the success data.
     * @return A {@code Try} instance representing success.
     */
    public static <T> Try<T> success(T data) {
        return new Try<>(data, null);
    }

    /**
     * Creates a failed Try containing the provided exception.
     *
     * @param exception The exception to be contained in the Try.
     * @param <T>       The type of the success data.
     * @return A {@code Try} instance representing failure.
     */
    public static <T> Try<T> failure(@Nonnull Exception exception) {
        Objects.requireNonNull(exception);
        return new Try<>(null, exception);
    }

    /**
     * Creates a successful Try if the callable doesn't throw any exception;
     * otherwise, creates a failure Try with the exception thrown.
     *
     * @param callable The Callable lambda to be called.
     * @param <T>      The type of the success data.
     * @return A successful {@code Try} instance containing the returned data if the
     *         callable doesn't throw any exception; otherwise, a failed {@code Try}
     *         instance with the exception thrown
     */
    public static <T> Try<T> to(@Nonnull Callable<T> callable) {
        Objects.requireNonNull(callable);
        try {
            T result = callable.call();
            return Try.success(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * Executes a runnable that may throw a checked exception.
     *
     * @param runnable The runnable to execute.
     * @return A {@code Try<Empty>} representing success or failure.
     */
    @Preview
    public static Try<Empty> run(@Nonnull ThrowingRunnable runnable) {
        Objects.requireNonNull(runnable);
        try {
            runnable.run();
            return Try.success(Empty.getInstance());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * Executes a block of code with a resource that is automatically closed,
     * capturing any exceptions into a {@code Try}.
     *
     * @param resourceSupplier A supplier that creates the resource.
     * @param action           The function to execute with the resource.
     * @param <R>              The type of the resource (must be AutoCloseable).
     * @param <T>              The type of the result.
     * @return A {@code Try} containing the result or any exception thrown.
     */
    @Preview
    public static <R extends AutoCloseable, T> Try<T> withResources(
            @Nonnull ThrowingSupplier<R> resourceSupplier,
            @Nonnull ThrowingFunction<? super R, ? extends T> action
    ) {
        Objects.requireNonNull(resourceSupplier);
        Objects.requireNonNull(action);
        try (R resource = resourceSupplier.get()) {
            return Try.success(action.apply(resource));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * Executes a block of code with two resources that are automatically closed,
     * capturing any exceptions into a {@code Try}.
     * <p>
     * Resources are closed in reverse order of acquisition: the second resource is
     * closed first, followed by the first resource. If closing a resource throws an
     * exception, it is added as a suppressed exception if the action also threw.
     * </p>
     *
     * @param resourceSupplier1 A supplier that creates the first resource.
     * @param resourceSupplier2 A supplier that creates the second resource.
     * @param action            The function to execute with the resources.
     * @param <R1>              The type of the first resource (must be
     *                          AutoCloseable).
     * @param <R2>              The type of the second resource (must be
     *                          AutoCloseable).
     * @param <T>               The type of the result.
     * @return A {@code Try} containing the result or any exception thrown.
     */
    @Preview
    public static <R1 extends AutoCloseable, R2 extends AutoCloseable, T> Try<T> withResources(
            @Nonnull ThrowingSupplier<R1> resourceSupplier1,
            @Nonnull ThrowingSupplier<R2> resourceSupplier2,
            @Nonnull ThrowingBiFunction<? super R1, ? super R2, ? extends T> action
    ) {
        Objects.requireNonNull(resourceSupplier1);
        Objects.requireNonNull(resourceSupplier2);
        Objects.requireNonNull(action);
        try (
                R1 r1 = resourceSupplier1.get();
                R2 r2 = resourceSupplier2.get()
        ) {
            return Try.success(action.apply(r1, r2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * Executes a side effect operation with a resource that is automatically
     * closed.
     *
     * @param resourceSupplier A supplier that creates the resource.
     * @param action           The consumer to execute with the resource.
     * @param <R>              The type of the resource (must be AutoCloseable).
     * @return A {@code Try<Empty>} representing success or failure.
     */
    @Preview
    public static <R extends AutoCloseable> Try<Empty> consumeResource(
            @Nonnull ThrowingSupplier<R> resourceSupplier,
            @Nonnull ThrowingConsumer<? super R> action
    ) {
        Objects.requireNonNull(resourceSupplier);
        Objects.requireNonNull(action);
        try (R resource = resourceSupplier.get()) {
            action.accept(resource);
            return Try.success(Empty.getInstance());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Try.failure(e);
        } catch (Exception e) {
            return Try.failure(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFailure() {
        return this.getError() != null;
    }

    /**
     * Returns the data if the Try is successful; otherwise, throws the exception
     * contained within the Try construct
     *
     * @return The success data.
     * @throws Exception if the Try is a failure, the exception thrown is contained
     *                   within the Try
     */
    @SuppressWarnings("java:S112")
    public T orElseThrow() throws Exception {
        if (this.isFailure()) {
            throw getError();
        }
        return this.getData();
    }

    /**
     * Maps the success value of this {@code Try} using the provided mapping
     * function. If this {@code Try} is a failure, the same failure is returned.
     *
     * @param mapper A function to transform the success value.
     * @param <U>    The type of the mapped success value.
     * @return A {@code Try} containing the mapped value if successful, or the same
     *         failure.
     */
    public <U> Try<U> map(@Nonnull Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper);
        return this.isFailure() ? Try.failure(this.getError()) : Try.success(mapper.apply(this.getData()));
    }

    /**
     * Transforms the failure value of this {@code Try} into a success value using
     * the provided mapping function. If this {@code Try} is a success, it is
     * returned unchanged.
     *
     * @param mapper A function to transform the failure value into a success value.
     * @return A new {@code Try} with the transformed value if this was a failure,
     *         or the same success.
     */
    public Try<T> otherwise(@Nonnull Function<? super Exception, ? extends T> mapper) {
        Objects.requireNonNull(mapper);
        return this.isFailure() ? Try.success(mapper.apply(this.getError())) : this;
    }

    /**
     * Composes this {@code Try} into a new {@code Try} using the provided functions
     * for success and failure cases.
     *
     * @param successMapper Function to transform the success value.
     * @param failureMapper Function to transform the failure value.
     * @param <U>           The type of the resulting {@code Try}.
     * @return A {@code Try} resulting from applying the appropriate mapper.
     */
    public <U> Try<U> compose(
            @Nonnull Function<? super T, ? extends Try<U>> successMapper,
            @Nonnull Function<? super Exception, ? extends Try<U>> failureMapper
    ) {
        Objects.requireNonNull(successMapper);
        Objects.requireNonNull(failureMapper);
        return this.isFailure() ? failureMapper.apply(this.getError()) : successMapper.apply(this.getData());
    }

    /**
     * Flattens and maps the success value of this {@code Try} into another
     * {@code Try} using the provided mapping function. If this {@code Try} is a
     * failure, the same failure is returned.
     *
     * @param mapper A function to map the success value to a new {@code Try}.
     * @param <U>    The type of the resulting success value.
     * @return A {@code Try} resulting from the mapping function if successful, or
     *         the same failure.
     */
    public <U> Try<U> flatMap(@Nonnull Function<? super T, ? extends Try<U>> mapper) {
        return compose(mapper, Try::failure);
    }

    /**
     * Recovers from a failure by transforming it another {@code Try} using the
     * provided mapping function. If this {@code Try} is a success, it is returned
     * unchanged.
     *
     * @param mapper A function to transform the failure into a success {@code Try}.
     * @return A {@code Try} resulting from the recovery function if this was a
     *         failure, or the same success.
     */
    public Try<T> recover(@Nonnull Function<? super Exception, ? extends Try<T>> mapper) {
        return compose(Try::success, mapper);
    }

    /**
     * Attempts to recover from a failure if the error satisfies the predicated
     * <p>
     * If this Try is successful, returns it unchanged. If this Try contains an
     * error that is doesn't satisfy the predicate, returns it unchanged. If this
     * Try contains an error that satisfies the predicate, applies the failure
     * mapper to attempt recovery.
     * </p>
     *
     * @param predicate the predicate to test the error
     * @param mapper    function to apply to the exception if it matches the
     *                  specified type; should return a new Try representing the
     *                  recovery attempt
     * @return this Try if successful or if the error doesn't match the exception
     *         type; otherwise the result of applying the failureMapper to the error
     * @throws NullPointerException if predicate or failureMapper is null
     */
    public Try<T> recover(
            Predicate<? super Exception> predicate,
            Function<? super Exception, ? extends Try<T>> mapper
    ) {
        Objects.requireNonNull(predicate);
        Objects.requireNonNull(mapper);
        if (this.isFailure() && predicate.test(this.getError())) {
            return mapper.apply(this.getError());
        }
        return compose(Try::success, Try::failure);
    }

    /**
     * Attempts to recover from a failure if the error matches the specified
     * exception type.
     * <p>
     * If this Try is successful, returns it unchanged. If this Try contains an
     * error that is NOT an instance of the specified exception type, returns it
     * unchanged. If this Try contains an error that IS an instance of the specified
     * exception type, applies the failure mapper to attempt recovery.
     * </p>
     *
     * <p>
     * Example usage:
     * </p>
     *
     * <pre>
     * {
     *     &#64;code
     *     Try&lt;String&gt; result = Try.of(() -&gt; riskyOperation())
     *             .recover(IOException.class, ex -&gt; Try.success("default value"))
     *             .recover(TimeoutException.class, ex -&gt; Try.failure(new CustomException(ex)));
     * }
     * </pre>
     *
     * @param exceptionType the class of the exception type to match against
     * @param mapper        function to apply to the exception if it matches the
     *                      specified type; should return a new Try representing the
     *                      recovery attempt
     * @return this Try if successful or if the error doesn't match the exception
     *         type; otherwise the result of applying the failureMapper to the error
     * @throws NullPointerException if exceptionType or failureMapper is null
     * @see #recover(Function)
     * @see #recover(Function, Class[])
     */
    public Try<T> recover(
            Class<? extends Exception> exceptionType,
            Function<? super Exception, ? extends Try<T>> mapper
    ) {
        Objects.requireNonNull(exceptionType);
        Objects.requireNonNull(mapper);
        return recover(exceptionType::isInstance, mapper);
    }

    /**
     * Attempts to recover from a failure if the error matches any of the specified
     * exception types.
     * <p>
     * This method provides a convenient way to recover from multiple exception
     * types using a single failure mapper. If this Try is successful, it returns
     * unchanged. If this Try contains an error that does NOT match any of the
     * specified exception types, it returns unchanged. If this Try contains an
     * error that matches at least one of the specified exception types, the failure
     * mapper is applied to attempt recovery.
     * </p>
     *
     * <p>
     * Null elements in the varargs array are automatically filtered out and ignored
     * during exception type matching.
     * </p>
     *
     * <p>
     * Example usage:
     * </p>
     *
     * <pre>
     * {
     *     &#64;code
     *     // Recover from multiple I/O-related exceptions with a single handler
     *     Try&lt;String&gt; result = Try.to(() -&gt; readFile())
     *             .recover(
     *                     ex -&gt; Try.success("default content"),
     *                     IOException.class,
     *                     FileNotFoundException.class,
     *                     SocketTimeoutException.class
     *             );
     *
     *     // Chain multiple recovery strategies for different exception groups
     *     Try&lt;Data&gt; processed = Try.to(() -&gt; processData())
     *             .recover(
     *                     ex -&gt; Try.success(Data.empty()),
     *                     IOException.class,
     *                     TimeoutException.class
     *             )
     *             .recover(
     *                     ex -&gt; Try.failure(new ProcessingException(ex)),
     *                     ValidationException.class,
     *                     ParseException.class
     *             );
     * }
     * </pre>
     *
     * @param mapper         function to apply to the exception if it matches any of
     *                       the specified types; should return a new Try
     *                       representing the recovery attempt
     * @param exceptionTypes varargs array of exception types to match against; null
     *                       elements are ignored
     * @return this Try if successful or if the error doesn't match any exception
     *         type; otherwise the result of applying the failureMapper to the error
     * @throws NullPointerException if failureMapper or exceptionTypes array itself
     *                              is null
     * @see #recover(Class, Function)
     * @see #recover(Function)
     */
    @SafeVarargs
    @Preview
    public final Try<T> recover(
            Function<? super Exception, ? extends Try<T>> mapper,
            Class<? extends Exception>... exceptionTypes
    ) {
        Objects.requireNonNull(mapper);
        Objects.requireNonNull(exceptionTypes);
        return recover(
                e -> Arrays.stream(exceptionTypes)
                        .filter(Objects::nonNull)
                        .anyMatch(exceptionType -> exceptionType.isInstance(e)),
                mapper
        );
    }

    /**
     * Transforms this {@code Try} into another {@code Try} using the provided
     * transformation function.
     *
     * @param mapper A function to transform this {@code Try}.
     * @param <U>    The type of the resulting {@code Try}.
     * @return A {@code Try} resulting from the transformation function.
     */
    public <U> Try<U> transform(@Nonnull Function<? super Try<T>, ? extends Try<U>> mapper) {
        Objects.requireNonNull(mapper);
        return mapper.apply(this);
    }

    /**
     * Handles the success or failure values of this {@code Try} using the provided
     * consumers.
     *
     * @param dataConsumer  Consumer to handle the success value.
     * @param errorConsumer Consumer to handle the failure value.
     * @return This {@code Try} instance.
     */
    public Try<T> onHandle(Consumer<? super T> dataConsumer, Consumer<? super Exception> errorConsumer) {
        if (isFailure()) {
            Optional.ofNullable(errorConsumer).ifPresent(handler -> handler.accept(this.getError()));
        } else {
            Optional.ofNullable(dataConsumer).ifPresent(handler -> handler.accept(this.getData()));
        }
        return this;
    }

    /**
     * Handles this {@code Try} using a single consumer that receives the entire
     * {@code Try}.
     *
     * @param tryConsumer Consumer to handle this {@code Try}.
     * @return This {@code Try} instance.
     */
    public Try<T> onHandle(Consumer<? super Try<T>> tryConsumer) {
        Optional.ofNullable(tryConsumer).ifPresent(handler -> handler.accept(this));
        return this;
    }

    /**
     * Executes the provided consumer if this {@code Try} is successful.
     *
     * @param dataConsumer Consumer to handle the success value.
     * @return This {@code Try} instance.
     */
    public Try<T> onSuccess(Consumer<? super T> dataConsumer) {
        return onHandle(dataConsumer, null);
    }

    /**
     * Executes the provided consumer if this {@code Try} is a failure.
     *
     * @param errorConsumer Consumer to handle the failure value.
     * @return This {@code Try} instance.
     */
    public Try<T> onFailure(Consumer<? super Exception> errorConsumer) {
        return onHandle(null, errorConsumer);
    }

    /**
     * Executes the provided consumer if this {@code Try} is a failure and the error
     * satisfies the predicate.
     * <p>
     * If this Try is successful, nothing happens, and this Try is returned
     * unchanged. If this Try contains an error that is doesn't satisfy the
     * predicate, nothing happens, and this Try is returned unchanged. If this Try
     * contains an error that satisfies the predicate, the error consumer is
     * executed.
     * </p>
     *
     * <p>
     * This method is useful for handling specific exception types differently, such
     * as logging at different levels, recording metrics, or performing
     * type-specific cleanup operations.
     * </p>
     *
     * @param predicate     the class of the exception type to match against
     * @param errorConsumer consumer to execute if the error matches the specified
     *                      type
     * @return this Try instance for method chaining
     * @throws NullPointerException if predicate is null
     * @see #onFailure(Consumer)
     * @see #onFailure(Consumer, Class[])
     * @see #recover(Class, Function)
     */
    public Try<T> onFailure(
            Predicate<? super Exception> predicate,
            Consumer<? super Exception> errorConsumer
    ) {
        Objects.requireNonNull(predicate);
        if (isFailure() && predicate.test(getError())) {
            return onHandle(null, errorConsumer);
        }
        return this;
    }

    /**
     * Executes the provided consumer if this {@code Try} is a failure and the error
     * matches the specified exception type.
     * <p>
     * If this Try is successful, nothing happens, and this Try is returned
     * unchanged. If this Try contains an error that is NOT an instance of the
     * specified exception type, nothing happens, and this Try is returned
     * unchanged. If this Try contains an error that IS an instance of the specified
     * exception type, the error consumer is executed.
     * </p>
     *
     * <p>
     * This method is useful for handling specific exception types differently, such
     * as logging at different levels, recording metrics, or performing
     * type-specific cleanup operations.
     * </p>
     *
     * <p>
     * Example usage:
     * </p>
     *
     * <pre>
     * {
     *     &#64;code
     *     // Log different exception types at different levels
     *     Try&lt;Data&gt; result = Try.to(() -&gt; fetchData())
     *             .onFailure(IOException.class, ex -&gt; logger.error("I/O error: {}", ex.getMessage()))
     *             .onFailure(TimeoutException.class, ex -&gt; logger.warn("Timeout: {}", ex.getMessage()))
     *             .onFailure(ex -&gt; logger.error("Unexpected error", ex));
     *
     *     // Record type-specific metrics
     *     Try&lt;Response&gt; apiResult = Try.to(() -&gt; callApi())
     *             .onFailure(TimeoutException.class, ex -&gt; metrics.increment("api.timeout"))
     *             .onFailure(IOException.class, ex -&gt; metrics.increment("api.network_error"));
     * }
     * </pre>
     *
     * @param exceptionType the class of the exception type to match against
     * @param errorConsumer consumer to execute if the error matches the specified
     *                      type
     * @return this Try instance for method chaining
     * @throws NullPointerException if exceptionType is null
     * @see #onFailure(Consumer)
     * @see #onFailure(Consumer, Class[])
     * @see #recover(Class, Function)
     */
    public Try<T> onFailure(
            Class<? extends Exception> exceptionType,
            Consumer<? super Exception> errorConsumer
    ) {
        Objects.requireNonNull(exceptionType);
        return onFailure(exceptionType::isInstance, errorConsumer);
    }

    /**
     * Executes the provided consumer if this {@code Try} is a failure and the error
     * matches any of the specified exception types.
     * <p>
     * This method provides a convenient way to handle multiple exception types
     * using a single error consumer. If this Try is successful, nothing happens,
     * and this Try is returned unchanged. If this Try contains an error that does
     * NOT match any of the specified exception types, nothing happens, and this Try
     * is returned unchanged. If this Try contains an error that matches at least
     * one of the specified exception types, the error consumer is executed.
     * </p>
     *
     * <p>
     * Null elements in the varargs array are automatically filtered out and ignored
     * during exception type matching.
     * </p>
     *
     * <p>
     * Example usage:
     * </p>
     *
     * <pre>
     * {
     *     &#64;code
     *     // Handle multiple I/O-related exceptions with a single handler
     *     Try&lt;String&gt; result = Try.to(() -&gt; readFile())
     *             .onFailure(
     *                     ex -&gt; logger.error("I/O operation failed: {}", ex.getMessage()),
     *                     IOException.class,
     *                     FileNotFoundException.class,
     *                     SocketTimeoutException.class
     *             );
     *
     *     // Combine different error handling strategies
     *     Try&lt;Data&gt; processed = Try.to(() -&gt; processData())
     *             .onFailure(
     *                     ex -&gt; metrics.increment("errors.io"),
     *                     IOException.class,
     *                     TimeoutException.class
     *             )
     *             .onFailure(
     *                     ex -&gt; metrics.increment("errors.validation"),
     *                     ValidationException.class,
     *                     ParseException.class
     *             )
     *             .onFailure(ex -&gt; metrics.increment("errors.other"));
     * }
     * </pre>
     *
     * @param errorConsumer  consumer to execute if the error matches any of the
     *                       specified types
     * @param exceptionTypes varargs array of exception types to match against; null
     *                       elements are ignored
     * @return this Try instance for method chaining
     * @throws NullPointerException if exceptionTypes array itself is null
     * @see #onFailure(Consumer)
     * @see #onFailure(Class, Consumer)
     * @see #recover(Function, Class[])
     */
    @SafeVarargs
    @Preview
    public final Try<T> onFailure(
            Consumer<? super Exception> errorConsumer,
            Class<? extends Exception>... exceptionTypes
    ) {
        Objects.requireNonNull(exceptionTypes);
        return onFailure(
                e -> Arrays.stream(exceptionTypes)
                        .filter(Objects::nonNull)
                        .anyMatch(exceptionType -> exceptionType.isInstance(e)),
                errorConsumer
        );
    }

    /**
     * Converts this {@code Try} into a {@code Result} object.
     *
     * @return A {@code Result} representing the same success or failure as this
     *         {@code Try}.
     */
    public Result<T, Exception> toResult() {
        return this.isFailure() ? Result.failure(this.getError()) : Result.success(this.getData());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return isFailure()
                ? "Try.Failure[" + getError() + "]"
                : "Try.Success[" + getData() + "]";
    }
}
