// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testImplementation(libs.stellar.wallet.sdk)
  testImplementation(libs.okhttp3.mockserver)
  testImplementation(libs.assertj.core)
  testImplementation("org.springframework.boot:spring-boot-starter-web")
  testImplementation(libs.kotlin.serialization.json)
  testImplementation(libs.ktor.client.json)
  testImplementation(libs.google.gson)
  // project dependencies
  testImplementation(project(":lib-util"))
  testImplementation(project(":api-schema"))
  testImplementation(project(":core"))
  testImplementation(project(":platform"))
  testImplementation(project(":service-runner"))
  testImplementation(project(":wallet-reference-server"))
  testImplementation(project(":kotlin-reference-server"))
  testImplementation(testFixtures(project(":essential-tests")))
}

tasks { bootJar { enabled = false } }

tasks.test { exclude("**/org/stellar/anchor/platform/extendedtest/**") }
