package org.stellar.anchor.platform.configurator

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * ANCHOR-1576 (HackerOne 4235840): the JVM decodes environment values with the locale's charset
 * before the platform reads them. In a C locale every non-ASCII byte of a secret becomes U+FFFD.
 * The decoding happens once, at JVM start, so these tests start a child JVM.
 */
class EnvironmentDecodingTest {
  @TempDir lateinit var tmp: File

  /** 25 Cyrillic letters (50 UTF-8 bytes), the report's secret. */
  private val cyrillic = (0x0430 until 0x0430 + 25).toList()

  private val probeSource =
    """
    public class Probe {
      public static void main(String[] args) {
        String value = System.getenv("PROBE_SECRET");
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> out.append(Integer.toHexString(cp)).append(' '));
        System.out.print(out.toString().trim());
      }
    }
    """
      .trimIndent()

  /** The Dockerfile's final stage: everything after its last `FROM`. */
  private fun dockerfileFinalStage(): String {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "Dockerfile").isFile) dir = dir.parentFile
    assertNotNull(dir, "Dockerfile not found above ${System.getProperty("user.dir")}")
    val lines = File(dir, "Dockerfile").readLines()
    return lines.drop(lines.indexOfLast { it.startsWith("FROM ") }).joinToString("\n")
  }

  private fun dockerfileLang(): String? =
    Regex("^ENV\\s+LANG=(\\S+)\\s*$", RegexOption.MULTILINE)
      .find(dockerfileFinalStage())
      ?.groupValues
      ?.get(1)

  /**
   * Starts a JVM with exactly [locale] in its environment (no PATH, no other locale variable) and
   * the 25 Cyrillic letters in PROBE_SECRET, and returns the code points it reads back.
   *
   * The value is written into the child's environment by `sh` from octal escapes. Passing it
   * through [ProcessBuilder.environment] would encode it with this JVM's own charset, which is the
   * defect under test.
   */
  private fun decodeInChildJvm(locale: Map<String, String>): List<Int> {
    val probe = File(tmp, "Probe.java").also { it.writeText(probeSource) }
    val octal =
      cyrillic.joinToString("") { cp ->
        String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).joinToString("") { b ->
          "\\%03o".format(b.toInt() and 0xff)
        }
      }
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val assignments = locale.entries.joinToString(" ") { "${it.key}=${it.value}" }
    val script = "exec env -i $assignments PROBE_SECRET=\"\$(printf '$octal')\" '$java' '$probe'"
    val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.US_ASCII).trim()
    assertEquals(0, process.waitFor(), "child JVM failed: $output")
    return output.split(" ").map { it.toInt(16) }
  }

  @Test
  fun `test the image sets LANG to C UTF-8 in its final stage`() {
    assertEquals("C.UTF-8", dockerfileLang())
  }

  @Test
  fun `test a JVM with the image locale reads the 25 letters as typed`() {
    val lang = dockerfileLang()
    assertNotNull(lang, "the final stage of the Dockerfile sets no LANG")

    assertEquals(cyrillic, decodeInChildJvm(mapOf("LANG" to lang)))
  }

  @Test
  fun `test a JVM in a C locale reads the 25 letters as 50 replacement characters`() {
    assertEquals(List(50) { 0xFFFD }, decodeInChildJvm(mapOf("LC_ALL" to "C")))
  }
}
