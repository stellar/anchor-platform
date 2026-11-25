package org.stellar.anchor.platform

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Walks up from the Gradle working directory (the module folder) to the repository root. */
internal fun repoRoot(): File {
  var dir: File? = File(System.getProperty("user.dir")).absoluteFile
  while (dir != null) {
    if (File(dir, "Dockerfile").isFile && File(dir, "settings.gradle.kts").isFile) return dir
    dir = dir.parentFile
  }
  throw IllegalStateException("Repository root not found from ${System.getProperty("user.dir")}")
}

/**
 * Guards the JDK the official image ships (HackerOne #4215161, CVE-2026-114938): Temurin 17 before
 * 17.0.21.1 carries the Base64 AVX-512 intrinsic overflow.
 */
class DockerfileJdkPinTest {
  companion object {
    private val FLOOR = listOf(17, 0, 21, 1)
    private val PIN_LINE = Regex("""^ARG TEMURIN_RELEASE=(\S+)$""", RegexOption.MULTILINE)
    private val RELEASE = Regex("""^jdk-(\d+(?:\.\d+)*)\+\d+$""")

    /**
     * Numeric components of a Temurin release name such as `jdk-17.0.21.1+2`; null if unparsable.
     */
    fun versionOf(release: String): List<Int>? =
      RELEASE.matchEntire(release)?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

    /** Compares component by component, padding the shorter list with zeros. */
    fun isAtOrAboveFloor(release: String): Boolean {
      val version = versionOf(release) ?: return false
      val size = maxOf(version.size, FLOOR.size)
      for (i in 0 until size) {
        val actual = version.getOrElse(i) { 0 }
        val floor = FLOOR.getOrElse(i) { 0 }
        if (actual != floor) return actual > floor
      }
      return true
    }

    private val dockerfile: String by lazy { File(repoRoot(), "Dockerfile").readText() }
  }

  @ParameterizedTest
  @ValueSource(strings = ["jdk-17.0.21.1+2", "jdk-17.0.21.2+1", "jdk-17.0.22+5", "jdk-17.1.0+1"])
  fun `releases at or above 17_0_21_1 pass the floor`(release: String) {
    assertTrue(isAtOrAboveFloor(release), release)
  }

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "jdk-17.0.16+8", // the pin of HackerOne #4215161
        "jdk-17.0.21+9", // the release just before the fix: 17.0.21 is below 17.0.21.1
        "jdk-17.0.6+10",
        "jdk-11.0.25+9",
        "jdk-17.0.21.1", // no build number
        "17.0.21.1+2", // no jdk- prefix
        "jdk-17.0.x+2",
        "",
      ]
  )
  fun `releases below the floor or unparsable fail the floor`(release: String) {
    assertFalse(isAtOrAboveFloor(release), release)
  }

  @Test
  fun `unparsable release names have no version`() {
    assertNull(versionOf("jdk-17.0.x+2"))
    assertEquals(listOf(17, 0, 21, 1), versionOf("jdk-17.0.21.1+2"))
    assertEquals(listOf(17, 0, 21), versionOf("jdk-17.0.21+9"))
  }

  @Test
  fun `Dockerfile declares TEMURIN_RELEASE exactly once`() {
    assertEquals(1, PIN_LINE.findAll(dockerfile).count())
  }

  @Test
  fun `Dockerfile pins a JDK at or above the fixed release`() {
    val release = PIN_LINE.find(dockerfile)!!.groupValues[1]
    assertTrue(isAtOrAboveFloor(release), "Dockerfile pins $release, below jdk-17.0.21.1")
  }

  @Test
  fun `Dockerfile carries no second JDK version literal`() {
    assertFalse(Regex("""(?m)^ARG JDK_VER=""").containsMatchIn(dockerfile), "JDK_VER must derive")
    val pinned = PIN_LINE.find(dockerfile)!!.groupValues[1]
    val literals = Regex("""17\.\d+\.\d+(?:\.\d+)?""").findAll(dockerfile).map { it.value }
    val allowed = pinned.removePrefix("jdk-").substringBefore('+')
    assertTrue(literals.all { it == allowed }, "stray JDK versions: ${literals.toList()}")
  }

  @Test
  fun `JDK_VER derives from TEMURIN_RELEASE`() {
    val derivation = Regex("""JDK_VER="\$\(.*?\)"""").find(dockerfile)
    assertTrue(derivation != null, "Dockerfile has no JDK_VER derivation from TEMURIN_RELEASE")
    for ((release, expected) in
      mapOf("jdk-17.0.21.1+2" to "17.0.21.1_2", "jdk-17.0.22+5" to "17.0.22_5")) {
      val process =
        ProcessBuilder(
            "sh",
            "-c",
            "TEMURIN_RELEASE='$release'; ${derivation!!.value}; printf %s \"\$JDK_VER\"",
          )
          .redirectErrorStream(true)
          .start()
      val out = process.inputStream.bufferedReader().readText()
      assertTrue(process.waitFor(10, TimeUnit.SECONDS))
      assertEquals(expected, out)
    }
  }

  @Test
  fun `both download URLs are built from the derived version`() {
    val x64 = Regex("""amd64\|x86_64\) DOWNLOAD_URL="([^"]+)"""").find(dockerfile)!!.groupValues[1]
    val arm64 =
      Regex("""arm64\|aarch64\) DOWNLOAD_URL="([^"]+)"""").find(dockerfile)!!.groupValues[1]
    assertEquals(
      "https://github.com/adoptium/temurin17-binaries/releases/download/" +
        "\${TEMURIN_RELEASE}/OpenJDK17U-jdk_x64_linux_hotspot_\${JDK_VER}.tar.gz",
      x64,
    )
    assertEquals(
      "https://github.com/adoptium/temurin17-binaries/releases/download/" +
        "\${TEMURIN_RELEASE}/OpenJDK17U-jdk_aarch64_linux_hotspot_\${JDK_VER}.tar.gz",
      arm64,
    )
  }

  @Test
  fun `JDK install fails the build when the download or extraction fails`() {
    val block =
      Regex("""(?s)RUN JDK_VER=.*?ln -s /usr/lib/jvm/temurin-17/bin/java /usr/bin/java""")
        .find(dockerfile)
        ?.value
    assertTrue(block != null, "install RUN block not found")
    // `curl -f` fails on an HTTP error and `&&` chains every step, so one failure aborts the RUN.
    assertTrue(block!!.contains("curl -fsSL"))
    assertTrue(block.contains("&& \\\n    mkdir -p /usr/lib/jvm"))
    assertTrue(block.contains("&& \\\n    tar -xzf /tmp/openjdk17.tar.gz"))
    assertFalse(block.contains("|| true"))
    assertTrue(block.contains("*) echo \"Unsupported arch") && block.contains("exit 1"))
  }
}
