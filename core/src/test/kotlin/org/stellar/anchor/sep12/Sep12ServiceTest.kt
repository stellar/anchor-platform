package org.stellar.anchor.sep12

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertDoesNotThrow
import org.stellar.anchor.api.callback.CustomerIntegration
import org.stellar.anchor.api.exception.*
import org.stellar.anchor.api.sep.sep12.*
import org.stellar.anchor.auth.JwtToken

class Sep12ServiceTest {
  companion object {
    private const val TEST_ACCOUNT = "GBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUIZG"
    private const val TEST_MEMO = "123456"
    private const val TEST_MUXED_ACCOUNT =
      "MBFZNZTFSI6TWLVAID7VOLCIFX2PMUOS2X7U6H4TNK4PAPSHPWMMUAAAAAAAAAPCIA2IM"
    private const val CLIENT_DOMAIN = "demo-wallet.stellar.org"
    private const val TEST_HOST_URL = "http://localhost:8080"
  }

  private val issuedAt = Instant.now().epochSecond
  private val expiresAt = issuedAt + 9000

  private lateinit var sep12Service: Sep12Service
  @MockK(relaxed = true) private lateinit var customerIntegration: CustomerIntegration

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    sep12Service = Sep12Service(customerIntegration)
  }

  @AfterEach
  fun tearDown() {
    clearAllMocks()
    unmockkAll()
  }

  @Test
  fun test_validateRequestAndTokenAccounts() {
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
  }

  @Test
  fun test_validateRequestAndTokenMemos() {
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
  fun test_updateRequestMemoAndMemoType() {
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

  @Test
  fun test_putCustomer() {
    val mockCustomerResponse = Sep12PutCustomerResponse()
    val putRequestSlot = slot<Sep12PutCustomerRequest>()
    every { customerIntegration.putCustomer(capture(putRequestSlot)) } returns mockCustomerResponse

    val mockPutRequest = Sep12PutCustomerRequest.builder().build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    var sep12PutCustomerResponse: Sep12PutCustomerResponse? = null
    assertDoesNotThrow {
      sep12PutCustomerResponse = sep12Service.putCustomer(jwtToken, mockPutRequest)
    }

    verify(exactly = 1) { customerIntegration.putCustomer(any()) }
    assertEquals(TEST_ACCOUNT, mockPutRequest.account)
    assertEquals(mockCustomerResponse, sep12PutCustomerResponse)

    val wantPutRequest = Sep12PutCustomerRequest.builder().account(TEST_ACCOUNT).build()
    assertEquals(wantPutRequest, putRequestSlot.captured)
  }

  @Test
  fun test_getCustomer() {
    val mockCustomerResponse = Sep12GetCustomerResponse()
    val getRequestSlot = slot<Sep12GetCustomerRequest>()
    every { customerIntegration.getCustomer(capture(getRequestSlot)) } returns mockCustomerResponse

    val mockGetRequest = Sep12GetCustomerRequest.builder().build()
    val jwtToken = createJwtToken(TEST_ACCOUNT)
    var sep12GetCustomerResponse: Sep12GetCustomerResponse? = null
    assertDoesNotThrow {
      sep12GetCustomerResponse = sep12Service.getCustomer(jwtToken, mockGetRequest)
    }

    verify(exactly = 1) { customerIntegration.getCustomer(any()) }
    assertEquals(TEST_ACCOUNT, mockGetRequest.account)
    assertEquals(mockCustomerResponse, sep12GetCustomerResponse)

    val wantGetRequest = Sep12GetCustomerRequest.builder().account(TEST_ACCOUNT).build()
    assertEquals(wantGetRequest, getRequestSlot.captured)
  }

  @Test
  fun test_deleteCustomer_validation() {
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
  fun test_deleteCustomer_handleCustomerIntegration() {
    val deleteCustomerIdSlot = slot<String>()
    every { customerIntegration.deleteCustomer(capture(deleteCustomerIdSlot)) } just Runs

    // attempting to delete a non-existent customer returns 404
    val mockNoCustomerFound = Sep12GetCustomerResponse()
    every { customerIntegration.getCustomer(any()) } returns mockNoCustomerFound

    val jwtToken = createJwtToken("$TEST_ACCOUNT:$TEST_MEMO")
    val ex: AnchorException = assertThrows {
      sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null)
    }
    assertInstanceOf(SepNotFoundException::class.java, ex)
    assertEquals("User not found.", ex.message)
    verify(exactly = 2) { customerIntegration.getCustomer(any()) }

    // attempting to delete a valid customer succeeds
    val mockValidCustomerFound = Sep12GetCustomerResponse()
    mockValidCustomerFound.id = "customer-id"
    every { customerIntegration.getCustomer(any()) } returns mockValidCustomerFound
    assertDoesNotThrow { sep12Service.deleteCustomer(jwtToken, TEST_ACCOUNT, TEST_MEMO, null) }
    verify(exactly = 4) { customerIntegration.getCustomer(any()) }
    verify(exactly = 2) { customerIntegration.deleteCustomer(any()) }

    val wantDeleteCustomerId = "customer-id"
    assertEquals(wantDeleteCustomerId, deleteCustomerIdSlot.captured)
  }

  private fun createJwtToken(subject: String): JwtToken {
    return JwtToken.of("$TEST_HOST_URL/auth", subject, issuedAt, expiresAt, "", CLIENT_DOMAIN)
  }
}
