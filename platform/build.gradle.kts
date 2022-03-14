plugins {
  application
  id("org.springframework.boot") version "2.6.3"
  id("io.spring.dependency-management") version "1.0.11.RELEASE"
  id("org.jetbrains.kotlin.jvm") version "1.6.10"
}

dependencies {
  api(libs.lombok)

  implementation("org.springframework.boot:spring-boot")
  implementation("org.springframework.boot:spring-boot-autoconfigure")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation(libs.commons.cli)
  implementation(libs.google.gson)
  implementation(libs.java.stellar.sdk)
  implementation(libs.sqlite.jdbc)
  implementation(libs.okhttp3)

  annotationProcessor(libs.lombok)
  annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

  // From projects
  implementation(project(":core"))
  implementation(project(":config-spring-property"))
  implementation(project(":data-spring-jdbc"))
  implementation(project(":platform-apis"))
  implementation(project(":anchor-reference-server"))
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation(libs.okhttp3.mockserver)
}

application { mainClass.set("org.stellar.anchor.platform.ServiceRunner") }

configurations {
  all {
    exclude(group = "ch.qos.logback", module = "logback-classic")
    exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
  }
}
