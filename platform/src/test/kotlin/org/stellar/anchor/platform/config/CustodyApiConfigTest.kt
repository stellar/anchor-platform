package org.stellar.anchor.platform.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.validation.BindException
import org.springframework.validation.Errors
import org.stellar.anchor.auth.AuthConfig
import org.stellar.anchor.auth.AuthType.JWT
import org.stellar.anchor.auth.AuthType.NONE
import org.stellar.anchor.config.CustodySecretConfig

class CustodyApiConfigTest {

  private lateinit var config: CustodyApiConfig
  private lateinit var secretConfig: CustodySecretConfig
  private lateinit var errors: Errors

  @BeforeEach
  fun setUp() {
    secretConfig = mockk()
    every { secretConfig.custodyAuthSecret } returns "testCustodyApiSecrettestCustodyApiSecret"
    config = CustodyApiConfig(secretConfig)
    config.baseUrl = "https://test.com"
    val authConfig = AuthConfig()
    authConfig.type = JWT
    config.auth = authConfig
    config.httpClient = HttpClientConfig(10, 30, 30, 60)
    errors = BindException(config, "config")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty host_url`(url: String?) {
    config.baseUrl = url
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-base-url-empty")
  }

  @ParameterizedTest
  @ValueSource(strings = ["https://custody.com", "https://custody.org:8080"])
  fun `test valid host_url`(url: String) {
    config.baseUrl = url
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(strings = ["https ://custody.com", "custody.com", "abc"])
  fun `test invalid host_url`(url: String) {
    config.baseUrl = url
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-base-url-invalid")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty api_key`(apiKey: String?) {
    every { secretConfig.custodyAuthSecret } returns apiKey
    config.validate(config, errors)
    assertErrorCode(errors, "empty-secret-custody-server-secret")
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = [""])
  fun `test empty api_key with NONE auth type`(apiKey: String?) {
    every { secretConfig.custodyAuthSecret } returns apiKey
    config.auth.type = NONE
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @Test
  fun `test valid api_key`() {
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_connect_timeout`(connectTimeout: Int) {
    config.httpClient.connectTimeout = connectTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_connect_timeout`(connectTimeout: Int) {
    config.httpClient.connectTimeout = connectTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-http-client-connect-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_read_timeout`(readTimeout: Int) {
    config.httpClient.readTimeout = readTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_read_timeout`(readTimeout: Int) {
    config.httpClient.readTimeout = readTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-http-client-read-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_write_timeout`(writeTimeout: Int) {
    config.httpClient.writeTimeout = writeTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_write_timeout`(writeTimeout: Int) {
    config.httpClient.writeTimeout = writeTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-http-client-write-timeout-invalid")
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1, Int.MAX_VALUE])
  fun `test valid http_client_call_timeout`(callTimeout: Int) {
    config.httpClient.callTimeout = callTimeout
    config.validate(config, errors)
    assertFalse(errors.hasErrors())
  }

  @ParameterizedTest
  @ValueSource(ints = [-1, Int.MIN_VALUE])
  fun `test invalid http_client_call_timeout`(callTimeout: Int) {
    config.httpClient.callTimeout = callTimeout
    config.validate(config, errors)
    assertErrorCode(errors, "custody-server-http-client-call-timeout-invalid")
  }

  @Test
  fun `validate JWT`() {
    every { secretConfig.custodyAuthSecret }.returns("tooshort")
    config.setAuth(
      AuthConfig(
        JWT,
        null,
        AuthConfig.JwtConfig("30000", "Authorization"),
        AuthConfig.ApiKeyConfig("X-Api-Key")
      )
    )
    config.validate(config, errors)
    Assertions.assertTrue(errors.hasErrors())
    assertErrorCode(errors, "hmac-weak-secret")
  }
}
