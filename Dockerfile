# ---------- BUILD STAGE ----------
ARG BASE_IMAGE=gradle:7.6.4-jdk11
FROM ${BASE_IMAGE} AS build
WORKDIR /code
COPY --chown=gradle:gradle . .
RUN gradle clean bootJar --stacktrace -x test

# ---------- RUNTIME STAGE (Ubuntu 24.04 + Temurin 11) ----------
FROM ubuntu:24.04

# App files
COPY --from=build /code/service-runner/build/libs/anchor-platform-runner*.jar /app/anchor-platform-runner.jar
COPY --from=build /code/scripts/docker-start.sh /app/start.sh

# Refresh keyrings and install Temurin 11 JRE (headless = smaller)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates wget gnupg ubuntu-keyring && \
    install -d -m 0755 /etc/apt/keyrings && \
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg && \
    . /etc/os-release && \
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] \
        https://packages.adoptium.net/artifactory/deb ${UBUNTU_CODENAME} main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update

RUN apt-get install -y --no-install-recommends temurin-11-jre && \
    rm -rf /var/lib/apt/lists/*

# Optional sanity check
RUN java -version

ENTRYPOINT ["/bin/bash", "/app/start.sh"]
