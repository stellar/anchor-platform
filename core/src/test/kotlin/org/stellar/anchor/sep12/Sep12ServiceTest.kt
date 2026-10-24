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
import org.stellar.anchor.client.ClientService
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.client.NonCustodialClient
import org.stellar.anchor.event.EventService
import org.stellar.anchor.sep31.Sep31CustomerIdOwnerStore
import org.stellar.anchor.util.StringHelper.json

class Sep12ServiceTest {
  companion object {
    private const val TEST_ACCOUNT = "GBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUIZG"
    private const val TEST_CONTRACT_ACCOUNT =
      "CCAASCQKVVBSLREPEUGPOTQZ4BC2NDBY2MW7B2LGIGFUPIY4Z3XUZRVTX"
    private const val TEST_MEMO = "123456"
    private const val OTHER_KEY = "GAXLBAY4YSF6RRZTMV2CKS4NDVCMAYVKQGV3GNPUR2WWQVEFF6UYS4XZ"
    private const val TEST_MUXED_ACCOUNT =
      "MBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUAAAAAAAAAPCIA2IM"
    private const val CLIENT_DOMAIN = "demo-wallet.stellar.org"
    private const val TEST_HOST_URL = "http://localhost:8080"
    private const val TEST_TRANSACTION_ID = "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11"
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
  @MockK(relaxed = true) private lateinit var customerIdOwnerStore: Sep31CustomerIdOwnerStore
  @MockK(relaxed = true) private lateinit var clientService: ClientService

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    val rjas = DefaultAssetService.fromJsonResource("test_assets.json")
    val assets = rjas.getAssets()

    every { assetService.getAssets() } returns assets
    every { eventService.createSession(any(), any()) } returns eventSession
    every { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) } returns true
    every { customerIntegration.getCustomer(any()) } returns null

    sep12Service =
      Sep12Service(
        customerIntegration,
        platformApiClient,
        eventService,
        customerIdOwnerStore,
        clientService,
      )
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
    every { customerIdOwnerStore.isClaimed(any()) } returns true
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
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("existing-customer-id").build()
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

  @ParameterizedTest
  @ValueSource(
    strings =
      [
        "../clients",
        "../../clients/some-wallet",
        "../transactions?sep=24&page_size=2000000000",
        "../health",
        "..",
        "not-a-uuid",
        "9a0a0f9c-3b1c-4f1e-8d3a-2b6f4f0e7c11/../../clients",
      ]
  )
  fun `test transaction_id that is not a transaction id is rejected without calling the platform`(
    transactionId: String
  ) {
    val request = Sep12GetCustomerRequest.builder().transactionId(transactionId).build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }

    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("The transaction specified does not exist", ex.message)
    verify(exactly = 0) { platformApiClient.getTransaction(any()) }
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
  fun `test transaction_id path rejects a resolved customer id the caller does not own`() {
    val transaction =
      GetTransactionResponse.builder()
        .creator(StellarId.builder().account(TEST_ACCOUNT).build())
        .customers(
          Customers.builder().receiver(StellarId.builder().id("victim-customer-id").build()).build()
        )
        .build()
    every { platformApiClient.getTransaction(any()) } returns transaction
    every { customerIdOwnerStore.isClaimed("victim-customer-id") } returns true
    every { customerIdOwnerStore.verify("victim-customer-id", TEST_ACCOUNT, null, true) } returns
      false
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("some-other-id").build()

    val request =
      Sep12GetCustomerRequest.builder()
        .transactionId(TEST_TRANSACTION_ID)
        .type("sep31-receiver")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
  }

  @Test
  fun `test transaction_id path allows a SEP-31 customer id owned under a different memo of the same account`() {
    // Sep31Service intentionally accepts the same owner account under a different memo
    // (ignoreMemo=true) for sender_id/receiver_id, since a sending client legitimately manages
    // KYC records for both parties. Reading that same id back via transaction_id must not be
    // stricter than the check that allowed the transaction to be created in the first place.
    val transaction =
      GetTransactionResponse.builder()
        .creator(StellarId.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).build())
        .customers(
          Customers.builder()
            .receiver(StellarId.builder().id("receiver-under-other-memo").build())
            .build()
        )
        .build()
    every { platformApiClient.getTransaction(any()) } returns transaction
    every { customerIdOwnerStore.isClaimed("receiver-under-other-memo") } returns true
    every {
      customerIdOwnerStore.verify("receiver-under-other-memo", TEST_ACCOUNT, TEST_MEMO, true)
    } returns true

