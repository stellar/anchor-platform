// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  `java-test-fixtures`
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testFixturesImplementation(libs.assertj.core)
  testFixturesImplementation(libs.httpclient)
  testFixturesImplementation(libs.kotlin.serialization.json)

  // Stellar dependencies
  testFixturesImplementation(libs.stellar.wallet.sdk)
  testFixturesImplementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })

  // Spring dependencies
  testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
  testFixturesImplementation("org.springframework.boot:spring-boot-starter-web")

  // project dependencies
  testFixturesImplementation(project(":api-schema"))
  testFixturesImplementation(project(":core"))
  testFixturesImplementation(project(":platform"))
  testFixturesImplementation(project(":kotlin-reference-server"))
  testFixturesImplementation(project(":wallet-reference-server"))
  testFixturesImplementation(project(":service-runner"))
  testFixturesImplementation(project(":lib-util"))

  testImplementation(libs.stellar.wallet.sdk)

  implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")
}

tasks { bootJar { enabled = false } }

tasks.test {
  exclude("**/org/stellar/anchor/platform/*Test.class")
  exclude("**/org/stellar/anchor/platform/integrationtest/**")
  exclude("**/org/stellar/anchor/platform/e2etest/**")
}
