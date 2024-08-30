package org.stellar.anchor.platform.event

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.util.*
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.stellar.anchor.LockAndMockStatic
import org.stellar.anchor.LockAndMockTest
import org.stellar.anchor.api.event.AnchorEvent
import org.stellar.anchor.api.platform.GetTransactionResponse
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.api.sep.SepTransactionStatus
import org.stellar.anchor.api.sep.sep12.Sep12GetCustomerResponse
import org.stellar.anchor.api.sep.sep24.TransactionResponse
import org.stellar.anchor.api.sep.sep6.Sep6TransactionResponse
import org.stellar.anchor.asset.AssetService
import org.stellar.anchor.client.ClientConfig.CallbackUrls
import org.stellar.anchor.client.CustodialClientConfig
import org.stellar.anchor.platform.config.PropertySecretConfig
import org.stellar.anchor.platform.service.Sep24MoreInfoUrlConstructor
import org.stellar.anchor.platform.service.Sep6MoreInfoUrlConstructor
import org.stellar.anchor.platform.utils.setupMock
import org.stellar.anchor.sep24.Sep24Helper
import org.stellar.anchor.sep24.Sep24Helper.fromTxn
import org.stellar.anchor.sep24.Sep24TransactionStore
import org.stellar.anchor.sep31.Sep31TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionStore
import org.stellar.anchor.sep6.Sep6TransactionUtils
import org.stellar.anchor.util.StringHelper.json
import org.stellar.sdk.KeyPair

@ExtendWith(LockAndMockTest::class)
class ClientStatusCallbackHandlerTest {
  private lateinit var handler: ClientStatusCallbackHandler
  private lateinit var secretConfig: PropertySecretConfig
  private lateinit var clientConfig: CustodialClientConfig
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
      CustodialClientConfig.builder()
        .name("circle")
        .signingKeys(
          setOf(
            "GBI2IWJGR4UQPBIKPP6WG76X5PHSD2QTEBGIP6AZ3ZXWV46ZUSGNEGN2",
            "GACYKME36AI6UYAV7A5ZUA6MG4C4K2VAPNYMW5YLOM6E7GS6FSHDPV4F",
          )
        )
        .callbackUrl("https://callback.circle.com/api/v1/anchor/callback")
        .callbackUrls(CallbackUrls.builder().build())
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
    event.transaction.sep = PlatformTransactionData.Sep.SEP_24
    event.transaction.kind = PlatformTransactionData.Kind.DEPOSIT
    event.transaction.status = SepTransactionStatus.COMPLETED

    handler =
      ClientStatusCallbackHandler(
        secretConfig,
        clientConfig,
        sep6TransactionStore,
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
      ClientStatusCallbackHandler.buildHttpRequest(signer, payload, clientConfig.callbackUrl)
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
  fun `test getCallbackUrl fallback`() {
    clientConfig.callbackUrls.sep6 = null
    val url = handler.getCallbackUrl(event)

    Assertions.assertEquals(clientConfig.callbackUrl, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-6 event`() {
    event.transaction.sep = PlatformTransactionData.Sep.SEP_6
    clientConfig.callbackUrls.sep6 = "https://callback.circle.com/api/v1/anchor/callback/sep6"
    val url = handler.getCallbackUrl(event)

    Assertions.assertEquals(clientConfig.callbackUrls.sep6, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-24 event`() {
    event.transaction.sep = PlatformTransactionData.Sep.SEP_24
    clientConfig.callbackUrls.sep24 = "https://callback.circle.com/api/v1/anchor/callback/sep24"
    val url = handler.getCallbackUrl(event)

    Assertions.assertEquals(clientConfig.callbackUrls.sep24, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-31 event`() {
    event.transaction.sep = PlatformTransactionData.Sep.SEP_31
    clientConfig.callbackUrls.sep31 = "https://callback.circle.com/api/v1/anchor/callback/sep31"
    val url = handler.getCallbackUrl(event)

    Assertions.assertEquals(clientConfig.callbackUrls.sep31, url)
  }

  @Test
  fun `test getCallbackUrl with SEP-12 event`() {
    event.transaction = null
    event.customer = Sep12GetCustomerResponse.builder().build()
    clientConfig.callbackUrls.sep12 = "https://callback.circle.com/api/v1/anchor/callback/sep12"
    val url = handler.getCallbackUrl(event)

    Assertions.assertEquals(clientConfig.callbackUrls.sep12, url)
  }

  @Test
  fun `test buildHttpRequest with no callback URLs defined`() {
    clientConfig.callbackUrl = null
    clientConfig.callbackUrls.sep6 = null
    clientConfig.callbackUrls.sep24 = null
    clientConfig.callbackUrls.sep31 = null
    clientConfig.callbackUrls.sep12 = null

    val request = handler.buildHttpRequest(signer, event)
    Assertions.assertNull(request)
  }
}
