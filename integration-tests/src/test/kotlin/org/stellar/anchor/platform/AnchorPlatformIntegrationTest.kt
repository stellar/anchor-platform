package org.stellar.anchor.platform

import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.get
import org.stellar.anchor.api.callback.GetFeeRequest
import org.stellar.anchor.api.callback.GetRateRequest
import org.stellar.anchor.api.callback.GetRateRequest.Type.*
import org.stellar.anchor.api.exception.NotFoundException
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerRequest
import org.stellar.anchor.api.sep.sep38.Sep38Context.*
import org.stellar.anchor.config.AppConfig
import org.stellar.anchor.config.Sep10Config
import org.stellar.anchor.config.Sep1Config
import org.stellar.anchor.config.Sep38Config
import org.stellar.anchor.platform.callback.RestCustomerIntegration
import org.stellar.anchor.platform.callback.RestFeeIntegration
import org.stellar.anchor.platform.callback.RestRateIntegration
import org.stellar.anchor.reference.AnchorReferenceServer
import org.stellar.anchor.util.GsonUtils
import org.stellar.anchor.util.Sep1Helper

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnchorPlatformIntegrationTest {
  companion object {
    private const val SEP_SERVER_PORT = 8080
    private const val REFERENCE_SERVER_PORT = 8081
    private lateinit var toml: Sep1Helper.TomlContent
    private lateinit var jwt: String
    private val httpClient: OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()
    private val gson: Gson = GsonUtils.getInstance()
    private val rci =
      RestCustomerIntegration("http://localhost:$REFERENCE_SERVER_PORT", httpClient, gson)
    private val rri =
      RestRateIntegration("http://localhost:$REFERENCE_SERVER_PORT", httpClient, gson)
    private val rfi =
      RestFeeIntegration("http://localhost:$REFERENCE_SERVER_PORT", httpClient, gson)
    const val fiatUSD = "iso4217:USD"
    const val stellarUSDC = "stellar:USDC:GBBD47IF6LWK7P7MDEVSCWR7DPUWV3NY3DTQEVFL4NAT4AQH3ZLLFLA5"
    private lateinit var platformServerContext: ConfigurableApplicationContext
    init {
      val props = System.getProperties()
      props.setProperty("REFERENCE_SERVER_CONFIG", "classpath:/anchor-reference-server.yaml")
    }
    @BeforeAll
    @JvmStatic
    fun setup() {
      platformServerContext =
        AnchorPlatformServer.start(
          SEP_SERVER_PORT,
          "/",
          mapOf("stellar.anchor.config" to "classpath:/integration-test.anchor-config.yaml")
        )

      AnchorReferenceServer.start(REFERENCE_SERVER_PORT, "/")
    }

    @AfterAll fun tearDown() {}
  }

  private fun readSep1Toml(): Sep1Helper.TomlContent {
    val tomlString = resourceAsString("http://localhost:$SEP_SERVER_PORT/.well-known/stellar.toml")
    return Sep1Helper.parse(tomlString)
  }

  @Test
  @Order(1)
  fun runSep1Test() {
    toml = readSep1Toml()
  }

  @Test
  @Order(2)
  fun runSep10Test() {
    jwt = sep10TestAll(toml)
  }

  @Test
  @Order(3)
  fun runSep12Test() {
    sep12TestAll(toml, jwt)
  }

  @Test
  @Order(4)
  fun runSep24Test() {
    sep24TestAll(toml, jwt)
  }

  @Test
  @Order(5)
  fun runSep31Test() {
    sep31TestAll(toml, jwt)
  }

  @Test
  @Order(6)
  fun runSep38Test() {
    sep38TestAll(toml, jwt)
  }

  @Test
  @Order(7)
  fun runPlatformTest() {
    platformTestAll(toml, jwt)
  }

  @Test
  fun testCustomerIntegration() {
    assertThrows<NotFoundException> {
      rci.getCustomer(Sep12GetCustomerRequest.builder().id("1").build())
    }
  }

  @Test
  fun testRate_indicativePrices() {
    val result =
      rri.getRate(
        GetRateRequest.builder()
          .type(INDICATIVE_PRICES)
          .sellAsset(fiatUSD)
          .sellAmount("100")
          .buyAsset(stellarUSDC)
          .build()
      )
    assertNotNull(result)
    val wantBody =
      """{
      "rate":{
        "price":"1.02",
        "sell_amount": "100",
        "buy_amount": "98.0392"
      }
    }""".trimMargin()
    JSONAssert.assertEquals(wantBody, gson.toJson(result), true)
  }

  @Test
  fun testRate_indicativePrice() {
    val result =
      rri.getRate(
        GetRateRequest.builder()
          .type(INDICATIVE_PRICE)
          .context(SEP31)
          .sellAsset(fiatUSD)
          .sellAmount("100")
          .buyAsset(stellarUSDC)
          .build()
      )
    assertNotNull(result)
    val wantBody =
      """{
      "rate":{
        "total_price":"1.0303032801",
        "price":"1.0200002473",
        "sell_amount": "100",
        "buy_amount": "97.0588",
        "fee": {
          "total": "1.00",
          "asset": "$fiatUSD",
          "details": [
            {
              "name": "Sell fee",
              "description": "Fee related to selling the asset.",
              "amount": "1.00"
            }
          ]
        }
      }
    }""".trimMargin()
    JSONAssert.assertEquals(wantBody, gson.toJson(result), true)
  }

  @Test
  fun testRate_firm() {
    val rate =
      rri.getRate(
          GetRateRequest.builder()
            .type(FIRM)
            .context(SEP31)
            .sellAsset(fiatUSD)
            .buyAsset(stellarUSDC)
            .buyAmount("100")
            .build()
        )
        .rate
    assertNotNull(rate)

    // check if id is a valid UUID
    val id = rate.id
    assertDoesNotThrow { UUID.fromString(id) }
    var gotExpiresAt: Instant? = null
    val expiresAtStr = rate.expiresAt.toString()
    assertDoesNotThrow {
      gotExpiresAt = DateTimeFormatter.ISO_INSTANT.parse(rate.expiresAt.toString(), Instant::from)
    }

    val wantExpiresAt =
      ZonedDateTime.now(ZoneId.of("UTC"))
        .plusDays(1)
        .withHour(12)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
    assertEquals(wantExpiresAt.toInstant(), gotExpiresAt)

    // check if rate was persisted by getting the rate with ID
    val gotQuote = rri.getRate(GetRateRequest.builder().id(rate.id).build())
    assertEquals(rate.id, gotQuote.rate.id)
    assertEquals("1.02", gotQuote.rate.price)

    val wantBody =
      """{
      "rate":{
        "id": "$id",
        "total_price":"1.03",
        "price":"1.02",
        "sell_amount": "103",
        "buy_amount": "100",
        "expires_at": "$expiresAtStr",
        "fee": {
          "total": "1.00",
          "asset": "$fiatUSD",
          "details": [
            {
              "name": "Sell fee",
              "description": "Fee related to selling the asset.",
              "amount": "1.00"
            }
          ]
        }
      }
    }""".trimMargin()
    JSONAssert.assertEquals(wantBody, gson.toJson(gotQuote), true)
  }

  @Test
  fun testGetFee() {
    val result =
      rfi.getFee(
        GetFeeRequest.builder()
          .sendAmount("10")
          .sendAsset("USDC")
          .receiveAsset("USDC")
          .senderId("sender_id")
          .receiverId("receiver_id")
          .build()
      )

    assertNotNull(result)
    JSONAssert.assertEquals(
      gson.toJson(result),
      """{
        "fee": {
          "asset": "USDC",
          "amount": "0.30"
        }
      }""",
      true
    )
  }

  @Test
  fun testYamlProperties() {
    val tests =
      mapOf(
        "sep1.enabled" to "true",
        "sep10.enabled" to "true",
        "sep10.homeDomain" to "localhost:8080",
        "sep10.signingSeed" to "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
        "sep38.enabled" to "true",
        "sep38.quoteIntegrationEndPoint" to "http://localhost:8081",
        "payment-gateway.circle.name" to "circle",
        "payment-gateway.circle.enabled" to "true",
        "spring.jpa.properties.hibernate.dialect" to "org.hibernate.dialect.H2Dialect",
        "logging.level.root" to "INFO",
        "server.servlet.context-path" to "/"
      )

    tests.forEach { assertEquals(it.value, platformServerContext.environment[it.key]) }
  }

  @Test
  fun testAppConfig() {
    val appConfig = platformServerContext.getBean(AppConfig::class.java)
    assertEquals("Test SDF Network ; September 2015", appConfig.stellarNetworkPassphrase)
    assertEquals("http://localhost:8080", appConfig.hostUrl)
    assertEquals(listOf("en"), appConfig.languages)
    assertEquals("https://horizon-testnet.stellar.org", appConfig.horizonUrl)
    assertEquals("assets-test.json", appConfig.assets)
    assertEquals("secret", appConfig.jwtSecretKey)
  }

  @Test
  fun testSep1Config() {
    val sep1Config = platformServerContext.getBean(Sep1Config::class.java)
    assertEquals(true, sep1Config.isEnabled)
    assertEquals("sep1/stellar-wks.toml", sep1Config.stellarFile)
  }

  @Test
  fun testSep10Config() {
    val sep10Config = platformServerContext.getBean(Sep10Config::class.java)
    assertEquals(true, sep10Config.enabled)
    assertEquals("localhost:8080", sep10Config.homeDomain)
    assertEquals(false, sep10Config.isClientAttributionRequired)
    assertEquals(listOf("lobstr.co", "preview.lobstr.co"), sep10Config.clientAttributionAllowList)
    assertEquals(900, sep10Config.authTimeout)
    assertEquals(86400, sep10Config.jwtTimeout)
    assertEquals(
      "SAX3AH622R2XT6DXWWSRIDCMMUCCMATBZ5U6XKJWDO7M2EJUBFC3AW5X",
      sep10Config.signingSeed
    )
  }

  @Test
  fun testSep38Config() {
    val sep38Config = platformServerContext.getBean(Sep38Config::class.java)

    assertEquals(true, sep38Config.isEnabled)
    assertEquals("http://localhost:8081", sep38Config.quoteIntegrationEndPoint)
  }
}
