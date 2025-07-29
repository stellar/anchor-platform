import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktor)
}

dependencies {
  implementation(libs.bundles.exposed)
  implementation(libs.bundles.ktor)
  implementation(libs.bundles.ktor.client)
  implementation(libs.google.gson)
  implementation(libs.hoplite.core)
  implementation(libs.hoplite.yaml)
  implementation(libs.httpcore)
  implementation(libs.jjwt)
  implementation(libs.kotlin.logging)
  implementation(libs.slf4j.simple)
  implementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })

  implementation(project(mapOf("path" to ":api-schema")))
}

tasks {
  named<KotlinCompile>("compileKotlin") {
    dependsOn("spotlessKotlinApply")
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }

  test {
    useJUnitPlatform()
  }
}

application { mainClass.set("org.stellar.reference.ReferenceServerKt") }
