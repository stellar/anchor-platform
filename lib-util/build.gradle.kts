// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation(libs.bundles.junit)
  implementation(libs.commons.text)
  implementation(libs.coroutines.core)
  implementation(libs.google.gson)
  implementation(libs.jjwt)
  implementation(libs.bcastle)
  implementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })
  implementation(libs.okhttp3)
  implementation(project(":api-schema"))
}
