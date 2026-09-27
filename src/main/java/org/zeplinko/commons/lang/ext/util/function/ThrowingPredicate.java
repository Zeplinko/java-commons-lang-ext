package org.zeplinko.commons.lang.ext.util.function;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A {@link java.util.function.Predicate}-like functional interface whose
 * {@link #test(Object)} method is allowed to throw a checked {@link Exception}.
 *
 * <p>
 * This is useful when you want to use lambdas or method references that can
 * throw checked exceptions in contexts where a {@code Predicate} would normally
 * be used.
 * </p>
 *
 * @author Shivam&nbsp;Nagpal
 *
 * @param <T> the type of the input to the predicate
 */
@FunctionalInterface
public interface ThrowingPredicate<T> {
    /**
     * Evaluates this predicate on the given argument.
     *
     * @param t the input argument
     * @return {@code true} if the input argument matches the predicate, otherwise
     *         {@code false}
     * @throws Exception if the evaluation fails
     */
    boolean test(T t) throws Exception;

    /**
     * Returns a composed predicate that represents a short-circuit logical AND of
     * this predicate and the {@code other}. If this predicate evaluates to
     * {@code false}, the {@code other} predicate is not evaluated.
     *
     * @param other the predicate to AND with this predicate
     * @return a composed predicate
     */
    default ThrowingPredicate<T> and(ThrowingPredicate<T> other) {
        Objects.requireNonNull(other, "other must not be null");
        return t -> this.test(t) && other.test(t);
    }

    /**
     * Returns a composed predicate that represents a short-circuit logical OR of
     * this predicate and the {@code other}. If this predicate evaluates to
     * {@code true}, the {@code other} predicate is not evaluated.
     *
     * @param other the predicate to OR with this predicate
     * @return a composed predicate
     */
    default ThrowingPredicate<T> or(ThrowingPredicate<T> other) {
        Objects.requireNonNull(other, "other must not be null");
        return t -> this.test(t) || other.test(t);
    }

    /**
     * Returns a predicate that represents the logical negation of this predicate.
     *
     * @return a predicate that negates this predicate
     */
    default ThrowingPredicate<T> negate() {
        return t -> !this.test(t);
    }

    /**
     * Returns a predicate that tests if the argument is equal to {@code target}
     * according to {@link Objects#equals(Object, Object)}. Handles null targets
     * correctly.
     *
     * @param <T>    the type of the input to the predicate
     * @param target the reference object for equality comparison (may be null)
     * @return a predicate that tests equality against {@code target}
     */
    static <T> ThrowingPredicate<T> isEqual(Object target) {
        return t -> Objects.equals(target, t);
    }

    /**
     * Returns a predicate that is the negation of the supplied predicate. This is
     * useful as a method reference where a negated predicate is needed.
     *
     * @param <T>    the type of the input to the predicate
     * @param target the predicate to negate
     * @return a predicate that negates the supplied predicate
     */
    static <T> ThrowingPredicate<T> not(ThrowingPredicate<T> target) {
        Objects.requireNonNull(target, "target must not be null");
        return target.negate();
    }

    /**
     * Returns a standard {@link Predicate} that delegates to this predicate,
     * wrapping any checked exception in a {@link RuntimeException}.
     *
     * <p>
     * This is useful for using this predicate in stream pipelines and other
     * contexts that require a standard {@code Predicate}.
     * </p>
     *
     * @return a {@link Predicate} that wraps checked exceptions in
     *         {@link RuntimeException}
     */
    default Predicate<T> toUnchecked() {
        return t -> {
            try {
                return this.test(t);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
