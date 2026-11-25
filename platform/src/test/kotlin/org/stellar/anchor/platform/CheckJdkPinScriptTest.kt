package org.stellar.anchor.platform

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scheduled stale-pin check (HackerOne #4215161): `scripts/check-jdk-pin.sh` compares the
 * Dockerfile pin with the latest GA Temurin 17 release, and a weekly workflow runs it.
 */
class CheckJdkPinScriptTest {
  private val script = File(repoRoot(), "scripts/check-jdk-pin.sh").path

  private fun run(vararg args: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    val builder = ProcessBuilder(listOf("bash", script) + args).redirectErrorStream(true)
    builder.environment().putAll(env)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timed out")
    return process.exitValue() to output
  }

  @Test
  fun `exits 0 when the pin equals the latest release`() {
    val (exit, output) = run("jdk-17.0.21.1+2", "jdk-17.0.21.1+2")
    assertEquals(0, exit, output)
  }

  @Test
  fun `exits 1 and prints both releases when the pin differs from the latest`() {
    val (exit, output) = run("jdk-17.0.21.1+2", "jdk-17.0.22+5")
    assertEquals(1, exit, output)
    assertTrue(output.contains("jdk-17.0.21.1+2"), output)
    assertTrue(output.contains("jdk-17.0.22+5"), output)
  }

  @Test
  fun `exits 2 when the latest release is empty`() {
    val (exit, output) = run("jdk-17.0.21.1+2", "")
    assertEquals(2, exit, output)
    assertTrue(output.contains("could not read the latest release"), output)
  }

  @Test
  fun `exits 2 when the latest release cannot be fetched`() {
    val (exit, output) =
      run(
        "jdk-17.0.21.1+2",
        env = mapOf("ADOPTIUM_RELEASE_NAMES_URL" to "http://127.0.0.1:1/unreachable"),
      )
    assertEquals(2, exit, output)
    assertTrue(output.contains("could not read the latest release"), output)
  }

  @Test
  fun `the weekly workflow runs the script on a schedule and on demand`() {
    val workflow =
      YAMLMapper().readTree(File(repoRoot(), ".github/workflows/jdk-pin-check.yml").readText())

    val cron = workflow["on"]["schedule"][0]["cron"].asText()
    assertTrue(Regex("""^\d+ \d+ \* \* \d$""").matches(cron), "not a weekly cron: $cron")
    assertTrue(workflow["on"].has("workflow_dispatch"))

    val commands =
      workflow["jobs"].elements().asSequence().flatMap { job ->
        job["steps"].elements().asSequence().mapNotNull { it["run"]?.asText() }
      }
    assertTrue(commands.any { it.contains("scripts/check-jdk-pin.sh") }, "no step runs the script")
  }
}
