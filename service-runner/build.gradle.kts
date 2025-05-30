import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
import com.bmuschko.gradle.docker.tasks.container.DockerLogsContainer
import com.bmuschko.gradle.docker.tasks.container.DockerStartContainer
import com.bmuschko.gradle.docker.tasks.image.DockerPullImage
import java.io.ByteArrayOutputStream

@Suppress(
    // The alias call in plugins scope produces IntelliJ false error which is suppressed here.
    "DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spring.dependency.management)
  alias(libs.plugins.kotlin.jvm)
  id("com.bmuschko.docker-remote-api") version "9.3.7"
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

val dockerPullAnchorTest by
    tasks.register<DockerPullImage>("pullDockerImage") {
      group = "docker"
      image.set("stellar/anchor-tests:latest")
      
      doLast {
        println("Pulling the docker image.")
      }
    }

val dockerCreateAnchorTest by
    tasks.register<DockerCreateContainer>("dockerCreatePullAnchorTest") {
      group = "docker"
      dependsOn(dockerPullAnchorTest)
      targetImageId { dockerPullAnchorTest.image.get() }
      
      // These values need to be captured during configuration time to set up the container properly
      val homeDomain = System.getenv("TEST_HOME_DOMAIN") ?: "http://host.docker.internal:8080"
      val seps = System.getenv().getOrDefault("TEST_SEPS", "1,6,10,12,24,31,38").split(",")
      val configPath = "${project.projectDir}/../platform/src/test/resources"
      
      hostConfig.autoRemove.set(true)
      hostConfig.binds.set(mutableMapOf(configPath to "/config"))
      hostConfig.network.set("host")

      val cmdList = mutableListOf("--home-domain", homeDomain, "--seps")
      cmdList.addAll(seps)
      cmdList.addAll(
          listOf("--sep-config", "/config/stellar-anchor-tests-sep-config.json", "--verbose"))

      cmd.set(cmdList)
      
      doLast {
        println("Creating the docker container.")
        println("TEST_HOME_DOMAIN=$homeDomain")
        println("TEST_SEPS=$seps")
      }
    }

val dockerStartAnchorTest by
    tasks.register<DockerStartContainer>("dockerStartAnchorTest") {
      group = "docker"
      dependsOn(dockerCreateAnchorTest)
      targetContainerId(dockerCreateAnchorTest.containerId)
      
      doLast {
        println("Starting the docker container.")
      }
    }

val anchorTest by
    tasks.register<DockerLogsContainer>("anchorTest") {
      group = "docker"
      dependsOn(dockerStartAnchorTest)
      targetContainerId(dockerCreateAnchorTest.containerId)
      follow.set(true)
      tailAll.set(true)
      
      doLast {
        println("Running the docker container.")
      }
    }
