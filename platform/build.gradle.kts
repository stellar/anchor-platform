// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
  id("io.sentry.jvm.gradle") version "5.3.0" // Sentry plugin
}

// The Spring Boot 3.2.4 BOM manages org.testcontainers:testcontainers at 1.19.7, whose docker-java
// client is rejected by Docker Engine 29 (minimum API 1.40). Overriding the BOM property keeps the
// core artifact in step with the testcontainers-* modules declared below.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

dependencies {
  api(libs.lombok)

  annotationProcessor(libs.lombok)
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  implementation("org.springframework.boot:spring-boot")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-reactor-netty")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-aop")
  implementation("org.springframework.boot:spring-boot-starter-validation")

  implementation(libs.aws.iam.auth)
  implementation(libs.aws.rds)
  implementation(libs.aws.sqs)
  implementation(libs.bcastle)
  implementation(libs.bcutil)
  implementation(libs.commons.beanutils)
  implementation(libs.commons.cli)
  implementation(libs.commons.io)
  implementation(libs.commons.text)
  implementation(libs.flyway.core)
  implementation(libs.google.gson)
  implementation(libs.hibernate.types)
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.jjwt)
  implementation(libs.log4j2.api)
  implementation(libs.log4j2.core)
  implementation(libs.log4j2.slf4j2)
  implementation(libs.okhttp3)
  implementation(libs.postgresql)
  implementation(libs.sentry.agent)
  implementation(libs.servlet.api)
  implementation(libs.spring.kafka)
  implementation(libs.spring.retry)
  implementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })

  // From projects
  implementation(project(":api-schema"))
  implementation(project(":lib-util"))
  implementation(project(":core"))

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation(libs.okhttp3.mockserver)
  testImplementation(libs.okhttp3.tls)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
  testLogging {
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    events = setOf(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
  }
}

tasks { bootJar { enabled = false } }
