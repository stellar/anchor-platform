// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  `java-library`
  `maven-publish`
  signing
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  compileOnly(libs.servlet.api)
  compileOnly(libs.slf4j.api)

  api(libs.lombok)

  annotationProcessor(libs.lombok)

  implementation(libs.apache.commons.lang3)
  implementation(libs.bcastle)
  implementation(libs.commons.beanutils)
  implementation(libs.commons.codec)
  implementation(libs.commons.io)
  implementation(libs.google.gson)
  implementation(libs.httpclient)
  implementation(libs.jakarta.annotation.api)
  implementation(libs.jakarta.transaction.api)
  implementation(libs.jakarta.validation.api)
  implementation(libs.jjwt)
  implementation(libs.log4j2.core)
  implementation(libs.micrometer.prometheus)
  implementation(libs.okhttp3)
  implementation(libs.snakeyaml)
  implementation(libs.spring.context)
  implementation(libs.spring.data.commons)
  implementation(variantOf(libs.java.stellar.sdk) { classifier("uber") })

  implementation(project(":api-schema"))
  implementation(project(":lib-util"))

  testImplementation(libs.coroutines.core)
  testImplementation(libs.okhttp3.mockserver)
  testImplementation(libs.servlet.api)
  testImplementation(libs.slf4j.api)
  testImplementation(libs.stellar.wallet.sdk)
}

publishing {
  apply<MavenPublishPlugin>()

  configure<JavaPluginExtension> {
    withJavadocJar()
    withSourcesJar()
  }

  configure<PublishingExtension> {
    publications {
      val main by
        creating(MavenPublication::class) {
          from(components["java"])
          groupId = "org.stellar.anchor-sdk"

          pom {
            name.set("stellar-anchor-sdk")
            description.set("Stellar Anchor SDK - Java")
            url.set("https://github.com/stellar/java-stellar-anchor-sdk")
            licenses {
              license {
                name.set("Apache 2.0")
                url.set("https://github.com/stellar/java-stellar-anchor-sdk/blob/main/LICENSE")
              }
            }
            developers {
              developer {
                id.set("lijamie98")
                name.set("Jamie Li")
                email.set("jamie@stellar.org")
              }
              developer {
                id.set("JakeUrban")
                name.set("Jake Urban")
                email.set("jake@stellar.org")
              }
              developer {
                id.set("marcelosalloum")
                name.set("Marcelo Salloum")
                email.set("marcelo@stellar.org")
              }
            }
            scm {
              connection.set("scm:git:git://github.com/stellar/java-stellar-anchor-sdk")
              developerConnection.set("scm:git:git://github.com/stellar/java-stellar-anchor-sdk")
              url.set("https://github.com/stellar/java-stellar-anchor-sdk")
            }
          }
        }
    }
    repositories {
      maven {
        name = "OSSRH"
        setUrl("https://oss.sonatype.org/service/local/staging/deploy/maven2")
        credentials {
          username = System.getenv("OSSRH_USER") ?: return@credentials
          password = System.getenv("OSSRH_PASSWORD") ?: return@credentials
        }
      }
    }
  }

  apply<SigningPlugin>()
  configure<SigningExtension> { sign(publishing.publications) }
}
