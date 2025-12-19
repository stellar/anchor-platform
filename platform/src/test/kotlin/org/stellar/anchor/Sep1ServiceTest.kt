package org.stellar.anchor

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.SepException
import org.stellar.anchor.config.Sep1Config.TomlType.*
import org.stellar.anchor.platform.config.PropertySep1Config
import org.stellar.anchor.platform.config.PropertySep1Config.TomlConfig
import org.stellar.anchor.platform.config.Sep1ConfigTest
import org.stellar.anchor.sep1.Sep1Service

class Sep1ServiceTest {
  lateinit var sep1: Sep1Service
  private val stellarToml =
    """
          ACCOUNTS = [ "GCSGSR6KQQ5BP2FXVPWRL6SWPUSFWLVONLIBJZUKTVQB5FYJFVL6XOXE" ]
          VERSION = "0.1.0"
          SIGNING_KEY = "GBDYDBJKQBJK4GY4V7FAONSFF2IBJSKNTBYJ65F5KCGBY2BIGPGGLJOH"
          NETWORK_PASSPHRASE = "Test SDF Network ; September 2015"
          
          WEB_AUTH_ENDPOINT = "http://localhost:8080/auth"
          KYC_SERVER = "http://localhost:8080/sep12"
          TRANSFER_SERVER_SEP0024 = "http://localhost:8080/sep24"
          DIRECT_PAYMENT_SERVER = "http://localhost:8080/sep31"
          ANCHOR_QUOTE_SERVER = "http://localhost:8080/sep38"
          
          [[CURRENCIES]]
          code = "SRT"
          issuer = "GCDNJUBQSX7AJWLJACMJ7I4BC3Z47BQUTMHEICZLE6MU4KQBRYG5JY6B"
          status = "test"
          is_asset_anchored = false
          anchor_asset_type = "other"
          desc = "A fake anchored asset to use with this example anchor server."
          
          [DOCUMENTATION]
          ORG_NAME = "Stellar Development Foundation"
          ORG_URL = "https://stellar.org"
          ORG_DESCRIPTION = "SEP 24 reference server."
          ORG_KEYBASE = "stellar.public"
          ORG_TWITTER = "StellarOrg"
          ORG_GITHUB = "stellar"
        """
      .trimIndent()

  @Test
  fun `test Sep1Service reading toml from inline string`() {
    val config = PropertySep1Config(true, TomlConfig(STRING, stellarToml))
    sep1 = Sep1Service(config)
    assertEquals(sep1.toml, stellarToml)
  }

  @Test
  fun `test Sep1Service reading toml from file`() {
    val config = PropertySep1Config(true, TomlConfig(FILE, Sep1ConfigTest.getTestTomlAsFile()))
    sep1 = Sep1Service(config)
    assertEquals(sep1.toml, Files.readString(Path.of(Sep1ConfigTest.getTestTomlAsFile())))
  }

  @Test
  fun `getStellarToml fetches data during re-direct`() {
    val mockServer = MockWebServer()
    mockServer.start()
    val mockAnchorUrl = mockServer.url("").toString()
    mockServer.enqueue(MockResponse().setBody(stellarToml))
    val config = PropertySep1Config(true, TomlConfig(URL, mockAnchorUrl))
    sep1 = Sep1Service(config)
    assertEquals(sep1.toml, stellarToml)
  }

  // this test is not expected to raise an exception. given the re-direct to a malicious
  // endpoint still returns a 200 the exception will be raised/obfuscated
  // when the toml is parsed.
  @Test
  fun `getStellarTomlthrows exception when redirect is encountered`() {
    val mockServer = MockWebServer()
    mockServer.start()
    val mockAnchorUrl = mockServer.url("").toString()
    val metadata =
      """
  {
    "ami-id": "ami-12345678",
    "instance-id": "i-1234567890abcdef",
    "instance-type": "t2.micro"
    // ... other metadata ...
  }
"""
        .trimIndent()

    // Enqueue a response with a 302 status and a Location header to simulate a redirect.
    mockServer.enqueue(
      MockResponse()
        .setResponseCode(302)
        .setHeader("Location", mockServer.url("/new_location").toString())
    )

    // Enqueue a response at the redirect location that simulates AWS metadata leak.
    mockServer.enqueue(MockResponse().setResponseCode(200).setBody(metadata))

    val config = PropertySep1Config(true, TomlConfig(URL, mockAnchorUrl))
    val sep1 = Sep1Service(config)
    assertEquals(sep1.getToml(), metadata)
    mockServer.shutdown()
  }

  @Test
  fun `getStellarToml throws exception when redirected location results in error`() {
    val mockServer = MockWebServer()
    mockServer.start()
    val mockAnchorUrl = mockServer.url("/new_location").toString()

    // Enqueue a response that provides a server error.
    mockServer.enqueue(MockResponse().setResponseCode(500))

    val config = PropertySep1Config(true, TomlConfig(URL, mockAnchorUrl))
    val sep1Service = Sep1Service(config)
    val exception = assertThrows(SepException::class.java) { sep1Service.toml }
    assertEquals(exception.message, "Unable to read SEP-1 value")
    mockServer.shutdown()
  }
}
