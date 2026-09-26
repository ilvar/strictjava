IMAGE ?= __PROJECT__:dev

.PHONY: all build test check fix fmt fmt-check verify-locks image clean

all: fmt-check test check

build:
	./gradlew --no-daemon build

test:
	./gradlew --no-daemon test

check:
	./scripts/strictjava.sh check --classpath "$$(./gradlew -q strictjavaClasspath)" .

fix:
	./scripts/strictjava.sh fix --classpath "$$(./gradlew -q strictjavaClasspath)" .

fmt:
	./gradlew --no-daemon format

fmt-check:
	./gradlew --no-daemon formatCheck

verify-locks:
	cp gradle.lockfile gradle.lockfile.before
	./gradlew --no-daemon dependencies --write-locks
	cmp gradle.lockfile.before gradle.lockfile
	rm gradle.lockfile.before

image:
	./gradlew --no-daemon installDist
	docker build --target from-artifact -t $(IMAGE) .

clean:
	./gradlew --no-daemon clean
