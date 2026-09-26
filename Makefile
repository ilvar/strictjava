.PHONY: build analyzers test check clean

build:
	./scripts/build.sh

analyzers:
	./gradlew --no-daemon prepareAnalyzers

test: build analyzers
	./scripts/test.sh
	./scripts/test-m1.sh
	./scripts/test-m2.sh
	./scripts/test-m3.sh

check: build analyzers
	java -jar build/strictjava.jar check .

clean:
	rm -rf build
