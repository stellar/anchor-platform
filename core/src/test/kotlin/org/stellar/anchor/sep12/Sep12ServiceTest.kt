@file:Suppress("unused")

package org.stellar.anchor.sep12

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.skyscreamer.jsonassert.JSONAssert
import org.stellar.anchor.api.callback.*
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.exception.*
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.sep.sep12.Sep12CustomerRequestBase
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12PutCustomerRequest
import org.stellar.anchor.api.sep.sep12.Sep12Status
import org.stellar.anchor.api.shared.CustomerField
import org.stellar.anchor.api.shared.Customers
import org.stellar.anchor.api.shared.ProvidedCustomerField
import org.stellar.anchor.api.shared.StellarId
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.asset.DefaultAssetService
import org.stellar.anchor.auth.Sep10Jwt
import org.stellar.anchor.auth.WebAuthJwt
import org.stellar.anchor.client.ClientFinder
import org.stellar.anchor.event.EventService
import org.stellar.anchor.util.StringHelper.json

class Sep12ServiceTest {
  companion object {
    private const val TEST_ACCOUNT = "GBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUIZG"
    private const val TEST_CONTRACT_ACCOUNT =
      "CCAASCQKVVBSLREPEUGPOTQZ4BC2NDBY2MW7B2LGIGFUPIY4Z3XUZRVTX"
    private const val TEST_MEMO = "123456"
    private const val TEST_MUXED_ACCOUNT =
      "MBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUAAAAAAAAAPCIA2IM"
    private const val CLIENT_DOMAIN = "demo-wallet.stellar.org"
    private const val TEST_HOST_URL = "http://localhost:8080"
    private const val TEST_TRANSACTION_ID = "test-transaction-id"
    private const val wantedSep12GetCustomerResponse =
      """
{
  "id": "customer-id",
  "status": "ACCEPTED",
  "fields": {
    "email_address": {
      "type": "string",
      "description": "email address of the customer",
      "optional": false
    }
  },
  "provided_fields": {
    "last_name": {
      "type": "string",
      "description": "The customer\u0027s last name",
      "optional": false,
      "status": "ACCEPTED"
    }
  },
  "message": "foo bar"
}
    """
  }

  private val issuedAt = Instant.now().epochSecond
  private val expiresAt = issuedAt + 9000
  private val mockFields =
    mapOf<String, CustomerField>(
      "email_address" to
        CustomerField.builder()
          .type("string")
          .description("email address of the customer")
          .optional(false)
          .build()
    )
  private val mockProvidedFields =
    mapOf<String, ProvidedCustomerField>(
      "last_name" to
        ProvidedCustomerField.builder()
          .type("string")
          .description("The customer's last name")
          .optional(false)
          .status(Sep12Status.ACCEPTED.name)
          .build()
    )

  private lateinit var sep12Service: Sep12Service
  @MockK(relaxed = true) private lateinit var customerIntegration: CustomerIntegration
  @MockK(relaxed = true) private lateinit var assetService: AssetService
  @MockK(relaxed = true) private lateinit var platformApiClient: PlatformApiClient
  @MockK(relaxed = true) private lateinit var eventService: EventService
  @MockK(relaxed = true) private lateinit var eventSession: EventService.Session
  @MockK(relaxed = true) private lateinit var clientFinder: ClientFinder

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    val rjas = DefaultAssetService.fromJsonResource("test_assets.json")
    val assets = rjas.getAssets()

    every { assetService.getAssets() } returns assets
    every { eventService.createSession(any(), any()) } returns eventSession

