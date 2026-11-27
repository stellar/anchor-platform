#!/user/bin/env bash
java -XX:+ExitOnOutOfMemoryError $JVM_FLAGS -jar /app/anchor-platform-runner.jar "$@"