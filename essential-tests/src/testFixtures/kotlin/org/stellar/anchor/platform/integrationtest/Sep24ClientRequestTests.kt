package org.stellar.anchor.platform.integrationtest

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.sep.sep24.DepositTransactionResponse
import org.stellar.anchor.api.sep.sep24.WithdrawTransactionResponse
import org.stellar.anchor.client.Sep24Client

/**
 * Pins what [Sep24Client] actually puts on the wire: the route it names, the body encoding it
 * promises, and the `Authorization` header. The e2e tests in [Sep24Tests] can't: deposit and
 * withdraw emit identical validation errors, and the JSON and multipart controller methods build
 * the same request map, so a client pointed at the wrong route, or posting JSON where it claims
 * multipart, would still pass every one of them.
 *
 * Needs no running stack.
 */
class Sep24ClientRequestTests {
  private lateinit var server: MockWebServer

  private val jwt = "test-jwt"
  private val fields =
    mapOf("asset_code" to "USDC", "asset_issuer" to "GISSUER", "account" to "GABC")

  /**
   * The `kind` the mock serves for `GET /transaction`; a test sets it before calling the client.
   */
  private var transactionKind = "deposit"

  @BeforeEach
  fun startServer() {
    server = MockWebServer()
    server.dispatcher =
      object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
          MockResponse()
            .setResponseCode(200)
            .setBody(
              if (request.requestUrl?.encodedPath == "/transaction") {
                """{"transaction":{"id":"txn-1","kind":"$transactionKind"}}"""
              } else {
                """{"type":"interactive_customer_info_needed","url":"https://example.com/interactive","id":"txn-1"}"""
              }
            )
      }
    server.start()
  }

  @AfterEach
  fun stopServer() {
    server.shutdown()
  }

  private fun client(jwt: String?) = Sep24Client(server.url("").toString().trimEnd('/'), jwt)

  private fun recorded(): RecordedRequest = server.takeRequest()

  @Test
  fun `test sep24 client deposit posts JSON to the deposit route`() {
    val response = client(jwt).deposit(fields)

    val request = recorded()
    assertEquals("POST", request.method)
    assertEquals("/transactions/deposit/interactive", request.path)
    assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    assertEquals("Bearer $jwt", request.getHeader("Authorization"))
    assertEquals("txn-1", response.id)
  }

  @Test
  fun `test sep24 client withdraw posts JSON to the withdraw route`() {
    client(jwt).withdraw(fields)

    val request = recorded()
    assertEquals("POST", request.method)
    assertEquals("/transactions/withdraw/interactive", request.path)
    assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    assertEquals("Bearer $jwt", request.getHeader("Authorization"))
  }

  @Test
  fun `test sep24 client depositMultipart posts form data to the deposit route`() {
    client(jwt).depositMultipart(fields)

    val request = recorded()
    assertEquals("POST", request.method)
    assertEquals("/transactions/deposit/interactive", request.path)
    assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    assertEquals("Bearer $jwt", request.getHeader("Authorization"))
    assertFormFields(request)
  }

  @Test
  fun `test sep24 client withdrawMultipart posts form data to the withdraw route`() {
    client(jwt).withdrawMultipart(fields)

    val request = recorded()
    assertEquals("POST", request.method)
    assertEquals("/transactions/withdraw/interactive", request.path)
    assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    assertEquals("Bearer $jwt", request.getHeader("Authorization"))
    assertFormFields(request)
  }

  @Test
  fun `test sep24 client sends no Authorization header without a JWT`() {
    client(null).deposit(fields)

    assertNull(recorded().getHeader("Authorization"))
  }

  @Test
  fun `test sep24 client sends no Authorization header on a multipart call without a JWT`() {
    client(null).withdrawMultipart(fields)

    assertNull(recorded().getHeader("Authorization"))
  }

  @Test
  fun `test sep24 client getTransaction sends the given query parameters with the JWT`() {
    // '&', '=', '+' and '#' are the delimiters an unencoded query would let split or truncate it
    val externalTransactionId = "ext 1/é&x=y+z#f"
    val query = mapOf("external_transaction_id" to externalTransactionId, "lang" to "en")

    val response = client(jwt).getTransaction(query)

    val request = recorded()
    assertEquals("GET", request.method)
    assertEquals("/transaction", request.requestUrl!!.encodedPath)
    assertEquals(setOf("external_transaction_id", "lang"), request.requestUrl!!.queryParameterNames)
    assertEquals(
      externalTransactionId,
      request.requestUrl!!.queryParameter("external_transaction_id"),
    )
    assertEquals("en", request.requestUrl!!.queryParameter("lang"))
    assertEquals("Bearer $jwt", request.getHeader("Authorization"))
    assertEquals("txn-1", response.transaction.id)
    assertTrue(response.transaction is DepositTransactionResponse)
  }

  @Test
  fun `test sep24 client getTransaction parses a withdrawal as a WithdrawTransactionResponse`() {
    transactionKind = "withdrawal"

    val response = client(jwt).getTransaction(mapOf("id" to "txn-1"))

    assertEquals("txn-1", response.transaction.id)
    assertTrue(response.transaction is WithdrawTransactionResponse)
  }

  @Test
  fun `test sep24 client getTransaction sends no Authorization header without a JWT`() {
    client(null).getTransaction(mapOf("id" to "txn-1"))

    assertNull(recorded().getHeader("Authorization"))
  }

  /** Each supplied field must travel as its own form part, with the supplied value. */
  private fun assertFormFields(request: RecordedRequest) {
    val body = request.body.readUtf8()
    fields.forEach { (name, value) ->
      assertTrue(body.contains("""name="$name"""")) { "form part '$name' missing from: $body" }
      assertTrue(body.contains(value)) { "value '$value' missing from: $body" }
    }
  }
}
