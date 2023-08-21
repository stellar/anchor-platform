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
  implementation(libs.java.stellar.sdk)
  implementation(libs.jjwt)
  implementation(libs.javax.jaxb.api)
  implementation(libs.kotlin.logging)
  implementation(libs.slf4j.simple)
  implementation(libs.h2database)
  implementation(libs.exposed)
  implementation(project(mapOf("path" to ":api-schema")))
  implementation("io.ktor:ktor-server-auth:${libs.versions.ktor}")
  implementation("io.ktor:ktor-server-auth-jwt:${libs.versions.ktor}")
}

tasks {
  compileKotlin {
    dependsOn("spotlessKotlinApply")
    kotlinOptions.jvmTarget = "11"
  }

  test { useJUnitPlatform() }
}

application { mainClass.set("org.stellar.reference.ReferenceServerKt") }
