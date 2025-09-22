#ARG BASE_IMAGE=gradle:8.2.1-jdk17
#
#FROM ${BASE_IMAGE} AS build
#WORKDIR /code
#COPY --chown=gradle:gradle . .
#
#RUN gradle clean bootJar --stacktrace -x test -DignoreJdkCheck=true

FROM ubuntu:24.04
ARG JDK_VER=17.0.16_8
ARG TEMURIN_RELEASE=jdk-17.0.16+8
ARG TARGETARCH

#COPY --from=build /code/service-runner/build/libs/anchor-platform-runner*.jar /app/anchor-platform-runner.jar
#COPY --from=build /code/scripts/docker-start.sh /app/start.sh
COPY ./temp/anchor-platform/service-runner/build/libs/anchor-platform-runner*.jar /app/anchor-platform-runner.jar
COPY ./temp/anchor-platform/scripts/docker-start.sh /app/start.sh

# Install curl and ca-certificates
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates tar unzip \
 && rm -rf /var/lib/apt/lists/*

RUN bash -c 'echo "Building for arch: $TARGETARCH"'

# ---- Install Temurin JDK 17.0.16+8 ----
RUN case "$TARGETARCH" in \
      amd64|x86_64) DOWNLOAD_URL="https://github.com/adoptium/temurin17-binaries/releases/download/${TEMURIN_RELEASE}/OpenJDK17U-jdk_x64_linux_hotspot_${JDK_VER}.tar.gz" ;; \
      arm64|aarch64) DOWNLOAD_URL="https://github.com/adoptium/temurin17-binaries/releases/download/${TEMURIN_RELEASE}/OpenJDK17U-jdk_aarch64_linux_hotspot_${JDK_VER}.tar.gz" ;; \
      *) echo "Unsupported arch: '$arch'"; exit 1 ;; \
    esac && \
    echo "JDK DOWNLOAD_URL: $DOWNLOAD_URL" && \
    curl -fsSL -H "User-Agent: curl" -o /tmp/openjdk17.tar.gz "$DOWNLOAD_URL" && \
    mkdir -p /usr/lib/jvm && \
    tar -xzf /tmp/openjdk17.tar.gz -C /usr/lib/jvm && \
    ln -s /usr/lib/jvm/${TEMURIN_RELEASE} /usr/lib/jvm/temurin-17 && \
    ln -s /usr/lib/jvm/temurin-17/bin/java /usr/bin/java

# Make Java available via JAVA_HOME and PATH
ENV JAVA_HOME=/usr/lib/jvm/temurin-17
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Sanity check
RUN java --version

ENTRYPOINT ["/bin/bash", "/app/start.sh"]
