package org.stellar.anchor.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The operator mitigation for CVE-2026-114938 (HackerOne #4215161): the documented JVM option, its
 * acceptance by the JVM, and the image start script passing `JVM_FLAGS` to `java`.
 */
class JvmFlagDocsTest {
  companion object {
    private const val FLAG = "-XX:-UseBASE64Intrinsics"
  }

  private fun run(command: List<String>, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    val builder = ProcessBuilder(command).redirectErrorStream(true)
    builder.environment().putAll(env)
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timed out: $command")
    return process.exitValue() to output
  }

  @Test
  fun `README documents the JVM_FLAGS setting, the affected range and the start script`() {
    val readme = File(repoRoot(), "README.md").readText()
    assertTrue(readme.contains("JVM_FLAGS=$FLAG"), "README lacks JVM_FLAGS=$FLAG")
    assertTrue(readme.contains("17.0.6"), "README lacks the first affected JDK 17.0.6")
    assertTrue(readme.contains("17.0.21.1"), "README lacks the fixed JDK 17.0.21.1")
    assertTrue(readme.contains("scripts/docker-start.sh"), "README lacks the start script name")
  }

  @Test
  fun `a JVM started with the documented flag exits 0 and reports no unrecognized option`() {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val (exit, output) = run(listOf(java, FLAG, "-version"))
    assertEquals(0, exit, output)
    assertFalse(output.contains("Unrecognized VM option"), output)
  }

  @Test
  fun `the start script passes JVM_FLAGS to java before -jar`(@TempDir bin: File) {
    val fakeJava = File(bin, "java")
    fakeJava.writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\"\n")
    Files.setPosixFilePermissions(fakeJava.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

    val script = File(repoRoot(), "scripts/docker-start.sh").path
    val (exit, output) =
      run(
        listOf("bash", script, "--sep-server"),
        mapOf("JVM_FLAGS" to FLAG, "PATH" to "${bin.path}:${System.getenv("PATH")}"),
      )

    assertEquals(0, exit, output)
    assertEquals("$FLAG -jar /app/anchor-platform-runner.jar --sep-server", output.trim())
  }
}
