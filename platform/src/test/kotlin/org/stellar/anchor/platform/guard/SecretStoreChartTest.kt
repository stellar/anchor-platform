package org.stellar.anchor.platform.guard

import com.google.gson.Gson
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.stellar.anchor.platform.utils.PublishedLiterals
import org.yaml.snakeyaml.Yaml

/** Guards the fake Helm secret store: it must not publish authentication secrets. */
class SecretStoreChartTest {
  private val chartDir = File("../helm-charts/secret-store")

  /** chart value name -> the key it fills in `anchor-platform-secrets` */
  private val platformSecrets =
    linkedMapOf(
      "sep10_jwt_secret" to "SEP10_JWT_SECRET",
      "sep24_interactive_url_jwt_secret" to "SEP24_INTERACTIVE_URL_JWT_SECRET",
      "sep24_more_info_url_jwt_secret" to "SEP24_MORE_INFO_URL_JWT_SECRET",
      "sep6_more_info_url_jwt_secret" to "SEP6_MORE_INFO_URL_JWT_SECRET",
      "sep45_jwt_secret" to "SEP45_JWT_SECRET",
      "callback_api_auth_secret" to "CALLBACK_API_AUTH_SECRET",
      "platform_api_auth_secret" to "PLATFORM_API_AUTH_SECRET",
    )

  private val freshValues =
    platformSecrets.keys.associateWith { "fresh-$it-0123456789abcdef0123456789abcdef" }

  private class Render(val exitCode: Int, val stdout: String, val stderr: String)

  private fun helmAvailable(): Boolean =
    try {
      ProcessBuilder("helm", "version").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: java.io.IOException) {
      false
    }

  private fun render(values: Map<String, String>): Render {
    assumeTrue(helmAvailable(), "helm is not installed; the chart rendering cannot be checked")
    val command = mutableListOf("helm", "template", "secret-store", chartDir.path)
    values.forEach { (name, value) ->
      command += "--set-string"
      command += "$name=${value.replace("\\", "\\\\").replace(",", "\\,")}"
    }
    val process = ProcessBuilder(command).start()
    val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
    val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
    return Render(process.waitFor(), stdout, stderr)
  }

  /** Rendered secret documents: store key suffix -> the JSON object of the secret. */
  @Suppress("UNCHECKED_CAST")
  private fun renderedDocuments(values: Map<String, String>): Map<String, Map<String, String>> {
    val rendered = render(values)
    assertEquals(0, rendered.exitCode, rendered.stderr)
    val store = Yaml().load<Map<String, Any>>(rendered.stdout)
    val data =
      ((store["spec"] as Map<String, Any>)["provider"] as Map<String, Any>)["fake"]
        as Map<String, Any>
    return (data["data"] as List<Map<String, String>>).associate {
      it.getValue("key").substringAfterLast("/") to
        Gson().fromJson(it.getValue("value"), Map::class.java) as Map<String, String>
    }
  }

  @Test
  fun `test the chart file defines no literal for the seven secrets`() {
    val text = File(chartDir, "templates/secretstore.yaml").readText()
    platformSecrets.values.forEach { key ->
      val line = text.lines().single { it.contains("\"$key\":") }
      assertTrue(
        line.substringAfter(":").contains("{{"),
        "$key must come from a chart value: $line"
      )
    }
    PublishedLiterals.secretsByProperty.values.forEach {
      assertFalse(text.contains(it), "a published value is still in the chart file")
    }
  }

  @Test
  fun `test render fails naming each missing secret`() {
    platformSecrets.keys.forEach { missing ->
      val rendered = render(freshValues - missing)
      assertNotEquals(0, rendered.exitCode, "rendering without $missing must fail")
      assertTrue(
        rendered.stderr.contains(missing),
        "the error must name $missing: ${rendered.stderr}"
      )
    }
    platformSecrets.keys.forEach { blank ->
      val rendered = render(freshValues + (blank to "  "))
      assertNotEquals(0, rendered.exitCode, "a whitespace-only $blank must count as missing")
      assertTrue(rendered.stderr.contains(blank), "the error must name $blank: ${rendered.stderr}")
    }
  }

  @Test
  fun `test render carries each supplied value`() {
    val documents = renderedDocuments(freshValues)
    val platform = documents.getValue("anchor-platform-secrets")
    platformSecrets.forEach { (name, key) ->
      assertEquals(freshValues.getValue(name), platform[key], key)
    }

    val awkward = platformSecrets.keys.associateWith { """quote"back\slash,comma<tag>&$it""" }
    val awkwardPlatform = renderedDocuments(awkward).getValue("anchor-platform-secrets")
    platformSecrets.forEach { (name, key) ->
      assertEquals(
        awkward.getValue(name),
        awkwardPlatform[key],
        "$key keeps quotes and backslashes"
      )
    }
  }

  @Test
  fun `test reference server secrets pair with the platform secrets`() {
    val documents = renderedDocuments(freshValues)
    val platform = documents.getValue("anchor-platform-secrets")
    val reference = documents.getValue("reference-server-secrets")
    assertEquals(
      platform["SEP24_INTERACTIVE_URL_JWT_SECRET"],
      reference["SEP24_INTERACTIVE_JWT_KEY"]
    )
    assertEquals(platform["CALLBACK_API_AUTH_SECRET"], reference["PLATFORM_ANCHOR_SECRET"])
    assertEquals(platform["PLATFORM_API_AUTH_SECRET"], reference["ANCHOR_PLATFORM_SECRET"])
  }

  @Test
  fun `test README documents every secret value`() {
    val readme = File("../helm-charts/README.md").readText()
    assertTrue(
      readme.contains("openssl rand -hex 32"),
      "the README must show how to generate a value"
    )
    platformSecrets.keys.forEach {
      assertTrue(
        readme.contains("--set $it=$(openssl rand -hex 32)"),
        "the README must generate $it with openssl and pass it to helm upgrade --install",
      )
    }
  }
}
