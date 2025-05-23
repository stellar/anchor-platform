package org.stellar.anchor.platform.job

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import org.stellar.anchor.api.rpc.method.NotifyTrustSetRequest
import org.stellar.anchor.ledger.Horizon
import org.stellar.anchor.platform.config.PropertyCustodyConfig
import org.stellar.anchor.platform.config.PropertyCustodyConfig.Trustline
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrust
import org.stellar.anchor.platform.data.JdbcTransactionPendingTrustRepo
import org.stellar.anchor.platform.rpc.NotifyTrustSetHandler
import org.stellar.anchor.util.GsonUtils

class TrustlineCheckJobTest {

  companion object {
    private val gson = GsonUtils.getInstance()
    private const val TX_ID = "testId"
    private const val ACCOUNT = "testToAccount"
    private const val ASSET = "testAmountOutAsset"
    private const val TX_MESSAGE = "testMessage"
  }

  @MockK(relaxed = true) private lateinit var horizon: Horizon

  @MockK(relaxed = true)
  private lateinit var transactionPendingTrustRepo: JdbcTransactionPendingTrustRepo
  @MockK(relaxed = true) private lateinit var custodyConfig: PropertyCustodyConfig
  @MockK(relaxed = true) private lateinit var notifyTrustSetHandler: NotifyTrustSetHandler

  private lateinit var trustlineCheckJob: TrustlineCheckJob

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)
    trustlineCheckJob =
      TrustlineCheckJob(horizon, transactionPendingTrustRepo, custodyConfig, notifyTrustSetHandler)
  }

  @Test
  fun test_checkNotTimedOut_trustNotConfigured() {
    val createdAt = Instant.now()
    val txnPendingTrust = JdbcTransactionPendingTrust()
    txnPendingTrust.id = TX_ID
    txnPendingTrust.createdAt = createdAt
    txnPendingTrust.asset = ASSET
    txnPendingTrust.account = ACCOUNT
    val trustline = Trustline()
    trustline.checkDuration = 10

    every { transactionPendingTrustRepo.findAll() } returns listOf(txnPendingTrust)
    every { custodyConfig.trustline } returns trustline
    every { horizon.hasTrustline(ACCOUNT, ASSET) } returns false

    trustlineCheckJob.checkTrust()

    verify(exactly = 0) { notifyTrustSetHandler.handle(any()) }
    verify(exactly = 0) { transactionPendingTrustRepo.delete(any()) }
  }

  @Test
  fun test_checkNotTimedOut_trustConfigured() {
    val createdAt = Instant.now()
    val txnPendingTrust = JdbcTransactionPendingTrust()
    txnPendingTrust.id = TX_ID
    txnPendingTrust.createdAt = createdAt
    txnPendingTrust.asset = ASSET
    txnPendingTrust.account = ACCOUNT
    val trustline = Trustline()
    trustline.checkDuration = 10
    val notifyTrustSetRequestCapture = slot<NotifyTrustSetRequest>()

    every { transactionPendingTrustRepo.findAll() } returns listOf(txnPendingTrust)
    every { custodyConfig.trustline } returns trustline
    every { horizon.hasTrustline(ACCOUNT, ASSET) } returns true
    every { notifyTrustSetHandler.handle(capture(notifyTrustSetRequestCapture)) } returns null

    trustlineCheckJob.checkTrust()

    verify(exactly = 1) { transactionPendingTrustRepo.delete(txnPendingTrust) }

    val expectedNotifyTrustSetRequest = NotifyTrustSetRequest()
    expectedNotifyTrustSetRequest.transactionId = TX_ID
    expectedNotifyTrustSetRequest.isSuccess = true

    JSONAssert.assertEquals(
      gson.toJson(expectedNotifyTrustSetRequest),
      gson.toJson(notifyTrustSetRequestCapture.captured),
      JSONCompareMode.STRICT
    )
  }

  @Test
  fun test_checkTimedOut() {
    val createdAt = Instant.now().minus(11, ChronoUnit.HOURS)
    val txnPendingTrust = JdbcTransactionPendingTrust()
    txnPendingTrust.id = TX_ID
    txnPendingTrust.createdAt = createdAt
    txnPendingTrust.asset = ASSET
    txnPendingTrust.account = ACCOUNT
    val trustline = Trustline()
    trustline.checkDuration = 10
    trustline.timeoutMessage = TX_MESSAGE
    val notifyTrustSetRequestCapture = slot<NotifyTrustSetRequest>()

    every { transactionPendingTrustRepo.findAll() } returns listOf(txnPendingTrust)
    every { custodyConfig.trustline } returns trustline
    every { notifyTrustSetHandler.handle(capture(notifyTrustSetRequestCapture)) } returns null

    trustlineCheckJob.checkTrust()

    verify(exactly = 1) { transactionPendingTrustRepo.delete(txnPendingTrust) }

    val expectedNotifyTrustSetRequest = NotifyTrustSetRequest()
    expectedNotifyTrustSetRequest.transactionId = TX_ID
    expectedNotifyTrustSetRequest.message = TX_MESSAGE
    expectedNotifyTrustSetRequest.isSuccess = false

    JSONAssert.assertEquals(
      gson.toJson(expectedNotifyTrustSetRequest),
      gson.toJson(notifyTrustSetRequestCapture.captured),
      JSONCompareMode.STRICT
    )
  }
}
