.PHONY: build test check clean

build:
	./scripts/build.sh

test:
	./scripts/test.sh

check: build
	java -jar build/strictjava.jar check .

clean:
	rm -rf build
