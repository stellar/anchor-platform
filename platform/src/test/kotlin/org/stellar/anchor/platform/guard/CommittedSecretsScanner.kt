package org.stellar.anchor.platform.guard

import java.io.File

/**
 * Finds secrets that must not be committed to files that ship (jar resources, Helm charts, example
 * deployments). A finding names the file, the line and what was found, never the secret itself.
 */
object CommittedSecretsScanner {
  /** A Stellar secret seed: `S` and 55 base32 characters, not part of a longer base32 run. */
  private val SEED = Regex("(?<![A-Z2-7])S[A-Z2-7]{55}(?![A-Z2-7])")

  /** A key of the fake secret store whose value is a secret. */
  private val SECRET_KEY =
    Regex("""^\s*"?([A-Za-z0-9_]*(?:_SECRET|_SEED|_JWT_KEY))"?\s*:\s*(.*)$""")

  private val SKIPPED_DIRECTORIES = setOf("build", ".gradle", ".git", "node_modules")

  /** The trees that ship: every module's `src/main`, and the chart and example directories. */
  private fun scannedRoots(root: File): List<File> {
    val modules =
      root
        .listFiles { f -> f.isDirectory && f.name !in SKIPPED_DIRECTORIES }!!
        .map { File(it, "src/main") }
    val shipped =
      listOf("helm-charts", "docs/resources/deployment-examples", "quick-run").map {
        File(root, it)
      }
    return (modules + shipped).filter { it.isDirectory }
  }

  /**
   * The files to look at under [dir]. In a git checkout these are the tracked files and the new
   * files git does not ignore, so a developer's own ignored `.env` is not reported; without git the
   * directory is walked.
   */
  private fun textFiles(root: File, dir: File): Sequence<File> {
    val listed = gitFiles(root, dir)
    val files =
      listed?.asSequence()
        ?: dir.walkTopDown().onEnter { it.name !in SKIPPED_DIRECTORIES }.filter { it.isFile }
    return files.filter { it.isFile && !isBinary(it) }
  }

  private fun gitFiles(root: File, dir: File): List<File>? =
    try {
      val process =
        ProcessBuilder(
            "git",
            "-C",
            root.path,
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            dir.relativeTo(root).path,
          )
          .redirectErrorStream(false)
          .start()
      val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
      process.errorStream.readBytes()
      if (process.waitFor() != 0) null
      else output.split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }
    } catch (e: java.io.IOException) {
      null
    }

  private fun isBinary(file: File): Boolean =
    file.inputStream().use { input -> input.readNBytes(8192).any { it == 0.toByte() } }

  fun scan(root: File): List<String> {
    val findings = mutableListOf<String>()
    scannedRoots(root)
      .flatMap { textFiles(root, it).toList() }
      .forEach { file ->
        val name = file.relativeTo(root).path
        val isSecretStore = name.startsWith("helm-charts/secret-store/templates/")
        file.readLines().forEachIndexed { index, line ->
          if (SEED.containsMatchIn(line)) {
            findings += "$name:${index + 1}: a Stellar secret seed is committed"
          }
          if (isSecretStore) {
            SECRET_KEY.find(line)?.let { match ->
              val value = match.groupValues[2].trim().trimEnd(',').trim('"')
              if (value.isNotEmpty() && !value.contains("{{")) {
                findings += "$name:${index + 1}: ${match.groupValues[1]} has a literal value"
              }
            }
          }
        }
      }
    return findings
  }

  fun assertNoCommittedSecrets(root: File) {
    val findings = scan(root)
    if (findings.isNotEmpty()) {
      throw AssertionError(
        "Secrets must not be committed; generate them at deploy time instead:\n" +
          findings.joinToString("\n")
      )
    }
  }
}