    val request =
      Sep12GetCustomerRequest.builder()
        .transactionId(TEST_TRANSACTION_ID)
        .type("sep31-receiver")
        .build()
    val jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
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
    every { customerIdOwnerStore.isClaimed(any()) } returns true
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
  fun `test put customer creating a new sep31-receiver claims ownership of the new id`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("new-receiver-id").status(Sep12Status.ACCEPTED.name).build()

    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-receiver")
        .memo(TEST_MEMO)
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-receiver-id", TEST_ACCOUNT, TEST_MEMO)
    }
  }

  @Test
  fun `test put customer creating a new sep31-receiver via a muxed account claims ownership using the muxed id`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder()
        .id("new-muxed-receiver-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()

    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-receiver")
        .memo(TEST_MEMO)
        .memoType("id")
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-muxed-receiver-id", TEST_MUXED_ACCOUNT, TEST_MEMO)
    }
  }

  @Test
  fun `test put customer rejects creation when the returned id is already claimed by another client`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("colliding-id").status(Sep12Status.ACCEPTED.name).build()
    every { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) } returns false

    val request = Sep12PutCustomerRequest.builder().type("sep31-sender").firstName("John").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.putCustomer(jwtToken, request) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
  }

  @Test
  fun `test put customer does not claim ownership when updating an existing customer`() {
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("existing-id").status(Sep12Status.ACCEPTED.name).build()
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("existing-id").build()

    val request =
      Sep12PutCustomerRequest.builder()
        .id("existing-id")
        .type("sep31-receiver")
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 0) { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) }
  }

  @Test
  fun `test put customer verifies a legacy id against the callback before claiming it`() {
    every { customerIdOwnerStore.isClaimed("legacy-sep24-id") } returns false
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("legacy-sep24-id").status(Sep12Status.ACCEPTED.name).build()
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("legacy-sep24-id").build()

    val request = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("legacy-sep24-id", TEST_ACCOUNT, null)
    }
  }

  @Test
  fun `test put customer verifies the legacy id using the request's account and memo, not the token's`() {
    every { customerIdOwnerStore.isClaimed("new-sep31-sender-id") } returns false
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder()
        .id("new-sep31-sender-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()
    val getCustomerRequestSlot = slot<GetCustomerRequest>()
    every { customerIntegration.getCustomer(capture(getCustomerRequestSlot)) } returns
      GetCustomerResponse.builder().id("new-sep31-sender-id").build()

    // A plain, non-muxed token with no memo in `sub` — the memo lives only in the request body,
    // which SEP-12 allows when the token carries none.
    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-sender")
        .memo(TEST_MEMO)
        .memoType("id")
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    assertEquals(TEST_ACCOUNT, getCustomerRequestSlot.captured.account)
    assertEquals(TEST_MEMO, getCustomerRequestSlot.captured.memo)
    assertEquals("sep31-sender", getCustomerRequestSlot.captured.type)
    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-sep31-sender-id", TEST_ACCOUNT, null)
    }
  }

  @Test
  fun `test put customer fails closed for an unclaimed id the caller cannot verify against the callback`() {
    every { customerIdOwnerStore.isClaimed("victim-legacy-id") } returns false
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("victim-legacy-id").status(Sep12Status.ACCEPTED.name).build()
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("some-other-id").build()

    val request = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.putCustomer(jwtToken, request) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    verify(exactly = 0) { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) }
  }

  @Test
  fun `test put customer fails closed when the pre-check callback lookup errors`() {
    // A callback outage must abort before the mutating PUT, not be treated as "no existing
    // customer" and let the write through.
    every { customerIntegration.getCustomer(any()) } throws RuntimeException("callback unavailable")

    val request = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: AnchorException = assertThrows { sep12Service.putCustomer(jwtToken, request) }
    assertInstanceOf(ServerErrorException::class.java, ex)
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
    verify(exactly = 0) { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) }
  }

  @Test
  fun `test put customer claims ownership for a new non-sep31 customer too`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("sep24-id").status(Sep12Status.ACCEPTED.name).build()

    val request = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) { customerIdOwnerStore.verifyOrClaim("sep24-id", TEST_ACCOUNT, null) }
  }

  @Test
  fun `test put customer rejects a new non-sep31 customer id already claimed by another client`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder()
        .id("sep6-colliding-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()
    every { customerIdOwnerStore.verifyOrClaim(any(), any(), any()) } returns false

    val request =
      Sep12PutCustomerRequest.builder().type("sep6").account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.putCustomer(jwtToken, request) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
  }

  @Test
  fun `test put customer rejects before writing when the caller already has a colliding customer`() {
    // The ownership pre-check must run before the mutating PUT: a deduplicating business server
    // could otherwise apply the caller's submitted fields to a customer someone else already owns.
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("already-claimed-id").build()
    every { customerIdOwnerStore.isClaimed("already-claimed-id") } returns true
    every { customerIdOwnerStore.verify("already-claimed-id", any(), any()) } returns false

    val request = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)

    val ex: SepException = assertThrows { sep12Service.putCustomer(jwtToken, request) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    verify(exactly = 0) { customerIntegration.putCustomer(any()) }
  }

  @Test
  fun `test put customer claims ownership by client name when one resolves`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("new-sender-id").status(Sep12Status.ACCEPTED.name).build()

    val request = Sep12PutCustomerRequest.builder().type("sep31-sender").firstName("John").build()
    val jwtToken = createJwtToken(TEST_ACCOUNT, "vibrant")

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-sender-id", "vibrant:$TEST_ACCOUNT", null)
    }
  }

  @Test
  fun `test put customer keeps memo distinct per sub-user even when a client name resolves`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("new-receiver-id").status(Sep12Status.ACCEPTED.name).build()

    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-receiver")
        .memo(TEST_MEMO)
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO", "vibrant")

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-receiver-id", "vibrant:$TEST_ACCOUNT", TEST_MEMO)
    }
  }

  @Test
  fun `test put customer keeps muxed id distinct per sub-user even when a client name resolves`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder()
        .id("new-muxed-receiver-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()

    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-receiver")
        .memo(TEST_MEMO)
        .memoType("id")
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT, "vibrant")

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim(
        "new-muxed-receiver-id",
        "vibrant:$TEST_MUXED_ACCOUNT",
        TEST_MEMO,
      )
    }
  }

  @Test
  fun `test put customer forwards the owner memo to the callback for a new muxed customer`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    val putRequestSlot = slot<PutCustomerRequest>()
    every { customerIntegration.putCustomer(capture(putRequestSlot)) } returns
      PutCustomerResponse.builder()
        .id("new-muxed-receiver-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()

    val request =
      Sep12PutCustomerRequest.builder()
        .type("sep31-receiver")
        .memo(TEST_MEMO)
        .memoType("id")
        .firstName("Jane")
        .build()
    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    assertEquals(TEST_ACCOUNT, putRequestSlot.captured.account)
    assertEquals(TEST_MEMO, putRequestSlot.captured.memo)
    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-muxed-receiver-id", TEST_MUXED_ACCOUNT, TEST_MEMO)
    }
  }

  @Test
  fun `test put customer backfills the owner memo before validation for a muxed caller who omits it`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    val putRequestSlot = slot<PutCustomerRequest>()
    every { customerIntegration.putCustomer(capture(putRequestSlot)) } returns
      PutCustomerResponse.builder()
        .id("new-muxed-receiver-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()

    val request = Sep12PutCustomerRequest.builder().type("sep31-receiver").firstName("Jane").build()
    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)

    assertDoesNotThrow { sep12Service.putCustomer(jwtToken, request) }

    assertEquals(TEST_ACCOUNT, putRequestSlot.captured.account)
    assertEquals(TEST_MEMO, putRequestSlot.captured.memo)
    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-muxed-receiver-id", TEST_MUXED_ACCOUNT, TEST_MEMO)
    }
  }

  @Test
  fun `Test put customer publishes event with null clientName when the token was never authorized as a client`() {
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    val kycUpdateEventSlot = slot<AnchorEvent>()
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("customer-id").build()
    every { eventSession.publish(capture(kycUpdateEventSlot)) } returns Unit

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

  private fun captureGetCustomerRequest(): CapturingSlot<GetCustomerRequest> {
    val slot = slot<GetCustomerRequest>()
    every { customerIntegration.getCustomer(capture(slot)) } returns
      GetCustomerResponse.builder().status(Sep12Status.NEEDS_INFO.name).build()
    return slot
  }

  @Test
  fun `test get customer without id, account or memo uses the memo of a memo token`() {
    val slot = captureGetCustomerRequest()

    sep12Service.getCustomer(
      createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
      Sep12GetCustomerRequest.builder().type("sep24").build(),
    )

    assertEquals(
      GetCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sep24")
        .build(),
      slot.captured,
    )
  }

  @Test
  fun `test get customer without id, account or memo uses the base account and muxed id of a muxed token`() {
    val slot = captureGetCustomerRequest()

    sep12Service.getCustomer(
      createJwtToken(TEST_MUXED_ACCOUNT),
      Sep12GetCustomerRequest.builder().type("sep24").build(),
    )

    assertEquals(
      GetCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sep24")
        .build(),
      slot.captured,
    )
  }

  @Test
  fun `test get customer with the token memo sent explicitly resolves the same customer`() {
    val slot = captureGetCustomerRequest()

    sep12Service.getCustomer(
      createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
      Sep12GetCustomerRequest.builder().memo(TEST_MEMO).type("sep24").build(),
    )

    assertEquals(
      GetCustomerRequest.builder()
        .account(TEST_ACCOUNT)
        .memo(TEST_MEMO)
        .memoType("id")
        .type("sep24")
        .build(),
      slot.captured,
    )
  }

  @Test
  fun `test get customer with an explicit account and no memo is refused for a memo token`() {
    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.getCustomer(
        createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
        Sep12GetCustomerRequest.builder().account(TEST_ACCOUNT).type("sep24").build(),
      )
    }

    assertEquals("The memo specified does not match the memo ID authorized via SEP-10", ex.message)
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test get customer with a memo other than the token memo is refused`() {
    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.getCustomer(
        createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
        Sep12GetCustomerRequest.builder().memo("bar").type("sep24").build(),
      )
    }

    assertEquals("The memo specified does not match the memo ID authorized via SEP-10", ex.message)
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test get customer without id, account or memo leaves the memo empty for a memo-less token`() {
    val slot = captureGetCustomerRequest()

    sep12Service.getCustomer(
      createJwtToken(TEST_ACCOUNT),
      Sep12GetCustomerRequest.builder().type("sep24").build(),
    )

    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).type("sep24").build(),
      slot.captured,
    )
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
  fun `test id path uses the ownership store when the id is already claimed`() {
    every { customerIdOwnerStore.isClaimed("claimed-id") } returns true
    every { customerIdOwnerStore.verify("claimed-id", TEST_ACCOUNT, null, false) } returns true

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("claimed-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test id path rejects via the ownership store when claimed by a different identity`() {
    every { customerIdOwnerStore.isClaimed("claimed-id") } returns true
    every { customerIdOwnerStore.verify("claimed-id", any(), any(), any()) } returns false

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("claimed-id").build()

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test id path rejects a different account under the same client name from the true owner`() {
    every { customerIdOwnerStore.isClaimed("victim-id") } returns true
    every { customerIdOwnerStore.verify("victim-id", "vibrant:$TEST_ACCOUNT", null, false) } returns
      true
    val attackerAccount = "GAXLBAY4YSF6RRZTMV2CKS4NDVCMAYVKQGV3GNPUR2WWQVEFF6UYS4XZ"
    every {
      customerIdOwnerStore.verify("victim-id", "vibrant:$attackerAccount", null, false)
    } returns false

    val victimToken = createJwtToken(TEST_ACCOUNT, "vibrant")
    val victimRequest = Sep12GetCustomerRequest.builder().id("victim-id").build()
    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(victimRequest, victimToken) }

    val attackerToken = createJwtToken(attackerAccount, "vibrant")
    val attackerRequest = Sep12GetCustomerRequest.builder().id("victim-id").build()
    val ex: SepException = assertThrows {
      sep12Service.validateGetOrPutRequest(attackerRequest, attackerToken)
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    assertEquals("not authorized for customer id", ex.message)
  }

  @Test
  fun `test id path reconciles a legacy wallet-only key for the true owner via the callback`() {
    every { customerIdOwnerStore.isClaimed("legacy-id") } returns true
    every { customerIdOwnerStore.verify("legacy-id", "vibrant:$TEST_ACCOUNT", null, false) } returns
      false
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("legacy-id").build()
    every { customerIdOwnerStore.getCreatorMemo("legacy-id") } returns null
    every {
      customerIdOwnerStore.reconcileLegacyKey(
        "legacy-id",
        "vibrant",
        null,
        "vibrant:$TEST_ACCOUNT",
        null,
      )
    } returns true

    val jwtToken = createJwtToken(TEST_ACCOUNT, "vibrant")
    val request = Sep12GetCustomerRequest.builder().id("legacy-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
  }

  @Test
  fun `test id path reconciles a legacy row that was backfilled with a real memo`() {
    // V31 preserves the original transaction's memo when backfilling a legacy row -- it is not
    // always null. The reconciliation must match against that actual stored memo, not assume it.
    every { customerIdOwnerStore.isClaimed("legacy-memo-id") } returns true
    every {
      customerIdOwnerStore.verify("legacy-memo-id", "vibrant:$TEST_ACCOUNT", TEST_MEMO, false)
    } returns false
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("legacy-memo-id").build()
    every { customerIdOwnerStore.getCreatorMemo("legacy-memo-id") } returns "555555"
    every {
      customerIdOwnerStore.reconcileLegacyKey(
        "legacy-memo-id",
        "vibrant",
        "555555",
        "vibrant:$TEST_ACCOUNT",
        TEST_MEMO,
      )
    } returns true

    val jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO", "vibrant")
    val request = Sep12GetCustomerRequest.builder().id("legacy-memo-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
  }

  @Test
  fun `test id path does not reconcile a legacy wallet-only key for a different account`() {
    every { customerIdOwnerStore.isClaimed("legacy-id") } returns true
    val attackerAccount = "GAXLBAY4YSF6RRZTMV2CKS4NDVCMAYVKQGV3GNPUR2WWQVEFF6UYS4XZ"
    every {
      customerIdOwnerStore.verify("legacy-id", "vibrant:$attackerAccount", null, false)
    } returns false
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("some-other-customer-id").build()

    val jwtToken = createJwtToken(attackerAccount, "vibrant")
    val request = Sep12GetCustomerRequest.builder().id("legacy-id").build()

    val ex: SepException = assertThrows { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    verify(exactly = 0) {
      customerIdOwnerStore.reconcileLegacyKey(any(), any(), any(), any(), any())
    }
  }

  private fun registerCustodial(name: String) {
    every { clientService.getClientConfigByName(name) } returns
      CustodialClient.builder().name(name).signingKeys(setOf(TEST_ACCOUNT, OTHER_KEY)).build()
  }

  @Test
  fun `test id path authorizes a custodial client under any of its signing keys`() {
    registerCustodial("vibrant")
    every { customerIdOwnerStore.isClaimed("custodial-id") } returns true
    every { customerIdOwnerStore.verify("custodial-id", "vibrant", TEST_MEMO, false) } returns true
    val request = Sep12GetCustomerRequest.builder().id("custodial-id").build()

    assertDoesNotThrow {
      sep12Service.validateGetOrPutRequest(
        request,
        createJwtToken("$TEST_ACCOUNT:$TEST_MEMO", "vibrant"),
      )
    }
    assertDoesNotThrow {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("custodial-id").build(),
        createJwtToken("$OTHER_KEY:$TEST_MEMO", "vibrant"),
      )
    }
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) {
      customerIdOwnerStore.reconcileLegacyKey(any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `test id path refuses a custodial caller whose memo or client differs from the owner row`() {
    registerCustodial("vibrant")
    registerCustodial("other")
    every { customerIdOwnerStore.isClaimed("custodial-id") } returns true
    every { customerIdOwnerStore.verify("custodial-id", "vibrant", TEST_MEMO, false) } returns true
    every { customerIdOwnerStore.verify("custodial-id", "vibrant", "999", false) } returns false
    every { customerIdOwnerStore.verify("custodial-id", "other", TEST_MEMO, false) } returns false
    every { customerIntegration.getCustomer(any()) } returns null

    for (token in
      listOf(
        createJwtToken("$TEST_ACCOUNT:999", "vibrant"),
        createJwtToken("$TEST_ACCOUNT:$TEST_MEMO", "other"),
      )) {
      val ex: SepException = assertThrows {
        sep12Service.validateGetOrPutRequest(
          Sep12GetCustomerRequest.builder().id("custodial-id").build(),
          token,
        )
      }
      assertInstanceOf(SepNotAuthorizedException::class.java, ex)
    }
    verify(exactly = 1) { customerIdOwnerStore.verify("custodial-id", "vibrant", "999", false) }
    verify(exactly = 1) { customerIdOwnerStore.verify("custodial-id", "other", TEST_MEMO, false) }
  }

  @Test
  fun `test id path keeps the per-account key for a non-custodial client`() {
    every { clientService.getClientConfigByName("wallet") } returns
      NonCustodialClient.builder().name("wallet").domains(setOf("wallet.org")).build()
    every { customerIdOwnerStore.isClaimed("wallet-id") } returns true
    every { customerIdOwnerStore.verify("wallet-id", "wallet:$TEST_ACCOUNT", null, false) } returns
      true
    every { customerIdOwnerStore.verify("wallet-id", "wallet:$OTHER_KEY", null, false) } returns
      false
    every { customerIntegration.getCustomer(any()) } returns null

    assertDoesNotThrow {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("wallet-id").build(),
        createJwtToken(TEST_ACCOUNT, "wallet"),
      )
    }
    val ex: SepException = assertThrows {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("wallet-id").build(),
        createJwtToken(OTHER_KEY, "wallet"),
      )
    }
    assertInstanceOf(SepNotAuthorizedException::class.java, ex)
  }

  @Test
  fun `test id path authorizes a custodial muxed token by client name and muxed id`() {
    registerCustodial("vibrant")
    every { customerIdOwnerStore.isClaimed("custodial-muxed-id") } returns true
    every { customerIdOwnerStore.verify("custodial-muxed-id", "vibrant", TEST_MEMO, false) } returns
      true
    every { customerIntegration.getCustomer(any()) } returns null

    assertDoesNotThrow {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("custodial-muxed-id").build(),
        createJwtToken(TEST_MUXED_ACCOUNT, "vibrant"),
      )
    }
    verify(exactly = 1) {
      customerIdOwnerStore.verify("custodial-muxed-id", "vibrant", TEST_MEMO, false)
    }
  }

  @Test
  fun `test put customer claims a new customer of a custodial client under the client name`() {
    registerCustodial("vibrant")
    every { customerIdOwnerStore.isClaimed(any()) } returns true
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder().id("new-custodial-id").status(Sep12Status.ACCEPTED.name).build()

    val request = Sep12PutCustomerRequest.builder().type("sep31-sender").firstName("John").build()
    assertDoesNotThrow {
      sep12Service.putCustomer(createJwtToken("$OTHER_KEY:$TEST_MEMO", "vibrant"), request)
    }

    verify(exactly = 1) {
      customerIdOwnerStore.verifyOrClaim("new-custodial-id", "vibrant", TEST_MEMO)
    }
    verify(exactly = 0) {
      customerIdOwnerStore.verifyOrClaim(any(), match { it.startsWith("vibrant:") }, any())
    }
  }

  @Test
  fun `test put customer verifies an already claimed id of a custodial client under the client name`() {
    registerCustodial("vibrant")
    every { customerIdOwnerStore.isClaimed("claimed-custodial-id") } returns true
    every { customerIdOwnerStore.verify("claimed-custodial-id", "vibrant", TEST_MEMO) } returns true
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("claimed-custodial-id").build()
    every { customerIntegration.putCustomer(any()) } returns
      PutCustomerResponse.builder()
        .id("claimed-custodial-id")
        .status(Sep12Status.ACCEPTED.name)
        .build()

    val request = Sep12PutCustomerRequest.builder().type("sep31-sender").firstName("John").build()
    assertDoesNotThrow {
      sep12Service.putCustomer(createJwtToken("$OTHER_KEY:$TEST_MEMO", "vibrant"), request)
    }

    verify(exactly = 1) {
      customerIdOwnerStore.verify("claimed-custodial-id", "vibrant", TEST_MEMO)
    }
    verify(exactly = 0) {
      customerIdOwnerStore.reconcileLegacyKey(any(), any(), any(), any(), any())
    }
  }

  @Test
  fun `test id path reconciles a legacy custodial row onto the client name key`() {
    registerCustodial("vibrant")
    every { customerIdOwnerStore.isClaimed("legacy-custodial") } returns true
    every { customerIdOwnerStore.verify("legacy-custodial", "vibrant", null, false) } returns false
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("legacy-custodial").build()
    every { customerIdOwnerStore.getCreatorMemo("legacy-custodial") } returns null
    every {
      customerIdOwnerStore.reconcileLegacyKey("legacy-custodial", "vibrant", null, "vibrant", null)
    } returns true

    assertDoesNotThrow {
      sep12Service.validateGetOrPutRequest(
        Sep12GetCustomerRequest.builder().id("legacy-custodial").build(),
        createJwtToken(TEST_ACCOUNT, "vibrant"),
      )
    }
    verify(exactly = 1) {
      customerIdOwnerStore.reconcileLegacyKey("legacy-custodial", "vibrant", null, "vibrant", null)
    }
  }

  @Test
  fun `test id path clears account and memo once the store has authorized it`() {
    every { customerIdOwnerStore.isClaimed("claimed-muxed-id") } returns true
    every {
      customerIdOwnerStore.verify("claimed-muxed-id", TEST_MUXED_ACCOUNT, TEST_MEMO, false)
    } returns true

    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("claimed-muxed-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }

    assertEquals(null, request.account)
    assertEquals(null, request.memo)
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
  }

  @Test
  fun `test id path falls back to the business server reverse lookup when the id is not yet claimed`() {
    every { customerIdOwnerStore.isClaimed("unclaimed-id") } returns false
    val ownedResponse = GetCustomerResponse()
    ownedResponse.id = "unclaimed-id"
    every { customerIntegration.getCustomer(any()) } returns ownedResponse

    val jwtToken = createJwtToken(TEST_ACCOUNT)
    val request = Sep12GetCustomerRequest.builder().id("unclaimed-id").build()

    assertDoesNotThrow { sep12Service.validateGetOrPutRequest(request, jwtToken) }
    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
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
  fun `test id path queries the callback with the base account, not the muxed address, for a muxed caller`() {
    val callbackSlot = slot<GetCustomerRequest>()
    val callbackResponse = GetCustomerResponse()
    callbackResponse.id = "customer-id"
    every { customerIntegration.getCustomer(capture(callbackSlot)) } returns callbackResponse

    val muxedToken = createJwtToken(TEST_MUXED_ACCOUNT)
    sep12Service.getCustomer(
      muxedToken,
      Sep12GetCustomerRequest.builder().id("customer-id").build(),
    )

    assertEquals(TEST_ACCOUNT, callbackSlot.captured.account)
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
    every { customerIntegration.getCustomer(any()) } returns
      GetCustomerResponse.builder().id("existing-customer-id").build()

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

  private fun captureDeleteLookup(): CapturingSlot<GetCustomerRequest> {
    val slot = slot<GetCustomerRequest>()
    every { customerIntegration.getCustomer(capture(slot)) } returns
      GetCustomerResponse.builder().id("existing-customer-id").build()
    every { customerIntegration.deleteCustomer(any()) } just Runs
    return slot
  }

  @Test
  fun `test delete through the muxed address looks up the base account and the muxed id`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(createJwtToken(TEST_MUXED_ACCOUNT), TEST_MUXED_ACCOUNT, null, null)

    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).memoType("id").build(),
      slot.captured,
    )
    verify(exactly = 1) { customerIntegration.deleteCustomer("existing-customer-id") }
  }

  @Test
  fun `test delete through the muxed address accepts the muxed id as the memo`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(
      createJwtToken(TEST_MUXED_ACCOUNT),
      TEST_MUXED_ACCOUNT,
      TEST_MEMO,
      "id",
    )

    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).memoType("id").build(),
      slot.captured,
    )
    verify(exactly = 1) { customerIntegration.deleteCustomer("existing-customer-id") }
  }

  @Test
  fun `test delete through the muxed address with another memo is refused`() {
    captureDeleteLookup()

    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.deleteCustomer(
        createJwtToken(TEST_MUXED_ACCOUNT),
        TEST_MUXED_ACCOUNT,
        "bar",
        "id",
      )
    }

    assertEquals(
      "Not authorized to delete account [$TEST_MUXED_ACCOUNT] with memo [bar]",
      ex.message
    )
    verify(exactly = 0) { customerIntegration.getCustomer(any()) }
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }
  }

  @Test
  fun `test delete with a memo and no memo type looks the customer up with memo type id`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(
      createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
      TEST_ACCOUNT,
      TEST_MEMO,
      null,
    )
    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).memoType("id").build(),
      slot.captured,
    )

    sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT), TEST_ACCOUNT, "foo_bar", null)
    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo("foo_bar").memoType("id").build(),
      slot.captured,
    )
  }

  @Test
  fun `test delete without a memo leaves the memo type empty`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT), TEST_ACCOUNT, null, null)

    assertEquals(GetCustomerRequest.builder().account(TEST_ACCOUNT).build(), slot.captured)
  }

  @Test
  fun `test delete by a caller whose token carries a memo looks the customer up with memo type id`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(
      createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
      TEST_ACCOUNT,
      TEST_MEMO,
      "text",
    )
    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).memoType("id").build(),
      slot.captured,
    )

    sep12Service.deleteCustomer(
      createJwtToken(TEST_MUXED_ACCOUNT),
      TEST_ACCOUNT,
      TEST_MEMO,
      "text",
    )
    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo(TEST_MEMO).memoType("id").build(),
      slot.captured,
    )
  }

  // SEP12DO-12
  @Test
  fun `test delete refuses a customer claimed by another client for the same account`() {
    captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", "walletA:$TEST_ACCOUNT", null, false)
    } returns true

    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.deleteCustomer(
        createJwtToken(TEST_ACCOUNT, "walletB"),
        TEST_ACCOUNT,
        null,
        null,
      )
    }

    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 1) {
      customerIdOwnerStore.verify("existing-customer-id", "walletB:$TEST_ACCOUNT", null, false)
    }
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }
  }

  // SEP12DO-12
  @Test
  fun `test delete refuses a customer claimed by another identity when the token has no client`() {
    captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", "walletA:$TEST_ACCOUNT", null, false)
    } returns true

    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT), TEST_ACCOUNT, null, null)
    }

    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }
  }

  private fun assertDeleteRefusedForCaller(
    jwtToken: WebAuthJwt,
    account: String,
    memo: String?,
    memoType: String?,
    callerKey: String,
    callerMemo: String?,
  ) {
    captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", callerKey, callerMemo, false)
    } returns false

    val ex: SepNotAuthorizedException = assertThrows {
      sep12Service.deleteCustomer(jwtToken, account, memo, memoType)
    }

    assertEquals("not authorized for customer id", ex.message)
    verify(exactly = 1) {
      customerIdOwnerStore.verify("existing-customer-id", callerKey, callerMemo, false)
    }
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }
  }

  // SEP12DO-12
  @Test
  fun `test delete refuses a customer claimed by another identity for a caller with a memo token`() {
    assertDeleteRefusedForCaller(
      createJwtToken("$TEST_ACCOUNT:$TEST_MEMO"),
      TEST_ACCOUNT,
      TEST_MEMO,
      "id",
      TEST_ACCOUNT,
      TEST_MEMO,
    )
  }

  // SEP12DO-14
  @Test
  fun `test delete refuses a customer of a memo for a memo-less token that sends the memo in the body`() {
    assertDeleteRefusedForCaller(
      createJwtToken(TEST_ACCOUNT),
      TEST_ACCOUNT,
      TEST_MEMO,
      "id",
      TEST_ACCOUNT,
      null,
    )
  }

  // SEP12DO-12
  @Test
  fun `test delete refuses a customer claimed by another identity through the muxed address`() {
    assertDeleteRefusedForCaller(
      createJwtToken(TEST_MUXED_ACCOUNT),
      TEST_MUXED_ACCOUNT,
      null,
      null,
      TEST_MUXED_ACCOUNT,
      TEST_MEMO,
    )
  }

  // SEP12DO-12
  @Test
  fun `test delete refuses a customer claimed by another identity for a muxed token on its base account`() {
    assertDeleteRefusedForCaller(
      createJwtToken(TEST_MUXED_ACCOUNT),
      TEST_ACCOUNT,
      TEST_MEMO,
      "id",
      TEST_MUXED_ACCOUNT,
      TEST_MEMO,
    )
  }

  // SEP12DO-13
  @Test
  fun `test delete lets the claimed owner delete its customer`() {
    captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", "walletA:$TEST_ACCOUNT", null, false)
    } returns true

    sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT, "walletA"), TEST_ACCOUNT, null, null)

    verify(exactly = 1) { customerIntegration.deleteCustomer("existing-customer-id") }
  }

  // SEP12DO-13
  @Test
  fun `test delete lets a muxed owner delete through its muxed address and its base account`() {
    captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", TEST_MUXED_ACCOUNT, TEST_MEMO, false)
    } returns true
    val jwtToken = createJwtToken(TEST_MUXED_ACCOUNT)

    sep12Service.deleteCustomer(jwtToken, TEST_MUXED_ACCOUNT, null, null)
    sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, "id")

    verify(exactly = 2) { customerIntegration.deleteCustomer("existing-customer-id") }
  }

  // SEP12DO-13
  @Test
  fun `test delete lets a legacy-key owner that the business server confirms delete`() {
    val lastRequest = captureDeleteLookup()
    every { customerIdOwnerStore.isClaimed("existing-customer-id") } returns true
    every {
      customerIdOwnerStore.verify("existing-customer-id", "vibrant:$TEST_ACCOUNT", null, false)
    } returns false
    every { customerIdOwnerStore.getCreatorMemo("existing-customer-id") } returns null
    every {
      customerIdOwnerStore.reconcileLegacyKey(
        "existing-customer-id",
        "vibrant",
        null,
        "vibrant:$TEST_ACCOUNT",
        null,
      )
    } returns true

    sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT, "vibrant"), TEST_ACCOUNT, null, null)

    // The last business server lookup is the reconcile's own: the caller's account, no memo, no
    // type.
    assertEquals(GetCustomerRequest.builder().account(TEST_ACCOUNT).build(), lastRequest.captured)
    verify(exactly = 1) { customerIntegration.deleteCustomer("existing-customer-id") }
  }

  @Test
  fun `test delete through the muxed address with no customer answers not found`() {
    every { customerIntegration.getCustomer(any()) } returns GetCustomerResponse()

    val ex: SepNotFoundException = assertThrows {
      sep12Service.deleteCustomer(
        createJwtToken(TEST_MUXED_ACCOUNT),
        TEST_MUXED_ACCOUNT,
        null,
        null,
      )
    }

    assertEquals("User not found.", ex.message)
    verify(exactly = 0) { customerIntegration.deleteCustomer(any()) }
  }

  @Test
  fun `test delete forwards an explicit memo type unchanged`() {
    val slot = captureDeleteLookup()

    sep12Service.deleteCustomer(createJwtToken(TEST_ACCOUNT), TEST_ACCOUNT, "foo_bar", "text")

    assertEquals(
      GetCustomerRequest.builder().account(TEST_ACCOUNT).memo("foo_bar").memoType("text").build(),
      slot.captured,
    )
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

  private fun createJwtToken(subject: String, clientName: String? = null): WebAuthJwt {
    val token =
      Sep10Jwt.of("$TEST_HOST_URL/auth", subject, issuedAt, expiresAt, "", CLIENT_DOMAIN, null)
    token.setClientName(clientName)
    return token
  }
}