    sep12Service = Sep12Service(customerIntegration, platformApiClient, eventService, clientFinder)
  }

  @ValueSource(strings = [TEST_ACCOUNT, TEST_CONTRACT_ACCOUNT, TEST_MUXED_ACCOUNT])
  @ParameterizedTest
  fun `test get for all account types succeed`(account: String) {
    val jwtToken = createJwtToken(account)
    val request =
      Sep12GetCustomerRequest.builder()
        .memo(if (account == TEST_MUXED_ACCOUNT) TEST_MEMO else null)
        .build()
    assertDoesNotThrow { sep12Service.getCustomer(jwtToken, request) }
  }

  @ValueSource(strings = [TEST_ACCOUNT, TEST_CONTRACT_ACCOUNT, TEST_MUXED_ACCOUNT])
  @ParameterizedTest
  fun `test put for all account types succeed`(account: String) {
    val jwtToken = createJwtToken(account)
    val request =
      Sep12PutCustomerRequest.builder()
        .memo(if (account == TEST_MUXED_ACCOUNT) TEST_MEMO else null)
        .build()
    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }
  }

  @ValueSource(strings = [TEST_ACCOUNT, TEST_CONTRACT_ACCOUNT, TEST_MUXED_ACCOUNT])
  @ParameterizedTest
  fun `test delete for all account types succeed`(account: String) {
    val jwtToken = createJwtToken(account)
    val memo = if (account == TEST_MUXED_ACCOUNT) TEST_MEMO else null
    val memoType = if (account == TEST_MUXED_ACCOUNT) "id" else null
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, account, memo, memoType) }
  }

  @Test
  fun `test validate request and token accounts`() {
    val mockRequestBase = mockk<Sep12CustomerRequestBase>(relaxed = true)

    // request account fails if not the same as token account
    var jwtToken = createJwtToken(TEST_ACCOUNT)
    every { mockRequestBase.account } returns "random-account"
    val ex: SepException = assertThrows {
      sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken)
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The account specified does not match authorization token", ex.message)

    // request account succeeds when the same as token account
    every { mockRequestBase.account } returns TEST_ACCOUNT
    assertDoesNotThrow { sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken) }

    // request account succeeds when the same as token's base (demuxed) account
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    assertDoesNotThrow { sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken) }

    // request account succeeds when the same as token's muxed account
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    every { mockRequestBase.account } returns TEST_MUXED_ACCOUNT
    assertDoesNotThrow { sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken) }

    // request account succeeds when the same as token's base account when using "account:memo"
    jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    every { mockRequestBase.account } returns TEST_ACCOUNT
    assertDoesNotThrow { sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken) }

    // request account succeeds for contract accounts
    jwtToken = createJwtToken(TEST_CONTRACT_ACCOUNT)
    every { mockRequestBase.account } returns TEST_CONTRACT_ACCOUNT
    assertDoesNotThrow { sep12Service.validateRequestAndTokenAccounts(mockRequestBase, jwtToken) }
  }

  @Test
  fun `test validate request and token memos`() {
    val mockRequestBase = mockk<Sep12CustomerRequestBase>(relaxed = true)

    // If the token doesn't have a memo nor a Muxed account id, does not fail for empty request memo
    var jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow { sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken) }

    // If the token doesn't have a memo nor a Muxed account id, does not fail for any request memo
    every { mockRequestBase.memo } returns "random-memo"
    assertDoesNotThrow { sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken) }

    // If the token has a memo that's different from the request's, throw an error
    jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    every { mockRequestBase.memo } returns "random-memo"
    var ex: SepException = assertThrows {
      sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken)
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The memo specified does not match the memo ID authorized via SEP-10", ex.message)

    // If the token has a memo that's equals the request's, succeed!
    jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    every { mockRequestBase.memo } returns TEST_MEMO
    assertDoesNotThrow { sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken) }

    // If the token has a memo that's different from the request's Muxed id, throw an error
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    every { mockRequestBase.memo } returns "random-memo"
    ex = assertThrows { sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The memo specified does not match the memo ID authorized via SEP-10", ex.message)

    // If the token has a memo that's equals the request's Muxed id, succeed!
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    every { mockRequestBase.memo } returns TEST_MEMO
    assertDoesNotThrow { sep12Service.validateRequestAndTokenMemos(mockRequestBase, jwtToken) }
  }

  @Test
  fun `test transaction ownership check rejects mismatched creator`() {
    val transaction =
      GetTransactionResponse.builder()
        .creator(StellarId.builder().account("GOTHER_ACCOUNT").build())
        .customers(
          Customers.builder()
            .sender(StellarId.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).build())
            .build()
        )
        .build()
    every { platformApiClient.getTransaction(any()) } returns transaction

    val request = Sep12GetCustomerRequest.builder().transactionId(TEST_TRANSACTION_ID).build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The transaction specified does not exist", ex.message)
  }

  @Test
  fun `test transaction ownership check rejects null creator`() {
    val transaction =
      GetTransactionResponse.builder()
        .customers(
          Customers.builder()
            .sender(StellarId.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).build())
            .build()
        )
        .build()
    every { platformApiClient.getTransaction(any()) } returns transaction

    val request = Sep12GetCustomerRequest.builder().transactionId(TEST_TRANSACTION_ID).build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The transaction specified does not exist", ex.message)
  }

  @Test
  fun `test transaction ownership check allows matching creator`() {
    val transaction =
      GetTransactionResponse.builder()
        .creator(StellarId.builder().account(TEST_ACCOUNT).build())
        .customers(
          Customers.builder()
            .sender(StellarId.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).build())
            .build()
        )
        .build()
    every { platformApiClient.getTransaction(any()) } returns transaction

    val request = Sep12GetCustomerRequest.builder().transactionId(TEST_TRANSACTION_ID).build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertEquals(TEST_ACCOUNT, request.account)
    assertEquals(TEST_MEMO, request.memo)
  }

  @Test
  fun `test update request memo and memo type`() {
    val mockRequestBase = mockk<Sep12CustomerRequestBase>(relaxed = true)

    // if the request doesn't have any kind of memo, make sure the memo type is empty and return
    var jwtToken = createJwtToken(TEST_ACCOUNT)
    every { mockRequestBase.memo } returns null
    assertDoesNotThrow { sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken) }
    verify(exactly = 1) { mockRequestBase.memo }
    verify(exactly = 1) { mockRequestBase.memoType = null }

    // if the request memo is present but memoType is empty, default memoType to MEMO_ID
    every { mockRequestBase.memo } returns TEST_MEMO
    every { mockRequestBase.memoType } returns null
    assertDoesNotThrow { sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken) }
    verify(exactly = 2) { mockRequestBase.memo }
    verify(exactly = 1) { mockRequestBase.memoType = "id" }

    // if the token memo is present, default memoType to MEMO_ID
    jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    every { mockRequestBase.memo } returns TEST_MEMO
    every { mockRequestBase.memoType } returns "text"
    assertDoesNotThrow { sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken) }
    verify(exactly = 3) { mockRequestBase.memo }
    verify(exactly = 2) { mockRequestBase.memoType = "id" }

    // if the token muxed id is present, default memoType to MEMO_ID
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    every { mockRequestBase.memo } returns TEST_MEMO
    every { mockRequestBase.memoType } returns "text"
    assertDoesNotThrow { sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken) }
    verify(exactly = 4) { mockRequestBase.memo }
    verify(exactly = 3) { mockRequestBase.memoType = "id" }

    // works with memoType text when no memo was used in the token
    jwtToken = createJwtToken(TEST_ACCOUNT)
    every { mockRequestBase.memo } returns TEST_MEMO
    every { mockRequestBase.memoType } returns "text"
    assertDoesNotThrow { sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken) }
    verify(exactly = 5) { mockRequestBase.memo }
    verify(exactly = 1) { mockRequestBase.memoType = "text" }

    // tests if the memos are being validated according to their types
    jwtToken = createJwtToken(TEST_ACCOUNT)
    every { mockRequestBase.memo } returns TEST_MEMO
    every { mockRequestBase.memoType } returns "hash"
    val ex: SepException = assertThrows {
      sep12Service.updateRequestMemoAndMemoType(mockRequestBase, jwtToken)
    }
    assertInstanceOf(SepValidationException::class.java, ex)
    assertEquals("Invalid 'memo' for 'memo_type'", ex.message)
  }

  @ParameterizedTest
  @ValueSource(strings = [TEST_TRANSACTION_ID])
  @NullSource
  fun `Test put customer request ok`(transactionId: String?) {
    // mock `PUT {callbackApi}/customer` response
    val callbackApiPutRequestSlot = slot<PutCustomerRequest>()
    val kycUpdateEventSlot = slot<AnchorEvent>()
    val mockCallbackApiGetCustomerResponse =
      GetCustomerResponse.builder()
        .id("customer-id")
        .status(Sep12Status.ACCEPTED.toString())
        .fields(mockFields)
        .providedFields(mockProvidedFields)
        .build()
    val mockCallbackApiPutCustomerResponse =
      PutCustomerResponse.builder()
        .id("customer-id")
        .status(Sep12Status.ACCEPTED.toString())
        .fields(mockFields)
        .providedFields(mockProvidedFields)
        .build()
    every { customerIntegration.putCustomer(capture(callbackApiPutRequestSlot)) } returns
      mockCallbackApiPutCustomerResponse
    every { eventSession.publish(capture(kycUpdateEventSlot)) } returns Unit
    every { platformApiClient.getTransaction(any()) } returns
      GetTransactionResponse.builder()
        .creator(StellarId.builder().account(TEST_ACCOUNT).build())
        .customers(
          Customers.builder()
            .sender(StellarId.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).build())
            .build()
        )
        .build()

    // Execute the request
    val mockPutRequest =
      Sep12PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .birthDate("2000-01-01")
        .idIssueDate("2023-12-13")
        .idExpirationDate("2023-12-13T19:33:07Z")
        .emailAddressVerification("12345678")
        .bankName("Bank of America")
        .mobileMoneyNumber("12345678")
        .mobileMoneyProvider("M-PESA")
        .externalTransferMemo("memo")
        .transactionId(transactionId)
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, mockPutRequest) }

    // validate the request
    val wantCallbackApiPutRequest =
      PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .birthDate("2000-01-01")
        .idIssueDate("2023-12-13")
        .idExpirationDate("2023-12-13T19:33:07Z")
        .emailAddressVerification("12345678")
        .bankName("Bank of America")
        .mobileMoneyNumber("12345678")
        .mobileMoneyProvider("M-PESA")
        .externalTransferMemo("memo")
        .transactionId(transactionId)
        .build()
    assertEquals(wantCallbackApiPutRequest, callbackApiPutRequestSlot.captured)

    // validate the published event
    assertNotNull(kycUpdateEventSlot.captured.id)
    assertEquals("12", kycUpdateEventSlot.captured.sep)
    assertEquals(AnchorEvent.Type.CUSTOMER_UPDATED, kycUpdateEventSlot.captured.type)
    assertEquals(CLIENT_DOMAIN, kycUpdateEventSlot.captured.clientDomain)
    assertEquals(
      GetCustomerResponse.to(mockCallbackApiGetCustomerResponse),
      kycUpdateEventSlot.captured.customer,
    )

    // validate the response
    verify(exactly = 1) { customerIntegration.putCustomer(any()) }
    verify(exactly = 1) { eventSession.publish(any()) }
    assertEquals(TEST_ACCOUNT, mockPutRequest.account)
  }

  @Test
  fun `Test put customer publishes event with null clientName and logs warning when client is not authorized`() {
    val kycUpdateEventSlot = slot<AnchorEvent>()
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("customer-id").build()
    every { eventSession.publish(capture(kycUpdateEventSlot)) } returns Unit
    every { clientFinder.getClientName(any<WebAuthJwt>()) } throws
      SepNotAuthorizedException("Client not found")

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow {
      sep12Service.putCustomer(jwtToken, Sep12PutCustomerRequest.builder().build())
    }

    verify(exactly = 1) { eventSession.publish(any()) }
    assertEquals(AnchorEvent.Type.CUSTOMER_UPDATED, kycUpdateEventSlot.captured.type)
    assertEquals(null, kycUpdateEventSlot.captured.clientName)
  }

  @Test
  fun `Test put customer bad birth_date`() {
    // Execute the request
    val mockPutRequest =
      Sep12PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .birthDate("2023-12-13T19:33:07X")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertThrows<SepValidationException> { sep12Service.putCustomer(jwtToken, mockPutRequest) }
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `Test put customer bad id_issue_date`() {
    // Execute the request
    val mockPutRequest =
      Sep12PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .idIssueDate("2023-12-13T19:33:07X")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertThrows<SepValidationException> { sep12Service.putCustomer(jwtToken, mockPutRequest) }
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `Test put customer bad id_expiration_date`() {
    // Execute the request
    val mockPutRequest =
      Sep12PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .idExpirationDate("2023-12-13T19:33:07X")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertThrows<SepValidationException> { sep12Service.putCustomer(jwtToken, mockPutRequest) }
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `Test put customer request failure`() {
    val callbackApiPutRequestSlot = slot<PutCustomerRequest>()
    every { customerIntegration.putCustomer(capture(callbackApiPutRequestSlot)) } throws
      ServerErrorException("some error")

    val mockPutRequest =
      Sep12PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    assertThrows<AnchorException> { sep12Service.putCustomer(jwtToken, mockPutRequest) }

    // validate the request
    val wantCallbackApiPutRequest =
      PutCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sending_user")
        .firstName("John")
        .build()
    assertEquals(wantCallbackApiPutRequest, callbackApiPutRequestSlot.captured)

    verify(exactly = 1) { customerIntegration.putCustomer(any()) }
    verify { eventSession wasNot Called }
  }

  @Test
  fun `Test get customer request ok`() {
    // mock `GET {callbackApi}/customer` response
    val callbackApiGetRequestSlot = slot<GetCustomerRequest>()
    val mockCallbackApiGetCustomerResponse = GetCustomerResponse()
    mockCallbackApiGetCustomerResponse.id = "customer-id"
    mockCallbackApiGetCustomerResponse.status = Sep12Status.ACCEPTED.name
    mockCallbackApiGetCustomerResponse.fields = mockFields
    mockCallbackApiGetCustomerResponse.providedFields = mockProvidedFields
    mockCallbackApiGetCustomerResponse.message = "foo bar"
    every { customerIntegration.getCustomer(capture(callbackApiGetRequestSlot)) } returns
      mockCallbackApiGetCustomerResponse

    // Execute the request
    val mockGetRequest =
      Sep12GetCustomerRequest.builder()
        .memo(TEST_MEMO)
        .memoType("text")
        .type("sep31_sender")
        .lang("en")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow {
      val resp = sep12Service.getCustomer(jwtToken, mockGetRequest)
      JSONAssert.assertEquals(wantedSep12GetCustomerResponse, json(resp), false)
    }

    // validate the request
    val wantCallbackApiGetRequest =
      GetCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("text")
        .type("sep31_sender")
        .lang("en")
        .build()
    assertEquals(wantCallbackApiGetRequest, callbackApiGetRequestSlot.captured)

    // validate the response
    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
    assertEquals(TEST_ACCOUNT, mockGetRequest.account)
  }

  @Test
  fun `Test get customer request with id injects account`() {
    val callbackApiGetRequestSlot = slot<GetCustomerRequest>()
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    val fullResponse = GetCustomerResponse()
    fullResponse.id = "customer-id"
    every { customerIntegration.getCustomer(capture(callbackApiGetRequestSlot)) } returnsMany
      listOf(prefetchResponse, fullResponse)

    val mockGetRequest = Sep12GetCustomerRequest.builder().id("customer-id").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.getCustomer(jwtToken, mockGetRequest) }

    val wantCallbackApiGetRequest =
      GetCustomerRequest.builder().id("customer-id").account(TEST_ACCOUNT).build()
    assertEquals(wantCallbackApiGetRequest, callbackApiGetRequestSlot.captured)
    assertEquals(TEST_ACCOUNT, mockGetRequest.account)
  }

  @Test
  fun `Test put customer request with id injects account`() {
    val callbackApiPutRequestSlot = slot<PutCustomerRequest>()
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns prefetchResponse
    val mockCallbackApiPutCustomerResponse = PutCustomerResponse.builder().id("customer-id").build()
    every { customerIntegration.putCustomer(capture(callbackApiPutRequestSlot)) } returns
      mockCallbackApiPutCustomerResponse

    val mockPutRequest = Sep12PutCustomerRequest.builder().id("customer-id").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, mockPutRequest) }

    val wantCallbackApiPutRequest =
      PutCustomerRequest.builder().id("customer-id").account(TEST_ACCOUNT).build()
    assertEquals(wantCallbackApiPutRequest, callbackApiPutRequestSlot.captured)
    assertEquals(TEST_ACCOUNT, mockPutRequest.account)
  }

  @Test
  fun `test get customer with id belonging to different account should throw`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("account mismatch")

    val attackerToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("victim-customer-id").build()

    val ex: SepException = assertThrows {
      sep12Service.validateGetOrPutRequest(request, attackerToken)
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test put customer with id belonging to different account should throw`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("account mismatch")

    val attackerToken = createJwtToken(TEST_ACCOUNT)
    val request =
      Sep12PutCustomerRequest.builder()
        .id("victim-customer-id")
        .bankAccountNumber("attacker-iban")
        .build()

    val ex: SepException = assertThrows { sep12Service.putCustomer(attackerToken, request) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `test get customer with unknown id should throw`() {
    val notFoundResponse = GetCustomerResponse()
    every { customerIntegration.getCustomer(any()) } returns notFoundResponse

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("nonexistent-id").build()

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
  }

  @Test
  fun `test id ownership mismatch fails closed`() {
    every { customerIntegration.getCustomer(any()) } throws
      RuntimeException("not found for account")

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("some-other-id").build()

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
  }

  @Test
  fun `test id path normalizes error message across all failure modes`() {
    val attackerToken = createJwtToken(TEST_ACCOUNT)

    every { customerIntegration.getCustomer(any()) } throws RuntimeException("account mismatch")
    val accountMismatchEx: SepException = assertThrows {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("victim-id").build(),
        attackerToken
      )
    }

    every { customerIntegration.getCustomer(any()) } returns GetCustomerResponse()
    val unknownEx: SepException = assertThrows {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("unknown-id").build(),
        attackerToken
      )
    }

    assertEquals(accountMismatchEx.message, unknownEx.message)
    assertEquals("not authorized for customer id", accountMismatchEx.message)
  }

  @Test
  fun `test id path converts callback exception to 403`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("db error")

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("customer-id").build()

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
  }

  @Test
  fun `test id prefetch includes type in request`() {
    val prefetchSlot = slot<GetCustomerRequest>()
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    every { customerIntegration.getCustomer(capture(prefetchSlot)) } returns prefetchResponse

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("customer-id").type("sep31-receiver").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertNull(prefetchSlot.captured.id)
    assertEquals(TEST_ACCOUNT, prefetchSlot.captured.account)
    assertEquals("sep31-receiver", prefetchSlot.captured.type)
  }

  @Test
  fun `test memo-only customer with no-memo token is allowed when account matches`() {
    val ownedCustomer = GetCustomerResponse()
    ownedCustomer.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns ownedCustomer

    val noMemoToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("customer-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, noMemoToken) }
  }

  @Test
  fun `test memo-only customer with matching token memo is allowed`() {
    // Reverse lookup: account+memo finds the customer; returned id matches the request.
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns prefetchResponse

    val memoToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    val request = Sep12GetCustomerRequest.builder().id("customer-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, memoToken) }
  }

  @Test
  fun `test getCustomer denies no-memo token when business server rejects account mismatch end-to-end`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("account mismatch")

    val noMemoToken = createJwtToken(TEST_ACCOUNT)

    val ex: AnchorException = assertThrows {
      sep12Service.getCustomer(
        noMemoToken,
        Sep12GetCustomerRequest.builder().id("customer-id").build(),
      )
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test putCustomer denies no-memo token when business server rejects account mismatch end-to-end`() {
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("account mismatch")

    val noMemoToken = createJwtToken(TEST_ACCOUNT)

    val ex: AnchorException = assertThrows {
      sep12Service.putCustomer(
        noMemoToken,
        Sep12PutCustomerRequest.builder().id("customer-id").firstName("Alice").build(),
      )
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `test id path forwards token account to callback on get`() {
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    val callbackSlot = slot<GetCustomerRequest>()
    val callbackResponse = GetCustomerResponse()
    callbackResponse.id = "customer-id"
    every { customerIntegration.getCustomer(capture(callbackSlot)) } returnsMany
      listOf(prefetchResponse, callbackResponse)

    val memoToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    sep12Service.getCustomer(memoToken, Sep12GetCustomerRequest.builder().id("customer-id").build())

    assertEquals("customer-id", callbackSlot.captured.id)
    assertEquals(TEST_ACCOUNT, callbackSlot.captured.account)
    assertNull(callbackSlot.captured.memo)
  }

  @Test
  fun `test id path forwards token account to callback on put`() {
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    val callbackSlot = slot<PutCustomerRequest>()
    every { customerIntegration.getCustomer(any()) } returns prefetchResponse
    every { customerIntegration.putCustomer(capture(callbackSlot)) } returns
      PutCustomerResponse.builder().id("customer-id").build()

    val memoToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    sep12Service.putCustomer(
      memoToken,
      Sep12PutCustomerRequest.builder().id("customer-id").firstName("Alice").build(),
    )

    assertEquals("customer-id", callbackSlot.captured.id)
    assertEquals(TEST_ACCOUNT, callbackSlot.captured.account)
    assertNull(callbackSlot.captured.memo)
  }

  @Test
  fun `test id path sets token account and memo on request`() {
    val prefetchResponse = GetCustomerResponse()
    prefetchResponse.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns prefetchResponse

    val memoToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    val request = Sep12GetCustomerRequest.builder().id("customer-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, memoToken) }
    assertEquals(TEST_ACCOUNT, request.account)
    assertNull(request.memo)
  }

  @Test
  fun `test delete customer validation`() {
    every { customerIntegration.deleteCustomer(any()) } just Runs

    // PART 1 - account without memo
    // throws exception if request is missing the account
    var jwtToken = createJwtToken(TEST_ACCOUNT)
    var ex: AnchorException = assertThrows {
      sep12Service.deleteCustomer(jwtToken, null, null, null)
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [null] with memo [null]", ex.message)

    // throws exception if request account is different from token account
    ex = assertThrows { sep12Service.deleteCustomer(jwtToken, "foo", null, null) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [foo] with memo [null]", ex.message)

    // succeeds if request account is equals the token's
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, null, null) }

    // PART 2 - account with memo
    // throws exception if request is missing the memo
    jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    ex = assertThrows { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, null, null) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [$TEST_ACCOUNT] with memo [null]", ex.message)

    // throws exception if request account is different from token account
    ex = assertThrows { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, "bar", null) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [$TEST_ACCOUNT] with memo [bar]", ex.message)

    // succeeds if request account and memo are equal the token's
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null) }

    // succeeds if the request account is equals the token's, and the token memo is empty while the
    // request's is not
    jwtToken = createJwtToken(TEST_ACCOUNT)
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, "foo_bar", null) }

    // PART 3 - muxed account
    // throws exception if request is missing the memo
    jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    ex = assertThrows { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, null, null) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [$TEST_ACCOUNT] with memo [null]", ex.message)

    // throws exception if request account is different from token account
    ex = assertThrows { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, "bar", null) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("Not authorized to delete account [$TEST_ACCOUNT] with memo [bar]", ex.message)

    // succeeds if request account is equals the token's and the memo is equals the token muxed id
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null) }

    // succeeds if request account is equals the token's muxed account
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_MUXED_ACCOUNT, null, null) }
  }

  @Test
  fun `test delete customer`() {
    // mock callbackApi customer integration
    val deleteCustomerIdSlot = slot<String>()
    every { customerIntegration.deleteCustomer(capture(deleteCustomerIdSlot)) } just Runs

    // attempting to delete a non-existent customer returns 404
    val mockNoCustomerFound = GetCustomerResponse()
    every { customerIntegration.getCustomer(any()) } returns mockNoCustomerFound

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val ex: AnchorException = assertThrows {
      sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null)
    }
    assertInstanceOf(SepNotFoundException::class.java, ex)
    assertEquals("User not found.", ex.message)
    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }

    // customer deletion succeeds
    val mockValidCustomerFound = GetCustomerResponse()
    mockValidCustomerFound.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns mockValidCustomerFound
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null) }
    verify(exactly = 2) { customerIntegration.getCustomer(any()) }
    // callback API is called twice
    verify(exactly = 1) { customerIntegration.deleteCustomer(any()) }
    val wantDeleteCustomerId = "customer-id"
    assertEquals(wantDeleteCustomerId, deleteCustomerIdSlot.captured)
  }

  private fun createJwtToken(subject: String): WebAuthJwt {
    return Sep10Jwt.of("$TEST_HOST_URL/auth", subject, issuedAt, expiresAt, "", CLIENT_DOMAIN, null)
  }
}
