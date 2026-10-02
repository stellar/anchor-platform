package org.stellar.reference.sep24

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.reference.data.Amount
import org.stellar.reference.data.Transaction
import org.stellar.reference.jwt.Sep24SessionToken
import org.stellar.reference.service.SepHelper

class Sep24RouteTest {
  private val jwtKey = "sep24_interactive_url_jwt_secret_for_tests_0123456789"
  private val otherKey = "a_completely_different_secret_for_tests_0123456789_xx"
  private val moreInfoKey = "sep24_more_info_url_jwt_secret_for_tests_0123456789"
  private val transactionId = "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11"

  private lateinit var sepHelper: SepHelper
  private lateinit var depositService: DepositService
  private lateinit var withdrawalService: WithdrawalService

  @BeforeEach
  fun setup() {
    sepHelper = mockk()
    depositService = mockk(relaxed = true)
    withdrawalService = mockk(relaxed = true)
    coEvery { sepHelper.getTransaction(transactionId) } returns
      Transaction(
        id = transactionId,
        status = "incomplete",
        kind = "deposit",
        amountExpected = Amount(asset = "stellar:native"),
        destinationAccount = "GB4RHW4IBN2ML3LGXKSBPS4SKVFZW56G74RD5QZDYFFMXEFJEAJ6USNO",
      )
  }

