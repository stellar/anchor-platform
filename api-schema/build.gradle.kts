// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  api(libs.lombok)

  implementation(libs.google.gson)
  implementation(libs.reactor.core)

  annotationProcessor(libs.lombok)
}


