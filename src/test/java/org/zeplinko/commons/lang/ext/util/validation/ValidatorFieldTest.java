package org.zeplinko.commons.lang.ext.util.validation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

// P014: field(name, extractor, rules). Uses the existing generic builder API;
// include(), typed selectors, attributes, and member traversal have later phases.
class ValidatorFieldTest {

    @Test
    void givenMethodReferenceWhenConfiguringFieldThenUsesTypedChildBuilderAndReturnsParentBuilder() {
        Validator.Builder<Person> parent = Validator.builder(Person.class);

        Validator.Builder<Person> returned = parent.field(
                "address",
                Person::getAddress,
                address -> address.rule(value -> value.getStreetName() != null)
        );

        assertSame(parent, returned);
        Validator<Person> validator = returned.rule(person -> person.getName() != null).build();
        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address", "rule.unsatisfied");
    }

    @Test
    void givenTypedLambdaExtractorWhenConfiguringFieldThenInfersStringScopeWithoutClassTokenOrCast() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("name", (Person person) -> person.getName(), name -> name.rule(value -> value.length() > 3))
                .build();

        assertSingleErrorAt(validator.validate(new Person(new Address("Main Street"))), "#.name", "rule.unsatisfied");
    }

    @Test
    void givenPrimitiveGetterWhenConfiguringGenericFieldThenExposesBoxedValueToTypedRules() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("age", Person::getAge, (Validator.Builder<Integer> age) -> age.rule(value -> value >= 18))
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        Person underage = new Person("Ada", null, 17, Collections.emptyList());
        assertSingleErrorAt(validator.validate(underage), "#.age", "rule.unsatisfied");
    }

    @Test
    void givenRootAndSiblingFieldFailuresWhenValidatingThenCollectsAllAtTheirOwnNodes() {
        Validator<Person> validator = Validator.builder(Person.class)
                .rule("person.invalid", person -> false)
                .field("address", Person::getAddress, address -> address.rule("address.invalid", value -> false))
                .field("name", Person::getName, name -> name.rule("name.invalid", value -> false))
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "# [person.invalid]",
                "#.address [address.invalid]",
                "#.name [name.invalid]"
        );
    }

    @Test
    void givenNestedFieldsWhenValidatingThenComposesDeclaredNamesExactlyOnce() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "postalAddress",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "road",
                                        Address::getStreetName,
                                        street -> street
                                                .rule("street.invalid", "Street is invalid", value -> false)
                                )
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.postalAddress.road",
                "street.invalid"
        );
        assertEquals("Street is invalid", error.getMessage());
    }

    @Test
    void givenRepeatedFieldNamesAtDifferentLevelsWhenValidatingThenKeepsEveryStructuralSegment() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "value",
                        Person::getAddress,
                        address -> address
                                .field(
                                        "value",
                                        Address::getStreetName,
                                        street -> street
                                                .field("value", String::length, length -> length.rule(value -> false))
                                )
                )
                .build();

        assertSingleErrorAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.value.value.value",
                "rule.unsatisfied"
        );
    }

    @Test
    void givenMultipleChildRulesAndRepeatedCallsWhenValidatingThenConfiguresOnceAndExtractsOncePerCall() {
        AtomicInteger configurations = new AtomicInteger();
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                }, address -> {
                    configurations.incrementAndGet();
                    address.rule(value -> {
                        evaluations.incrementAndGet();
                        return value.getStreetName() != null;
                    }).rule(value -> {
                        evaluations.incrementAndGet();
                        return value.getStreetName().length() >= 3;
                    });
                })
                .build();

        assertEquals(1, configurations.get());
        assertEquals(0, extractions.get());
        assertEquals(0, evaluations.get());

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertEquals(1, extractions.get());
        assertEquals(2, evaluations.get());

        assertSingleErrorAt(validator.validate(new Person(new Address("A"))), "#.address", "rule.unsatisfied");
        assertEquals(1, configurations.get());
        assertEquals(2, extractions.get());
        assertEquals(4, evaluations.get());
    }

    @Test
    void givenMultipleParentBuildsWhenBuildingThenDoesNotRerunFieldConfiguration() {
        AtomicInteger configurations = new AtomicInteger();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                    configurations.incrementAndGet();
                    address.rule("address.invalid", value -> false);
                });

        Validator<Person> first = parent.build();
        Validator<Person> second = parent.build();

        assertEquals(1, configurations.get());
        assertSingleErrorAt(first.validate(new Person(null)), "#.address", "address.invalid");
        assertSingleErrorAt(second.validate(new Person(null)), "#.address", "address.invalid");
        assertEquals(1, configurations.get());
    }

    @Test
    void givenChildBuilderChangesAfterParentBuildWhenValidatingThenEarlierTemplateKeepsSnapshot() {
        AtomicReference<Validator.Builder<Address>> child = new AtomicReference<>();
        Validator.Builder<Person> parent = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                    child.set(address);
                    address.rule("original", value -> false);
                });
        Validator<Person> validator = parent.build();

        child.get().rule("added.later", value -> false);
        parent.rule("root.added.later", value -> false);

        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "original");
    }

    @Test
    void givenTwoFieldsSharingAnObjectWhenValidatingThenUsesFreshChildBuildersAndDistinctPaths() {
        AtomicReference<Validator.Builder<Address>> billing = new AtomicReference<>();
        AtomicReference<Validator.Builder<Address>> shipping = new AtomicReference<>();
        Address sharedAddress = new Address("Main Street");
        Validator<Person> validator = Validator.builder(Person.class)
                .field("billingAddress", Person::getAddress, address -> {
                    billing.set(address);
                    address.rule("billing.invalid", value -> false);
                })
                .field("shippingAddress", Person::getAddress, address -> {
                    shipping.set(address);
                    address.rule("shipping.invalid", value -> false);
                })
                .build();

        assertNotNull(billing.get());
        assertNotNull(shipping.get());
        assertNotSame(billing.get(), shipping.get());
        assertErrorsAt(
                validator.validate(new Person(sharedAddress)),
                "#.billingAddress [billing.invalid]",
                "#.shippingAddress [shipping.invalid]"
        );
    }

    @Test
    void givenTwoConditionsForSameFieldWhenValidatingThenExtractsForEachAndDoesNotDeduplicateFailures() {
        AtomicInteger extractions = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            extractions.incrementAndGet();
            return person.getAddress();
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.rule("address.invalid", value -> false))
                .field("address", extractor, address -> address.rule("address.invalid", value -> false))
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address [address.invalid]",
                "#.address [address.invalid]"
        );
        assertEquals(2, extractions.get());
    }

    @Test
    void givenNullParentWhenValidatingThenSkipsFieldOnceWithoutCallingExtractorOrDescendants() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", person -> {
                    extractions.incrementAndGet();
                    return person.getAddress();
                },
                        address -> address
                                .notNull()
                                .rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                })
                                .field("streetName", value -> {
                                    descendants.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull())
                )
                .build();

        ValidationError error = assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
        assertNonblankMessage(error);
        assertEquals(0, extractions.get());
        assertEquals(0, descendants.get());
    }

    @Test
    void givenNullRootAndTwoFieldsWhenValidatingThenReportsRootPresenceAndOneSkipPerField() {
        Validator<Person> validator = Validator.builder(Person.class)
                .notNull()
                .field("address", Person::getAddress, address -> address.notNull().rule(value -> false))
                .field("name", Person::getName, name -> name.notNull())
                .build();

        assertErrorsAt(
                validator.validate(null),
                "# [notNull]",
                "#.address [validation.skipped]",
                "#.name [validation.skipped]"
        );
    }

    @Test
    void givenNullExtractedValueWhenChildRulesAreNullAwareThenPassesNullIntoEveryPredicate() {
        AtomicInteger evaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .rule("address.absent", value -> {
                                    evaluations.incrementAndGet();
                                    assertNull(value);
                                    return true;
                                })
                                .rule("address.required", value -> {
                                    evaluations.incrementAndGet();
                                    assertNull(value);
                                    return false;
                                })
                )
                .build();

        assertSingleErrorAt(validator.validate(new Person(null)), "#.address", "address.required");
        assertEquals(2, evaluations.get());
    }

    @Test
    void givenNullExtractedValueWhenUsingAllNotNullAndRuleOverloadsThenReportsMetadataAtFieldNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .notNull("address.required")
                                .notNull("address.custom", "Address is required")
                                .rule(Objects::nonNull)
                                .rule("address.rule", Objects::nonNull)
                                .rule("address.rule.custom", "Address rule failed", Objects::nonNull)
                )
                .build();

        List<ValidationError> errors = validator.validate(new Person(null));

        assertErrorsAt(
                errors,
                "#.address [notNull]",
                "#.address [address.required]",
                "#.address [address.custom]",
                "#.address [rule.unsatisfied]",
                "#.address [address.rule]",
                "#.address [address.rule.custom]"
        );
        errors.forEach(ValidatorFieldTest::assertNonblankMessage);
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.custom".equals(error.getCode())
                                        && "Address is required".equals(error.getMessage())
                        )
        );
        assertTrue(
                errors.stream()
                        .anyMatch(
                                error -> "address.rule.custom".equals(error.getCode())
                                        && "Address rule failed".equals(error.getMessage())
                        )
        );
    }

    @Test
    void givenNullExtractedValueWhenOnlyNullAcceptingRuleIsConfiguredThenAddsNoImplicitPresenceError() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(Objects::isNull))
                .build();

        assertTrue(validator.validate(new Person(null)).isEmpty());
    }

    @Test
    void givenNullAddressWhenValidatingNestedFieldThenReportsAddressPresenceAndOneDescendantSkip() {
        AtomicInteger nestedExtractions = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .field("streetName", value -> {
                                    nestedExtractions.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull().rule(value -> false))
                )
                .build();

        assertErrorsAt(
                validator.validate(new Person(null)),
                "#.address [notNull]",
                "#.address.streetName [validation.skipped]"
        );
        assertEquals(0, nestedExtractions.get());
    }

    @Test
    void givenPresentAddressWithNullStreetWhenValidatingThenRunsStreetRulesAtNestedNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .notNull()
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .build();

        assertSingleErrorAt(validator.validate(new Person(new Address(null))), "#.address.streetName", "notNull");
    }

    @Test
    void givenNullExtractedValueWhenChildPredicateDereferencesItThenRecordsExecutionFailureAtField() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(value -> value.getStreetName() != null))
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(null)),
                "#.address",
                "validation.executionFailed"
        );
        assertTrue(error.getMessage().contains("NullPointerException"));
    }

    @Test
    void givenThrowingExtractorWhenValidatingThenReportsOnceAndContinuesIndependentRules() {
        AtomicInteger extractions = new AtomicInteger();
        AtomicInteger descendants = new AtomicInteger();
        AtomicInteger independentRules = new AtomicInteger();
        Function<Person, Address> extractor = person -> {
            extractions.incrementAndGet();
            throw new IllegalStateException("address lookup failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        extractor,
                        address -> address
                                .notNull()
                                .rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                })
                                .field("streetName", value -> {
                                    descendants.incrementAndGet();
                                    return value.getStreetName();
                                }, street -> street.notNull())
                )
                .field("name", Person::getName, name -> name.rule("name.invalid", value -> {
                    independentRules.incrementAndGet();
                    return false;
                }))
                .rule("person.invalid", person -> {
                    independentRules.incrementAndGet();
                    return false;
                })
                .build();

        List<ValidationError> errors = validator.validate(new Person(null));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.name [name.invalid]",
                "# [person.invalid]"
        );
        ValidationError executionError = errors.stream()
                .filter(error -> "validation.executionFailed".equals(error.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing extractor failure"));
        assertTrue(executionError.getMessage().contains("IllegalStateException"));
        assertTrue(executionError.getMessage().contains("address lookup failed"));
        assertEquals(1, extractions.get());
        assertEquals(0, descendants.get());
        assertEquals(2, independentRules.get());
    }

    @Test
    void givenThrowingNestedExtractorWhenValidatingThenReportsExecutionFailureAtNestedField() {
        AtomicInteger descendants = new AtomicInteger();
        Function<Address, String> extractor = address -> {
            throw new IllegalArgumentException("street lookup failed");
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", extractor, street -> street.rule(value -> {
                                    descendants.incrementAndGet();
                                    return false;
                                }))
                                .rule("address.invalid", value -> false)
                )
                .rule("person.invalid", person -> false)
                .build();

        assertErrorsAt(
                validator.validate(new Person(new Address("Main Street"))),
                "#.address.streetName [validation.executionFailed]",
                "#.address [address.invalid]",
                "# [person.invalid]"
        );
        assertEquals(0, descendants.get());
    }

    @Test
    void givenThrowingChildPredicateWhenValidatingThenContinuesOtherChildAndRootRules() {
        AtomicInteger nestedEvaluations = new AtomicInteger();
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .rule(value -> {
                                    throw new IllegalStateException("child rule failed");
                                })
                                .field(
                                        "streetName",
                                        Address::getStreetName,
                                        street -> street.rule("street.invalid", value -> {
                                            nestedEvaluations.incrementAndGet();
                                            return false;
                                        })
                                )
                )
                .rule("person.invalid", person -> false)
                .build();

        List<ValidationError> errors = validator.validate(new Person(new Address("Main Street")));

        assertErrorsAt(
                errors,
                "#.address [validation.executionFailed]",
                "#.address.streetName [street.invalid]",
                "# [person.invalid]"
        );
        assertEquals(1, nestedEvaluations.get());
    }

    @Test
    void givenExtractorThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal extractor failure");
        Function<Person, Address> extractor = person -> {
            throw failure;
        };
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", extractor, address -> address.notNull())
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person(null))));
    }

    @Test
    void givenChildPredicateThrowsJvmErrorWhenValidatingThenPropagatesSameError() {
        AssertionError failure = new AssertionError("fatal child failure");
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> address.rule(value -> {
                    throw failure;
                }))
                .build();

        assertSame(failure, assertThrows(AssertionError.class, () -> validator.validate(new Person(null))));
    }

    @Test
    void givenEmptyChildTemplateWhenParentExistsThenAcceptsPresentOrNullExtractedValue() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                })
                .build();

        assertTrue(validator.validate(new Person(new Address("Main Street"))).isEmpty());
        assertTrue(validator.validate(new Person(null)).isEmpty());
    }

    @Test
    void givenEmptyChildTemplateWhenParentIsNullThenStillReportsBlockedExtraction() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field("address", Person::getAddress, address -> {
                })
                .build();

        assertSingleErrorAt(validator.validate(null), "#.address", "validation.skipped");
    }

    @Test
    void givenWholeCollectionFieldWhenValidatingThenRulesReceiveTheCollectionAtOneNode() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "aliases",
                        Person::getAliases,
                        (Validator.Builder<List<String>> aliases) -> aliases
                                .rule("aliases.minimum", "At least two aliases", values -> values.size() >= 2)
                )
                .build();

        ValidationError error = assertSingleErrorAt(
                validator.validate(new Person(null)),
                "#.aliases",
                "aliases.minimum"
        );
        assertEquals("At least two aliases", error.getMessage());
        Person twoAliases = new Person("Ada", null, 18, Arrays.asList("Ada", "Augusta"));
        assertTrue(validator.validate(twoAliases).isEmpty());
    }

    @Test
    void givenRepeatedNestedValidationWhenInputsChangeThenResultsRemainImmutableAndCallLocal() {
        Validator<Person> validator = Validator.builder(Person.class)
                .field(
                        "address",
                        Person::getAddress,
                        address -> address
                                .field("streetName", Address::getStreetName, street -> street.notNull())
                )
                .build();

        List<ValidationError> first = validator.validate(new Person(new Address(null)));
        List<ValidationError> second = validator.validate(new Person(new Address("Main Street")));
        List<ValidationError> third = validator.validate(new Person(new Address(null)));

        assertSingleErrorAt(first, "#.address.streetName", "notNull");
        assertTrue(second.isEmpty());
        assertSingleErrorAt(third, "#.address.streetName", "notNull");
        assertNotSame(first, third);
        assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, () -> second.add(first.get(0)));
    }

    @Test
    void givenNullOrBlankFieldNameWhenConfiguringFieldThenRejectsConfiguration() {
        for (String name : new String[] { null, "", "   ", "\t\r\n" }) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class)
                            .field(name, Person::getAddress, address -> address.notNull())
                            .build()
            );
        }
    }

    @Test
    void givenReservedPathCharactersInFieldNameWhenConfiguringFieldThenRejectsConfiguration() {
        for (
            String name : new String[] { "address.street", "address[0]", "address]", "#address", ".", "[", "]", "#" }
        ) {
            assertThrows(
                    RuntimeException.class,
                    () -> Validator.builder(Person.class)
                            .field(name, Person::getAddress, address -> address.notNull())
                            .build()
            );
        }
    }

    @Test
    void givenNullExtractorWhenConfiguringFieldThenRejectsConfiguration() {
        Function<Person, Address> extractor = null;

        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).field("address", extractor, address -> address.notNull()).build()
        );
    }

    @Test
    void givenNullRulesCallbackWhenConfiguringFieldThenRejectsConfiguration() {
        Consumer<Validator.Builder<Address>> rules = null;

        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).field("address", Person::getAddress, rules).build()
        );
    }

    @Test
    void givenInvalidChildRuleWhenBuildingParentThenRejectsTemplateConfiguration() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class)
                        .field("address", Person::getAddress, address -> address.rule("", value -> true))
                        .build()
        );
    }

    @Test
    void givenThrowingConfigurationCallbackWhenBuildingParentThenPropagatesConfigurationFailure() {
        assertThrows(
                RuntimeException.class,
                () -> Validator.builder(Person.class).field("address", Person::getAddress, address -> {
                    throw new IllegalStateException("invalid child configuration");
                }).build()
        );
    }

    private static ValidationError assertSingleErrorAt(List<ValidationError> errors, String path, String code) {
        assertEquals(1, errors.size());
        ValidationError error = errors.get(0);
        assertAll(
                () -> assertEquals(path, error.getPath()),
                () -> assertEquals(code, error.getCode())
        );
        return error;
    }

    private static void assertErrorsAt(List<ValidationError> errors, String... expected) {
        List<String> actual = new ArrayList<>();
        for (ValidationError error : errors) {
            actual.add(error.getPath() + " [" + error.getCode() + "]");
        }
        List<String> expectedErrors = new ArrayList<>(Arrays.asList(expected));
        Collections.sort(actual);
        Collections.sort(expectedErrors);
        assertEquals(expectedErrors, actual);
    }

    private static void assertNonblankMessage(ValidationError error) {
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().trim().isEmpty());
    }

    private static final class Person {
        private final String name;

        private final Address address;

        private final int age;

        private final List<String> aliases;

        private Person(Address address) {
            this("Ada", address, 18, Collections.singletonList("Ada"));
        }

        private Person(String name, Address address, int age, List<String> aliases) {
            this.name = name;
            this.address = address;
            this.age = age;
            this.aliases = aliases;
        }

        private String getName() {
            return name;
        }

        private Address getAddress() {
            return address;
        }

        private int getAge() {
            return age;
        }

        private List<String> getAliases() {
            return aliases;
        }
    }

    private static final class Address {
        private final String streetName;

        private Address(String streetName) {
            this.streetName = streetName;
        }

        private String getStreetName() {
            return streetName;
        }
    }
}
