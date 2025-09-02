package org.stellar.anchor.platform.event

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.util.*
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData.Kind
import org.stellar.anchor.api.platform.PlatformTransactionData.Sep.*
import org.stellar.anchor.api.sep.SepTransactionStatus.COMPLETED
import org.stellar.anchor.api.sep.SepTransactionStatus.PENDING_USR_TRANSFER_START
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.api.sep.sep24.TransactionResponse
import org.stellar.anchor.api.sep.sep6.Sep6TransactionResponse
import org.stellar.anchor.api.shared.Amount
import org.stellar.anchor.api.shared.FeeDetails
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.client.ClientConfig.CallbackUrls
import org.stellar.anchor.client.CustodialClient
import org.stellar.anchor.platform.config.PropertySecretConfig
import org.stellar.anchor.platform.service.Sep24MoreInfoUrlConstructor
import org.stellar.anchor.platform.service.Sep6MoreInfoUrlConstructor
import org.stellar.anchor.platform.utils.setupMock
import org.stellar.anchor.sep24.Sep24Helper
import org.stellar.anchor.sep24.Sep24Helper.fromTxn
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6Transaction
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionUtils
import org.stellar.anchor.util.StringHelper.json
import org.stellar.sdk.KeyPair

@ExtendWith(LockAndMockTest::class)
class ClientStatusCallbackHandlerTest {
  private lateinit var handler: ClientStatusCallbackHandler
  private lateinit var secretConfig: PropertySecretConfig
  private lateinit var clientConfig: CustodialClient
  private lateinit var signer: KeyPair
  private lateinit var ts: String
  private lateinit var event: AnchorEvent

  @MockK(relaxed = true) private lateinit var sep6TransactionStore: Sep6TransactionStore
  @MockK(relaxed = true) private lateinit var sep24TransactionStore: Sep24TransactionStore
  @MockK(relaxed = true) private lateinit var sep31TransactionStore: Sep31TransactionStore
  @MockK(relaxed = true) private lateinit var assetService: AssetService
  @MockK(relaxed = true) lateinit var sep6MoreInfoUrlConstructor: Sep6MoreInfoUrlConstructor
  @MockK(relaxed = true) lateinit var sep24MoreInfoUrlConstructor: Sep24MoreInfoUrlConstructor

  @BeforeEach
  fun setUp() {
    clientConfig =
      CustodialClient.builder()
        .name("circle")
        .signingKeys(
          setOf(
            "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
            "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F",
          )
        )
        .callbackUrls(
          CallbackUrls.builder()
            .sep6("https://callback.circle.com/api/v1/anchor/callback/sep6")
            .sep24("https://callback.circle.com/api/v1/anchor/callback/sep24")
            .sep31("https://callback.circle.com/api/v1/anchor/callback/sep31")
            .sep12("https://callback.circle.com/api/v1/anchor/callback/sep12")
            .build()
        )
        .allowAnyDestination(false)
        .destinationAccounts(emptySet())
        .build()
    sep6TransactionStore = mockk<Sep6TransactionStore>()
    every { sep6TransactionStore.findByTransactionId(any()) } returns null

    sep24TransactionStore = mockk<Sep24TransactionStore>()
    sep31TransactionStore = mockk<Sep31TransactionStore>()
    every { sep24TransactionStore.findByTransactionId(any()) } returns null

    assetService = mockk<AssetService>()
    every { assetService.getAsset(null, null) } returns null

    assetService = mockk<AssetService>()
    sep6MoreInfoUrlConstructor = mockk<Sep6MoreInfoUrlConstructor>()
    sep24MoreInfoUrlConstructor = mockk<Sep24MoreInfoUrlConstructor>()
    every { sep6MoreInfoUrlConstructor.construct(any(), any()) } returns "https://example.com"
    every { sep24MoreInfoUrlConstructor.construct(any(), any()) } returns "https://example.com"

    secretConfig = mockk()
    secretConfig.setupMock()
    signer = KeyPair.fromSecretSeed(secretConfig.sep10SigningSeed)

    ts = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()).toString()
    event = AnchorEvent()
    event.transaction = GetTransactionResponse()
    event.transaction.sep = SEP_24
    event.transaction.kind = Kind.DEPOSIT
    event.transaction.status = COMPLETED

