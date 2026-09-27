.PHONY: build test

build:
	@./mvnw clean spotless:apply package

test:
	@./mvnw test
