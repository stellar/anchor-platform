// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
  id("org.gradle.test-retry") version "1.6.2"
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

evaluationDependsOn(":essential-tests")

val essentialTestFixtures =
  project(":essential-tests").extensions.getByType<SourceSetContainer>()["testFixtures"]

val extendedSuites =
  mapOf(
    "AuthApikey" to "org.stellar.anchor.platform.extendedtest.auth.apikey.platform",
    "AuthJwt" to "org.stellar.anchor.platform.extendedtest.auth.jwt.platform",
    "ClientsDb" to "org.stellar.anchor.platform.extendedtest.clients.db",
    "End2EndHorizon" to "org.stellar.anchor.platform.e2etest",
  )

extendedSuites.forEach { (suite, testPackage) ->
  tasks.register<Test>("test$suite") {
    description = "Run the $suite extended tests against a running server."
    group = "verification"
    testClassesDirs =
      if (suite == "End2EndHorizon") essentialTestFixtures.output.classesDirs
      else sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty(
      "junit.jupiter.testclass.order.default",
      "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    filter { includeTestsMatching("$testPackage.*") }
    testLogging {
      events("SKIPPED", "FAILED")
      showExceptions = true
      showStandardStreams = true
      exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    retry {
      maxRetries.set(2)
      maxFailures.set(20)
      failOnPassedAfterRetry.set(false)
    }
  }
}

val verifyExtendedSuites by
  tasks.registering {
    description = "Fail when an extended test package is in no suite."
    group = "verification"
    val root = file("src/test/kotlin/org/stellar/anchor/platform/extendedtest")
    inputs.dir(root)
    doLast {
      val testAnnotation = Regex("""@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b""")
      val packageLine = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
      val missing =
        root
          .walk()
          .filter { it.extension == "kt" && testAnnotation.containsMatchIn(it.readText()) }
          .mapNotNull { packageLine.find(it.readText())?.groupValues?.get(1) }
          .filter { pkg -> extendedSuites.values.none { pkg == it || pkg.startsWith("$it.") } }
          .toSet()
      if (missing.isNotEmpty()) {
        throw GradleException("Extended test packages in no suite: $missing")
      }
    }
  }

tasks.check { dependsOn(verifyExtendedSuites) }