    handler =
      ClientStatusCallbackHandler(
        secretConfig,
        clientConfig,
        assetService,
        sep6MoreInfoUrlConstructor,
        sep24MoreInfoUrlConstructor
      )
  }

  @Test
  @LockAndMockStatic([Sep24Helper::class, Sep6TransactionUtils::class])
  fun `test verify request signature`() {
    // header example: "X-Stellar-Signature": "t=....., s=......"
    // Get the signature from request

    every { Sep6TransactionUtils.fromTxn(any(), any(), any()) } returns
      mockk<Sep6TransactionResponse>()
    every { fromTxn(any(), any(), any(), any()) } returns mockk<TransactionResponse>()

    val payload = json(event)
    val request =
      ClientStatusCallbackHandler.buildHttpRequest(signer, payload, clientConfig.callbackUrls.sep6)
    val requestHeader = request.headers["Signature"]
    val parsedSignature = requestHeader?.split(", ")?.get(1)?.substring(2)
    val decodedSignature = Base64.getDecoder().decode(parsedSignature)

    // re-compose the signature from request info for verify
    val tsInRequest = requestHeader?.split(", ")?.get(0)?.substring(2)
    val payloadToVerify = tsInRequest + "." + request.url.host + "." + payload
    val signatureToVerify = signer.sign(payloadToVerify.toByteArray())

    Assertions.assertArrayEquals(decodedSignature, signatureToVerify)
  }

  @Test
  fun `test getCallbackUrl with SEP-6 event`() {
    event.transaction.sep = SEP_6
    clientConfig.callbackUrls.sep6 = "https://callback.circle.com/api/v1/anchor/callback/sep6"
    val url = handler.getCallbackUrl(event)

    assertEquals(clientConfig.callbackUrls.sep6, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-24 event`() {
    event.transaction.sep = SEP_24
    clientConfig.callbackUrls.sep24 = "https://callback.circle.com/api/v1/anchor/callback/sep24"
    val url = handler.getCallbackUrl(event)

    assertEquals(clientConfig.callbackUrls.sep24, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-31 event`() {
    event.transaction.sep = SEP_31
    clientConfig.callbackUrls.sep31 = "https://callback.circle.com/api/v1/anchor/callback/sep31"
    val url = handler.getCallbackUrl(event)

    assertEquals(clientConfig.callbackUrls.sep31, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-12 event`() {
    event.transaction = null
    event.customer = Sep12GetCustomerResponse.builder().build()
    clientConfig.callbackUrls.sep12 = "https://callback.circle.com/api/v1/anchor/callback/sep12"
    val url = handler.getCallbackUrl(event)

    assertEquals(clientConfig.callbackUrls.sep12, url)
  }

  @Test
  fun `test buildHttpRequest with no callback URLs defined`() {
    clientConfig.callbackUrls.sep6 = null
    clientConfig.callbackUrls.sep24 = null
    clientConfig.callbackUrls.sep31 = null
    clientConfig.callbackUrls.sep12 = null

    val request = handler.buildHttpRequest(signer, event)
    Assertions.assertNull(request)
  }

  @Test
  fun `fromSep6Txn should map GetTransactionResponse to Sep6Transaction correctly`() {
    // Arrange
    val amountIn = Amount("100.0", "USD")
    val amountOut = Amount("99.0", "USD")
    val feeDetails = FeeDetails("1.0", "USD", emptyList())
    val txnResponse =
      GetTransactionResponse.builder()
        .id("test-id")
        .sep(SEP_6)
        .kind(Kind.WITHDRAWAL)
        .status(PENDING_USR_TRANSFER_START)
        .startedAt(java.time.Instant.now())
        .completedAt(java.time.Instant.now())
        .transferReceivedAt(java.time.Instant.now())
        .type("bank_account")
        .amountIn(amountIn)
        .amountOut(amountOut)
        .feeDetails(feeDetails)
        .amountExpected(amountIn)
        .sourceAccount("source-account")
        .destinationAccount("dest-account")
        .externalTransactionId("ext-id")
        .memo("memo")
        .memoType("id")
        .clientDomain("client.com")
        .quoteId("quote-id")
        .message("message")
        .build()

    // Act
    val sep6Txn: Sep6Transaction = ClientStatusCallbackHandler.fromSep6Txn(txnResponse)

    // Assert
    assertEquals("test-id", sep6Txn.id)
    assertEquals("test-id", sep6Txn.transactionId)
    assertEquals("ext-id", sep6Txn.externalTransactionId)
    assertEquals("pending_user_transfer_start", sep6Txn.status)
    assertEquals("bank_account", sep6Txn.type)
    assertEquals("100.0", sep6Txn.amountIn)
    assertEquals("USD", sep6Txn.amountInAsset)
    assertEquals("99.0", sep6Txn.amountOut)
    assertEquals("USD", sep6Txn.amountOutAsset)
    assertEquals(feeDetails, sep6Txn.feeDetails)
    assertEquals("100.0", sep6Txn.amountExpected)
    assertEquals("source-account", sep6Txn.fromAccount)
    assertEquals("dest-account", sep6Txn.toAccount)
    assertEquals("memo", sep6Txn.memo)
    assertEquals("id", sep6Txn.memoType)
    assertEquals("client.com", sep6Txn.clientDomain)
    assertEquals("quote-id", sep6Txn.quoteId)
    assertEquals("message", sep6Txn.message)
  }

  @Test
  fun `fromSep24Txn should map GetTransactionResponse to Sep24Transaction correctly`() {
    // Arrange
    val amountIn = Amount("200.0", "USD")
    val amountOut = Amount("198.0", "USD")
    val feeDetails = FeeDetails("2.0", "USD", emptyList())
    val txnResponse =
      GetTransactionResponse.builder()
        .id("sep24-id")
        .sep(SEP_24)
        .kind(Kind.DEPOSIT)
        .status(PENDING_USR_TRANSFER_START)
        .startedAt(java.time.Instant.now())
        .completedAt(java.time.Instant.now())
        .transferReceivedAt(java.time.Instant.now())
        .amountIn(amountIn)
        .amountOut(amountOut)
        .feeDetails(feeDetails)
        .amountExpected(amountIn)
        .sourceAccount("source-account")
        .destinationAccount("dest-account")
        .externalTransactionId("ext-id")
        .memo("memo")
        .memoType("id")
        .clientDomain("client.com")
        .quoteId("quote-id")
        .message("message")
        .build()

    // Act
    val sep24Txn = ClientStatusCallbackHandler.fromSep24Txn(txnResponse)

    // Assert
    assertEquals("sep24-id", sep24Txn.id)
    assertEquals("sep24-id", sep24Txn.transactionId)
    assertEquals("ext-id", sep24Txn.externalTransactionId)
    assertEquals("pending_user_transfer_start", sep24Txn.status)
    assertEquals("200.0", sep24Txn.amountIn)
    assertEquals("USD", sep24Txn.amountInAsset)
    assertEquals("198.0", sep24Txn.amountOut)
    assertEquals("USD", sep24Txn.amountOutAsset)
    assertEquals(feeDetails, sep24Txn.feeDetails)
    assertEquals("200.0", sep24Txn.amountExpected)
    assertEquals("source-account", sep24Txn.fromAccount)
    assertEquals("dest-account", sep24Txn.toAccount)
    assertEquals("memo", sep24Txn.memo)
    assertEquals("id", sep24Txn.memoType)
    assertEquals("client.com", sep24Txn.clientDomain)
    assertEquals("quote-id", sep24Txn.quoteId)
    assertEquals("message", sep24Txn.message)
  }

  @Test
  fun `fromSep31Txn should map GetTransactionResponse to Sep31Transaction correctly`() {
    // Arrange
    val amountIn = Amount("300.0", "USD")
    val amountOut = Amount("295.0", "USD")
    val feeDetails = FeeDetails("5.0", "USD", emptyList())
    val txnResponse =
      GetTransactionResponse.builder()
        .id("sep31-id")
        .sep(SEP_31)
        .kind(Kind.RECEIVE)
        .status(PENDING_USR_TRANSFER_START)
        .startedAt(java.time.Instant.now())
        .completedAt(java.time.Instant.now())
        .transferReceivedAt(java.time.Instant.now())
        .amountIn(amountIn)
        .amountOut(amountOut)
        .feeDetails(feeDetails)
        .amountExpected(amountIn)
        .sourceAccount("source-account")
        .destinationAccount("dest-account")
        .externalTransactionId("ext-id")
        .memo("memo")
        .memoType("id")
        .clientDomain("client.com")
        .quoteId("quote-id")
        .message("message")
        .build()

    // Act
    val sep31Txn = ClientStatusCallbackHandler.fromSep31Txn(txnResponse)

    // Assert
    assertEquals("sep31-id", sep31Txn.id)
    assertEquals("ext-id", sep31Txn.externalTransactionId)
    assertEquals("pending_user_transfer_start", sep31Txn.status)
    assertEquals("300.0", sep31Txn.amountIn)
    assertEquals("USD", sep31Txn.amountInAsset)
    assertEquals("295.0", sep31Txn.amountOut)
    assertEquals("USD", sep31Txn.amountOutAsset)
    assertEquals(feeDetails, sep31Txn.feeDetails)
    assertEquals("300.0", sep31Txn.amountExpected)
    assertEquals("source-account", sep31Txn.fromAccount)
    assertEquals("dest-account", sep31Txn.toAccount)
    assertEquals("client.com", sep31Txn.clientDomain)
    assertEquals("quote-id", sep31Txn.quoteId)
    assertEquals("message", sep31Txn.requiredInfoMessage)
  }
}