  private fun routeTest(
    moreInfoJwtKey: String? = moreInfoKey,
    sessionTtlSeconds: Long = Sep24SessionToken.DEFAULT_TTL_SECONDS,
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      install(ContentNegotiation) { json() }
      routing {
        sep24(
          sepHelper,
          depositService,
          withdrawalService,
          jwtKey,
          moreInfoJwtKey,
          sessionTtlSeconds,
        )
      }
    }
    block()
  }

  private fun interactiveJwt(
    audience: String? = "sep24_interactive",
    key: String = jwtKey,
    expiresInMillis: Long = 60_000,
  ): String {
    val builder =
      Jwts.builder()
        .id(transactionId)
        .expiration(Date(System.currentTimeMillis() + expiresInMillis))
        .claim("data", mapOf("asset" to "stellar:native"))
    if (audience != null) {
      builder.audience().add(audience).and()
    }
    return builder.signWith(Keys.hmacShaKeyFor(key.toByteArray(StandardCharsets.UTF_8))).compact()
  }

  private fun sessionIdOf(body: String): String =
    Json.parseToJsonElement(body).jsonObject["sessionId"]!!.jsonPrimitive.content

  @Test
  fun `start exchanges a valid interactive token for a session bound to the transaction`() =
    routeTest {
      val response =
        client.post("/start") { header(HttpHeaders.Authorization, "Bearer ${interactiveJwt()}") }

      assertEquals(HttpStatusCode.OK, response.status)
      val session = sessionIdOf(response.bodyAsText())
      assertNotEquals(transactionId, session)
      assertEquals(transactionId, Sep24SessionToken.verify(session, jwtKey))
    }

  @Test
  fun `start rejects a token issued for another audience`() = routeTest {
    val response =
      client.post("/start") {
        header(HttpHeaders.Authorization, "Bearer ${interactiveJwt(audience = "sep24_more_info")}")
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun `start rejects an expired interactive token`() = routeTest {
    val response =
      client.post("/start") {
        header(HttpHeaders.Authorization, "Bearer ${interactiveJwt(expiresInMillis = -60_000)}")
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun `transaction rejects a raw transaction id as the bearer value`() = routeTest {
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $transactionId") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { sepHelper.getTransaction(any()) }
  }

  @Test
  fun `transaction rejects the interactive token itself`() = routeTest {
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer ${interactiveJwt()}") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { sepHelper.getTransaction(any()) }
  }

  @Test
  fun `transaction rejects a session signed with another key`() = routeTest {
    val forged = Sep24SessionToken.issue(transactionId, otherKey)
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $forged") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { sepHelper.getTransaction(any()) }
  }

  @Test
  fun `transaction rejects an expired session`() = routeTest {
    val expired =
      Sep24SessionToken.issue(
        transactionId,
        jwtKey,
        Sep24SessionToken.DEFAULT_TTL_SECONDS,
        System.currentTimeMillis() - Sep24SessionToken.DEFAULT_TTL_SECONDS * 1000 - 60_000,
      )
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $expired") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun `transaction returns the session's own transaction`() = routeTest {
    val session = Sep24SessionToken.issue(transactionId, jwtKey)
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $session") }

    assertEquals(HttpStatusCode.OK, response.status)
    coVerify(exactly = 1) { sepHelper.getTransaction(transactionId) }
  }

  @Test
  fun `transaction does not leak internal error details`() = routeTest {
    coEvery { sepHelper.getTransaction(transactionId) } throws
      IllegalStateException(
        "In response from http://anchor-platform-svc-platform:8085/transactions"
      )
    val session = Sep24SessionToken.issue(transactionId, jwtKey)
    val body =
      client
        .get("/transaction") { header(HttpHeaders.Authorization, "Bearer $session") }
        .bodyAsText()

    assertFalse(body.contains("anchor-platform-svc-platform"))
  }

  @Test
  fun `submit rejects a raw transaction id and never starts processing`() = routeTest {
    val response =
      client.post("/submit") {
        header(HttpHeaders.Authorization, "Bearer $transactionId")
        contentType(ContentType.Application.Json)
        setBody("""{"amount":"25","name":"a","surname":"b","email":"c@d.e"}""")
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { sepHelper.getTransaction(any()) }
    coVerify(exactly = 0) { depositService.processDeposit(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `submit with a valid session processes the session's transaction`() = routeTest {
    val session = Sep24SessionToken.issue(transactionId, jwtKey)
    val response =
      client.post("/submit") {
        header(HttpHeaders.Authorization, "Bearer $session")
        contentType(ContentType.Application.Json)
        setBody("""{"amount":"5","name":"a","surname":"b","email":"c@d.e"}""")
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(session, sessionIdOf(response.bodyAsText()))
    coVerify(timeout = 5_000) {
      depositService.processDeposit(transactionId, "5".toBigDecimal(), any(), "native", any())
    }
  }

  @Test
  fun `transaction accepts the platform more-info token for read only`() = routeTest {
    val moreInfo = interactiveJwt(audience = "sep24_more_info", key = moreInfoKey)
    val response =
      client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $moreInfo") }

    assertEquals(HttpStatusCode.OK, response.status)
    coVerify(exactly = 1) { sepHelper.getTransaction(transactionId) }
  }

  @Test
  fun `transaction rejects a more-info token when no more-info key is configured`() =
    routeTest(moreInfoJwtKey = null) {
      val moreInfo = interactiveJwt(audience = "sep24_more_info", key = moreInfoKey)
      val response =
        client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $moreInfo") }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `transaction rejects a more-info audience token signed with the interactive key`() =
    routeTest {
      val forged = interactiveJwt(audience = "sep24_more_info", key = jwtKey)
      val response =
        client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $forged") }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

  @Test
  fun `submit rejects the more-info token`() = routeTest {
    val moreInfo = interactiveJwt(audience = "sep24_more_info", key = moreInfoKey)
    val response =
      client.post("/submit") {
        header(HttpHeaders.Authorization, "Bearer $moreInfo")
        contentType(ContentType.Application.Json)
        setBody("""{"amount":"25","name":"a","surname":"b","email":"c@d.e"}""")
      }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
    coVerify(exactly = 0) { depositService.processDeposit(any(), any(), any(), any(), any()) }
  }

  private fun stubWithdrawal() {
    coEvery { sepHelper.getTransaction(transactionId) } returns
      Transaction(
        id = transactionId,
        status = "incomplete",
        kind = "withdrawal",
        amountExpected = Amount(asset = "stellar:native"),
      )
  }

  private val withdrawalBody =
    """{"amount":"5","name":"a","surname":"b","email":"c@d.e","bank":"x","account":"1"}"""

  @Test
  fun `submit rejects a raw transaction id for a withdrawal and never starts processing`() =
    routeTest {
      stubWithdrawal()
      val response =
        client.post("/submit") {
          header(HttpHeaders.Authorization, "Bearer $transactionId")
          contentType(ContentType.Application.Json)
          setBody(withdrawalBody)
        }

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      coVerify(exactly = 0) { withdrawalService.processWithdrawal(any(), any(), any()) }
    }

  @Test
  fun `submit with a valid session processes the session's withdrawal`() = routeTest {
    stubWithdrawal()
    val session = Sep24SessionToken.issue(transactionId, jwtKey)
    val response =
      client.post("/submit") {
        header(HttpHeaders.Authorization, "Bearer $session")
        contentType(ContentType.Application.Json)
        setBody(withdrawalBody)
      }

    assertEquals(HttpStatusCode.OK, response.status)
    coVerify(timeout = 5_000) {
      withdrawalService.processWithdrawal(transactionId, "5".toBigDecimal(), "native")
    }
  }

  @Test
  fun `start accepts an interactive token without an audience from older platforms`() = routeTest {
    val response =
      client.post("/start") {
        header(HttpHeaders.Authorization, "Bearer ${interactiveJwt(audience = null)}")
      }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(
      transactionId,
      Sep24SessionToken.verify(sessionIdOf(response.bodyAsText()), jwtKey),
    )
  }

  @Test
  fun `start rejects a session token`() = routeTest {
    val session = Sep24SessionToken.issue(transactionId, jwtKey)
    val response = client.post("/start") { header(HttpHeaders.Authorization, "Bearer $session") }

    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }

  @Test
  fun `transaction accepts a more-info token without an audience from older platforms`() =
    routeTest {
      val moreInfo = interactiveJwt(audience = null, key = moreInfoKey)
      val response =
        client.get("/transaction") { header(HttpHeaders.Authorization, "Bearer $moreInfo") }

      assertEquals(HttpStatusCode.OK, response.status)
    }

  @Test
  fun `start issues a session with the configured lifetime`() =
    routeTest(sessionTtlSeconds = 3600) {
      val response =
        client.post("/start") { header(HttpHeaders.Authorization, "Bearer ${interactiveJwt()}") }
      val claims =
        Jwts.parser()
          .verifyWith(Keys.hmacShaKeyFor(jwtKey.toByteArray(StandardCharsets.UTF_8)))
          .build()
          .parseSignedClaims(sessionIdOf(response.bodyAsText()))
          .payload

      assertEquals(3600_000L, claims.expiration.time - claims.issuedAt.time)
    }
}
