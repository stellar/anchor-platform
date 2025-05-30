import java.net.Socket
import org.apache.tools.ant.taskdefs.condition.Os

// The alias call in plugins scope produces IntelliJ false error which is suppressed here.
@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  java
  `java-test-fixtures`
  alias(libs.plugins.spotless)
  alias(libs.plugins.kotlin.jvm) apply false
}

// *******************************************************************************
// Task registration and configuration
// *******************************************************************************
fun skipNonCriticalTasks(tasks: TaskContainer) {
  tasks.matching { it.name == "spotlessApply" }.configureEach { enabled = false }
  tasks.matching { it.name == "spotlessKotlinApply" }.configureEach { enabled = false }
  tasks.matching { it.name == "javadoc" }.configureEach { enabled = false }
  tasks.matching { it.name == "javadocJar" }.configureEach { enabled = false }
  tasks.matching { it.name == "sourcesJar" }.configureEach { enabled = false }
  tasks.matching { it.name == "distTar" }.configureEach { enabled = false }
  tasks.matching { it.name == "distZip" }.configureEach { enabled = false }
  tasks.matching { it.name == "javadoc" }.configureEach { enabled = false }
  tasks.matching { it.name == "shadowJar" }.configureEach { enabled = false }
  tasks.matching { it.name == "shadowDistZip" }.configureEach { enabled = false }
  tasks.matching { it.name == "shadowDistTar" }.configureEach { enabled = false }
  tasks.matching { it.name == "bootDistTar" }.configureEach { enabled = false }
  tasks.matching { it.name == "bootDistZip" }.configureEach { enabled = false }
}

fun isPortActive(host: String = "localhost", port: Int): Boolean {
  return try {
    Socket(host, port).use { _ -> true }
  } catch (e: Exception) {
    false
  }
}

// The build task executed at GitHub Actions. This task is used to build the project and run the
// unit tests.
tasks.register("runBuild") {
  group = "github"
  description = "Build the project, and skip specific tasks."
  dependsOn("clean", "build")
  subprojects {
    if (name == "essential-tests" || name == "extended-tests") {
      tasks.named("test") { enabled = false }
    }
    dependsOn(tasks.named("build"))
    skipNonCriticalTasks(tasks)
  }
}

// The runEssentialTests task is used to run the essential tests. The task is used to check if the
// AnchorPlatform server is running before running the tests. The task also skips the non-critical
// tasks.
tasks.register("runEssentialTests") {
  group = "github"
  description = "Run the essential tests."
  dependsOn(":essential-tests:test")
  
  doFirst {
    if (!isPortActive(port = 8080)) {
      println("************************************************************")
      println(
          "ERROR: The AnchorPlatform server is not running. Please start the server before running the tests.")
      throw GradleException("AnchorPlatform server is not running.")
    }
  }
  
  subprojects {
    if (name == "essential-tests") {
      skipNonCriticalTasks(tasks)
    }
  }
}

// The printVersionName task is used to print the version name of the project. This
// is useful for CI/CD pipelines to get the version string of the project.
tasks.register("printVersionName") { 
  doLast {
    println(rootProject.version.toString()) 
  }
}

// The updateGitHook task is used to copy the pre-commit.sh file to the .git/hooks
// directory. This is part of the efforts to force the Java/Kotlin code to be formatted
// before committing the code.
tasks.register<Copy>("updateGitHook") {
  from("scripts/pre-commit.sh") { rename { it.removeSuffix(".sh") } }
  into(".git/hooks")
  doLast {
    if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
      project.exec { commandLine("chmod", "+x", ".git/hooks/pre-commit") }
    }
  }
}

tasks { build { dependsOn("updateGitHook") } }

// *******************************************************************************
// Common configurations
// *******************************************************************************

