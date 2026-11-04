package org.stellar.anchor.platform.guard

import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.stellar.sdk.KeyPair

class CommittedSecretsGuardTest {
  @TempDir lateinit var tmp: Path

  private fun newSeed() = String(KeyPair.random().secretSeed)

  private fun write(relativePath: String, content: String): File =
    File(tmp.toFile(), relativePath).also {
      it.parentFile.mkdirs()
      it.writeText(content)
    }

  private fun scan() = CommittedSecretsScanner.scan(tmp.toFile())

  @Test
  fun `test a seed in a scanned file fails naming file and line without the seed`() {
    val seed = newSeed()
    write("service/src/main/resources/profile.env", "a=1\nsecret.sep10.signing_seed=$seed\n")

    val error =
      assertFailsWith<AssertionError> {
        CommittedSecretsScanner.assertNoCommittedSecrets(tmp.toFile())
      }

    assertTrue(error.message!!.contains("service/src/main/resources/profile.env:2"), error.message)
    assertFalse(error.message!!.contains(seed), "the message must not print the seed")
  }

  @Test
  fun `test a seed is flagged in every scanned tree and ignored elsewhere`() {
    val scanned =
      listOf(
        "service/src/main/resources/a.env",
        "helm-charts/chart/values.yaml",
        "docs/resources/deployment-examples/example/config.yaml",
        "quick-run/docker-compose.yaml",
      )
    val ignored =
      listOf("service/src/test/resources/a.env", "docs/other/config.yaml", ".github/workflow.yml")
    scanned.forEach { write(it, "seed: ${newSeed()}\n") }
    ignored.forEach { write(it, "seed: ${newSeed()}\n") }

    val flagged = scan().map { it.substringBefore(":") }.toSet()

    assertEquals(scanned.toSet(), flagged)
  }

  @Test
  fun `test a 56 character seed token is flagged`() {
    val seed = newSeed()
    assertEquals(56, seed.length)
    listOf("$seed", "key=\"$seed\"", "key: $seed # note", "  - $seed,").forEach { line ->
      write("service/src/main/resources/f.txt", "$line\n")
      assertEquals(1, scan().size, "expected a finding for: ${line.replace(seed, "<seed>")}")
    }
  }

  @Test
  fun `test a 55 character token is not flagged`() {
    val shorter = newSeed().dropLast(1)
    assertEquals(55, shorter.length)
    write("service/src/main/resources/f.txt", "key=$shorter\n")

    assertEquals(emptyList(), scan())
  }

  @Test
  fun `test a run inside a longer base32 token is not flagged`() {
    val seed = newSeed()
    write(
      "service/src/main/resources/f.txt",
      listOf("MB${seed}AAAAAAAAAAAA", "${seed}A", "A$seed").joinToString("\n") + "\n",
    )

    assertEquals(emptyList(), scan())
  }

  @Test
  fun `test a literal secret in the secret store chart is flagged`() {
    val path = "helm-charts/secret-store/templates/store.yaml"
    write(
      path,
      """
      "SEP10_JWT_SECRET": "not-a-template-value",
      "SEP10_SIGNING_SEED": "{{ .Values.seed }}",
      "SEP24_INTERACTIVE_JWT_KEY": "literal-key",
      "POSTGRES_PASSWORD": "123456789",
      "SENTRY_AUTH_TOKEN": {{ default "\"\"" .Values.token }}
      """
        .trimIndent() + "\n",
    )

    val findings = scan()

    assertEquals(
      listOf(
        "$path:1: SEP10_JWT_SECRET has a literal value",
        "$path:3: SEP24_INTERACTIVE_JWT_KEY has a literal value",
      ),
      findings,
    )
    findings.forEach {
      assertFalse(it.contains("not-a-template-value") || it.contains("literal-key"), it)
    }
  }

  @Test
  fun `test the repository tree has no committed secrets`() {
    CommittedSecretsScanner.assertNoCommittedSecrets(File("..").canonicalFile)
  }

  @Test
  fun `test a file git ignores is not scanned`() {
    val git = ProcessBuilder("git", "-C", tmp.toString(), "init", "-q").start().waitFor()
    assumeTrue(git == 0, "git is not available")
    write(".gitignore", "local.env\n")
    write("quick-run/local.env", "seed=${newSeed()}\n")
    write("quick-run/tracked.env", "seed=${newSeed()}\n")

    assertEquals(listOf("quick-run/tracked.env"), scan().map { it.substringBefore(":") })
  }
}
