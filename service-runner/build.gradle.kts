import java.io.ByteArrayOutputStream

@Suppress(
    // The alias call in plugins scope produces IntelliJ false error which is suppressed here.
    "DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")

  implementation(libs.commons.cli)
  implementation(libs.coroutines.core)
  implementation(libs.docker.compose.rule)
  implementation(libs.google.gson)
  implementation(libs.kotlin.serialization.json)

  // From projects
  implementation(project(":api-schema"))
  implementation(project(":lib-util"))
  implementation(project(":core"))
  implementation(project(":platform"))
  implementation(project(":kotlin-reference-server"))
  implementation(project(":wallet-reference-server"))
}

tasks { bootJar { archiveBaseName.set("anchor-platform-runner") } }

application { mainClass.set("org.stellar.anchor.platform.ServiceRunner") }

/** Start all the servers based on the `default` test configuration. */
tasks.register<JavaExec>("startAllServers") {
  group = "application"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("org.stellar.anchor.platform.run_profiles.RunAllServers")
  
  doLast {
    println("Starting all servers based on the `default` test configuration.")
  }
}

/**
 * Start all the servers based on the test configuration specified by the TEST_PROFILE_NAME
 * envionrment variable.
 */
tasks.register<JavaExec>("startServersWithTestProfile") {
  group = "application"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("org.stellar.anchor.platform.run_profiles.RunTestProfile")
  
  doLast {
    println("Starting the servers based on the test configuration specified by the TEST_PROFILE_NAME environment variable.")
  }
}

/** Run docker compose up to start Postgres, Kafka, etc. */
tasks.register<JavaExec>("dockerComposeStart") {
  group = "application"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("org.stellar.anchor.platform.run_profiles.RunDockerDevStackNoWait")
  
  doLast {
    println("Running docker compose to start Postgres, Kafka ,etc.")
  }
}

tasks.register<Exec>("dockerComposeStop") {
  val outputStream = ByteArrayOutputStream()
  standardOutput = outputStream
  // Lists all running Docker containers with their labels
  commandLine("bash", "-c", "docker compose ls | awk 'NR>1 {print $1}'")

  doLast {
    // Captures the output of the command
    // Convert the output stream to a String
    val outputString = outputStream.toString()

    // Get the list of projects
    val projectNames =
        outputString.lineSequence().filter { it.isNotBlank() }.map { it.split(Regex("\\s+"))[0] }
    // Iterate over each project name and process it
    projectNames.forEach { projectName ->
      exec {
        println("docker compose -p $projectName down")
        commandLine("bash", "-c", "docker compose -p $projectName down")
      }
    }
  }
}
