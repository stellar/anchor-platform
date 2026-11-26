package org.stellar.anchor.platform

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckJdkPinScriptTest {
  private data class Result(val exitCode: Int, val output: String)

  private val repoRoot: File =
    generateSequence(File(".").absoluteFile.canonicalFile) { it.parentFile }
      .first { File(it, "scripts/check-jdk-pin.sh").isFile }

  private val fixtures = File(repoRoot, "service-runner/src/test/resources/check-jdk-pin")

  private fun run(dockerfile: File, apiJson: File): Result {
    val process =
      ProcessBuilder(
          "bash",
          File(repoRoot, "scripts/check-jdk-pin.sh").path,
          dockerfile.path,
          apiJson.path,
        )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "script did not finish")
    return Result(process.exitValue(), output)
  }

  private fun fixture(name: String) = File(fixtures, name)

  @Test
  fun `pin equal to the latest exits 0 and prints both versions`() {
    val r = run(fixture("Dockerfile.current"), fixture("api-latest-17.0.21.1+2.json"))
    assertEquals(0, r.exitCode, r.output)
    assertTrue(r.output.contains("pinned: 17.0.21.1+2"), r.output)
    assertTrue(r.output.contains("latest: 17.0.21.1+2"), r.output)
  }

  @Test
  fun `pin 17_0_21_1+2 is ahead of latest 17_0_21+9 and exits 0`() {
    val r = run(fixture("Dockerfile.current"), fixture("api-latest-17.0.21+9.json"))
    assertEquals(0, r.exitCode, r.output)
  }

  @Test
  fun `pin 17_0_21+9 is behind latest 17_0_21_1+2 and exits 1 with both versions printed`() {
    val r = run(fixture("Dockerfile.older"), fixture("api-latest-17.0.21.1+2.json"))
    assertEquals(1, r.exitCode, r.output)
    assertTrue(r.output.contains("pinned: 17.0.21+9"), r.output)
    assertTrue(r.output.contains("latest: 17.0.21.1+2"), r.output)
  }

  @Test
  fun `build number is compared as a number, so 17_0_21+9 is behind 17_0_21+10`() {
    val r = run(fixture("Dockerfile.older"), fixture("api-latest-17.0.21+10.json"))
    assertEquals(1, r.exitCode, r.output)
    assertTrue(r.output.contains("latest: 17.0.21+10"), r.output)
  }

  @Test
  fun `release name that is not numeric exits 3 and does not claim the pin is current`() {
    val r = run(fixture("Dockerfile.older"), fixture("api-non-numeric-release.json"))
    assertEquals(3, r.exitCode, r.output)
    assertFalse(r.output.contains("up to date"), r.output)
  }

  @Test
  fun `pin that is not numeric exits 3 and does not claim the pin is current`() {
    val r = run(fixture("Dockerfile.non-numeric"), fixture("api-latest-17.0.21+9.json"))
    assertEquals(3, r.exitCode, r.output)
    assertFalse(r.output.contains("up to date"), r.output)
  }

  @Test
  fun `pin behind a newer patch release exits 1`() {
    val r = run(fixture("Dockerfile.current"), fixture("api-latest-17.0.22+5.json"))
    assertEquals(1, r.exitCode, r.output)
  }

  @Test
  fun `JDK_VER and TEMURIN_RELEASE that disagree exit 2 and name both values`() {
    val r = run(fixture("Dockerfile.mismatch"), fixture("api-latest-17.0.21.1+2.json"))
    assertEquals(2, r.exitCode, r.output)
    assertTrue(r.output.contains("17.0.21.1_2"), r.output)
    assertTrue(r.output.contains("jdk-17.0.21+9"), r.output)
  }

  @Test
  fun `empty API response exits 3 and does not claim the pin is current`() {
    val r = run(fixture("Dockerfile.current"), fixture("api-empty.json"))
    assertEquals(3, r.exitCode, r.output)
    assertFalse(r.output.contains("up to date"), r.output)
  }

  @Test
  fun `API response without a release name exits 3`() {
    val r = run(fixture("Dockerfile.current"), fixture("api-no-release-name.json"))
    assertEquals(3, r.exitCode, r.output)
    assertFalse(r.output.contains("up to date"), r.output)
  }

  @Test
  fun `unreadable API file exits 3`() {
    val r = run(fixture("Dockerfile.current"), fixture("does-not-exist.json"))
    assertEquals(3, r.exitCode, r.output)
    assertFalse(r.output.contains("up to date"), r.output)
  }

  @Test
  fun `the repository Dockerfile pins one consistent build`() {
    val r = run(File(repoRoot, "Dockerfile"), fixture("api-latest-17.0.21.1+2.json"))
    assertTrue(r.exitCode == 0 || r.exitCode == 1, "exit ${r.exitCode}: ${r.output}")
    assertTrue(r.output.contains("pinned: "), r.output)
  }
}
