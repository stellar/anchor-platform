package org.stellar.anchor.util

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerStartScriptTest {

  private fun startScript(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile
    }
    requireNotNull(dir) { "repository root (settings.gradle.kts) not found" }
    return File(dir, "scripts/docker-start.sh")
  }

  @Test
  fun `test docker-start launches the JVM with ExitOnOutOfMemoryError before JVM_FLAGS and before -jar`() {
    val javaLines = startScript().readLines().filter { it.trimStart().startsWith("java ") }
    assertEquals(1, javaLines.size, "expected exactly one java line, got $javaLines")
    val line = javaLines.single()

    val flag = line.indexOf("-XX:+ExitOnOutOfMemoryError")
    val jvmFlags = line.indexOf("\$JVM_FLAGS")
    val jar = line.indexOf("-jar")
    assertTrue(flag >= 0, "flag missing: $line")
    assertTrue(
      flag < jvmFlags,
      "flag must come before \$JVM_FLAGS so operators can override: $line"
    )
    assertTrue(jvmFlags < jar, "\$JVM_FLAGS must come before -jar: $line")
  }
}
