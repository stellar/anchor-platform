# Check if we need to prepend docker command with sudo
SUDO := $(shell docker version >/dev/null 2>&1 || echo "sudo")

# If LABEL is not provided set default value
LABEL ?= $(shell git rev-parse --short HEAD)$(and $(shell git status -s),-dirty-$(shell id -u -n))
# If TAG is not provided set default value
TAG ?= stellar/anchor-platform:$(LABEL)
# https://github.com/opencontainers/image-spec/blob/master/annotations.md
BUILD_DATE := $(shell date -u +%FT%TZ)

# Define the output JAR file
JAR_FILE := service-runner/build/libs/anchor-platform-runner*.jar

# Clean build artifacts
.PHONY: clean
clean:
	gradle clean
	rm -rf service-runner/build
	rm -rf */build
	rm -rf .gradle

# Build the anchor platform JAR
.PHONY: build
build:
	gradle clean bootJar --stacktrace -x test

# Run the anchor platform
.PHONY: run
run: build
	chmod +x scripts/start_anchorplatform.sh
	./scripts/start_anchorplatform.sh

docker-build:
	$(SUDO) docker build --pull --label org.opencontainers.image.created="$(BUILD_DATE)" -t $(TAG) --build-arg GIT_COMMIT=$(LABEL) .

docker-push:
	$(SUDO) docker push $(TAG)