subprojects {
  apply(plugin = "java")
  apply(plugin = "com.diffplug.spotless")
  apply(plugin = "java-test-fixtures")

  repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven") }
    maven { url = uri("https://repository.mulesoft.org/nexus/content/repositories/public/") }
    maven { url = uri("https://jitpack.io") }
  }

  /** Specifies JDK-17 */
  java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

  spotless {
    java {
      importOrder("java", "javax", "org.stellar")
      removeUnusedImports()
      googleJavaFormat()
    }

    kotlin { ktfmt("0.42").googleStyle() }
  }

  dependencies {
    // `rootProject` is required here if we want to use the libs object in the root project.
    implementation(rootProject.libs.spotbugs.annotations)
    implementation(rootProject.libs.google.guava)
    implementation(rootProject.libs.log4j.template.json)

    // The common dependencies are declared here because we would like to have a uniform unit
    // testing across all subprojects.
    //
    // We need to use the dependency string because the VERSION_CATEGORY feature isn't supported in
    // subproject task as of today.
    //
    testImplementation(rootProject.libs.bundles.junit)
    testImplementation(rootProject.libs.jsonassert)

    testFixturesImplementation(rootProject.libs.bundles.junit)
    testFixturesImplementation(rootProject.libs.jsonassert)

    testAnnotationProcessor(rootProject.libs.lombok)
  }

  /**
   * This is to fix the Windows default cp-1252 character encoding that may potentially cause
   * compilation error
   */
  tasks {
    compileJava {
      options.encoding = "UTF-8"
      options.compilerArgs.add("-parameters")

      /** Enforces google-java-format at Java compilation. */
      dependsOn("spotlessApply")
    }

    compileTestJava { options.encoding = "UTF-8" }

    javadoc { options.encoding = "UTF-8" }

    test {
      useJUnitPlatform()
      systemProperty(
          "junit.jupiter.testclass.order.default",
          "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation")

      exclude("**/AnchorPlatformCustodyEnd2EndTest**")
      exclude("**/AnchorPlatformCustodyApiRpcEnd2EndTest**")

      testLogging {
        events("SKIPPED", "FAILED")
        showExceptions = true
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
      }
    }

    processResources {
      doFirst {
        val existingFile = file("$buildDir/resources/main/metadata.properties")
        existingFile.delete()
      }
      filesMatching("**/metadata.properties") {
        // This is to replace the %APP_VERSION_TOKEN% in the metadata.properties file.
        filter { line -> line.replace("%APP_VERSION_TOKEN%", rootProject.version.toString()) }
      }
    }
  }

  configurations {
    all {
      exclude(group = "ch.qos.logback", module = "logback-classic")
      exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
      exclude(group = "org.slf4j", module = "slf4j-log4j12")
      exclude(group = "org.slf4j", module = "slf4j-simple")
      exclude(group = "commons-logging", module = "commons-logging")
     }
  }

  tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  }
}

allprojects {
  group = "org.stellar.anchor-sdk"
  version = "3.1.2"

  tasks.jar {
    manifest {
      attributes(
          mapOf(
              "Implementation-Title" to project.name, "Implementation-Version" to project.version))
    }
  }
}

// *******************************************************************************
// print the gradle script usages
tasks.register("printUsage") {
  doLast {
    val green = "\u001B[32m"
    val bold = "\u001B[1m"
    // ANSI escape code to reset
    val reset = "\u001B[0m"
    println(
        """
                  ${green}${bold}Usage: ./gradlew <task>${reset}
                  
                  Available custom tasks:
                    - ${bold}printVersionName${reset}: Prints the version of the project.
                    - ${bold}updateGitHook${reset}: Updates the git hook to format the code before committing.
                    - ${bold}startAllServers${reset}: Starts all the servers based on the `default` test configuration.
                    - ${bold}startServersWithTestProfile${reset}: Starts the servers based on the test configuration specified by the TEST_PROFILE_NAME environment variable.
                    - ${bold}dockerComposeStart${reset}: Runs docker-compose up to start Postgres, Kafka, etc.
                    - ${bold}dockerComposeStop${reset}: Runs docker-compose down to stop Postgres, Kafka, etc.
                    - ${bold}anchorTest${reset}: Runs stellar anchor tests. Set `TEST_HOME_DOMAIN` and `TEST_SEPS` environment variables to customize the tests.
                    
    """
            .trimIndent())
  }
}

defaultTasks("printUsage")
