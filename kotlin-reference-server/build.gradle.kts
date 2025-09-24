// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktor)
}

dependencies {
  implementation(libs.bundles.ktor)
  implementation(libs.bundles.ktor.client)
  implementation(libs.google.gson)
  implementation(libs.hoplite.core)
  implementation(libs.hoplite.yaml)
  implementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })
  implementation(libs.jjwt)
  implementation(libs.bcastle)
  implementation(libs.javax.jaxb.api)
  implementation(libs.kotlin.logging)
  implementation(libs.slf4j.simple)
  implementation(libs.h2database)
  implementation(libs.bundles.exposed)
  implementation(project(mapOf("path" to ":api-schema")))
}

tasks {
  compileKotlin {
    dependsOn("spotlessKotlinApply")
    kotlinOptions.jvmTarget = "17"
  }

  test { useJUnitPlatform() }
}

application { mainClass.set("org.stellar.reference.ReferenceServerKt") }
