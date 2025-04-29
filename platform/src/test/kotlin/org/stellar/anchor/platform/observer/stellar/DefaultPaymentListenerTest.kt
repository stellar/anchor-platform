package org.stellar.anchor.platform.observer.stellar

import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.*
import org.stellar.anchor.util.AssetHelper.*
import org.stellar.sdk.xdr.Asset
import org.stellar.sdk.xdr.AssetType.ASSET_TYPE_POOL_SHARE
import org.stellar.sdk.xdr.Memo
import org.stellar.sdk.xdr.MemoType.MEMO_TEXT
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.XdrString

class DefaultPaymentListenerTest {
  @MockK(relaxed = true)
  private lateinit var paymentObservingAccountsManager: PaymentObservingAccountsManager
  @MockK(relaxed = true) private lateinit var sep31TransactionStore: JdbcSep31TransactionStore
  @MockK(relaxed = true) private lateinit var sep24TransactionStore: JdbcSep24TransactionStore
  @MockK(relaxed = true) private lateinit var sep6TransactionStore: JdbcSep6TransactionStore
  @MockK(relaxed = true) private lateinit var platformApiClient: PlatformApiClient
  @MockK(relaxed = true) private lateinit var rpcConfig: RpcConfig

  private lateinit var paymentListener: DefaultPaymentListener

  private lateinit var xdrMemoText: Memo

  @BeforeEach
  fun setup() {
    MockKAnnotations.init(this, relaxUnitFun = true)

    xdrMemoText = Memo()
    xdrMemoText.discriminant = MEMO_TEXT
    xdrMemoText.text = XdrString("text")

    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns true

    paymentListener =
      spyk(
        DefaultPaymentListener(
          paymentObservingAccountsManager,
          sep31TransactionStore,
          sep24TransactionStore,
          sep6TransactionStore,
          platformApiClient,
          rpcConfig,
        )
      )
  }

  @Test
  fun `test If the account is not monitored, processAndDispatchLedgerPayment should not be called`() {
    every { paymentObservingAccountsManager.lookupAndUpdate(any()) } returns false
    val ledgerTransaction = createTestLedgerTransaction()

    paymentListener.onReceived(ledgerTransaction)

    verify(exactly = 0) { paymentListener.processAndDispatchLedgerPayment(any(), any()) }
  }

  @Test
  fun `test validate()`() {
    var ledgerTransaction = createTestLedgerTransaction()
    // empty hash
    ledgerTransaction.hash = null
    var testPayment = ledgerTransaction.operations[0].paymentOperation

    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // null memo
    ledgerTransaction = createTestLedgerTransaction()
    ledgerTransaction.memo = null
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // empty memo
    ledgerTransaction = createTestLedgerTransaction()
    ledgerTransaction.memo = Memo()
    ledgerTransaction.memo.discriminant = MEMO_TEXT
    ledgerTransaction.memo.text = XdrString("")
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // unsupported asset type
    ledgerTransaction = createTestLedgerTransaction()
    testPayment = ledgerTransaction.operations[0].paymentOperation
    testPayment.asset = Asset()
    testPayment.asset.discriminant = ASSET_TYPE_POOL_SHARE
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))
  }

  @Test
  fun `test unsupported assets should not trigger any process`() {
    val ledgerTransaction = createTestLedgerTransaction()
    val poolShareAsset = Asset()
    poolShareAsset.discriminant = ASSET_TYPE_POOL_SHARE
    ledgerTransaction.operations[0].paymentOperation.asset = poolShareAsset

    paymentListener.onReceived(ledgerTransaction)

    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }
  }

  @Test
  fun `test handleSep31Transaction are called properly`() {
    val ledgerTransaction = createTestLedgerTransaction()
    xdrMemoText.text = XdrString("my_memo_1")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns JdbcSep31Transaction()

    every { paymentListener.handleSep31Transaction(any(), any(), any()) } answers {}

    paymentListener.onReceived(ledgerTransaction)

    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_1",
        "pending_sender",
      )
    }

    verify(exactly = 1) { paymentListener.handleSep31Transaction(ledgerTransaction, any(), any()) }

    assertEquals("my_memo_1", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)
  }

  @Test
  fun `test handleSep24Transaction are called properly`() {
    val ledgerTransaction = createTestLedgerTransaction()
    xdrMemoText.text = XdrString("my_memo_1")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    every {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns JdbcSep24Transaction()

    every { paymentListener.handleSep24Transaction(any(), any(), any()) } answers {}

    paymentListener.onReceived(ledgerTransaction)

    verify(exactly = 1) {
      sep24TransactionStore.findOneByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_1",
        "pending_user_transfer_start",
      )
    }

    verify(exactly = 1) { paymentListener.handleSep24Transaction(ledgerTransaction, any(), any()) }

    assertEquals("my_memo_1", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_user_transfer_start", slotStatus.captured)
  }

  @Test
  fun `test handleSep6Transaction are called properly`() {
    val ledgerTransaction = createTestLedgerTransaction()
    xdrMemoText.text = XdrString("my_memo_1")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every { sep24TransactionStore.findOneByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns JdbcSep6Transaction()

    every { paymentListener.handleSep6Transaction(any(), any(), any()) } answers {}

    paymentListener.onReceived(ledgerTransaction)

    verify(exactly = 1) {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_1",
        "pending_user_transfer_start",
      )
    }

    verify(exactly = 1) { paymentListener.handleSep6Transaction(ledgerTransaction, any(), any()) }

    assertEquals("my_memo_1", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_user_transfer_start", slotStatus.captured)
  }

  private fun createTestLedgerTransaction(): LedgerTransaction {
    val testAssetFoo =
      org.stellar.sdk.Asset.create(
        null,
        "FOO",
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      )

    return LedgerTransaction.builder()
      .hash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
      .memo(xdrMemoText)
      .operations(
        listOf(
          LedgerOperation.builder()
            .type(OperationType.PAYMENT)
            .paymentOperation(
              LedgerTransaction.LedgerPaymentOperation.builder()
                .asset(testAssetFoo.toXdr())
                .amount(1)
                .sourceAccount("GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5")
                .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
                .build()
            )
            .build()
        )
      )
      .build()
  }

  @Test
  fun `test if findByStellarAccountIdAndMemoAndStatus throws an exception, we shouldn't trigger an event`() {
    val ledgerTransaction = createTestLedgerTransaction()
    xdrMemoText.text = XdrString("my_memo_3")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } throws RuntimeException("Something went wrong")

    paymentListener.onReceived(ledgerTransaction)

    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_sender",
      )
    }
    assertEquals("my_memo_3", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)
  }

  @Test
  fun `test If asset code from the fetched tx is different, don't trigger event`() {
    val ledgerTransaction = createTestLedgerTransaction()
    xdrMemoText.text = XdrString("my_memo_4")
    ledgerTransaction.memo = xdrMemoText
    val sep31TxMock = JdbcSep31Transaction()
    sep31TxMock.amountInAsset = "BAR"
    sep31TxMock.amountIn = "1"

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()
    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns sep31TxMock
    paymentListener.onReceived(ledgerTransaction)
    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_4",
        "pending_sender",
      )
    }
    assertEquals("my_memo_4", slotMemo.captured)
    assertEquals("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364", slotAccount.captured)
    assertEquals("pending_sender", slotStatus.captured)
  }

  @Test
  fun `test handleSep31Transaction`() {
    val testTxn = createTestLedgerTransaction()
    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep31Transaction()
    testJdbcSepTransaction.id = "123"

    every {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    } answers {}

    paymentListener.handleSep31Transaction(testTxn, testPayment, testJdbcSepTransaction)

    verify(exactly = 1) {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    }

    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsReceived(
        "123",
        testTxn.hash,
        fromXdrAmount(testPayment.amount).toString(),
        any(),
      )
    }
  }

  @Test
  fun `test handleSep24Transaction WITHDRAWAL`() {
    val testTxn = createTestLedgerTransaction()
    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep24Transaction()
    testJdbcSepTransaction.id = "123"

    // Test WITHDRAWAL
    testJdbcSepTransaction.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    every {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    } answers {}

    paymentListener.handleSep24Transaction(testTxn, testPayment, testJdbcSepTransaction)

    verify(exactly = 1) {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    }
    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsReceived(
        "123",
        testTxn.hash,
        fromXdrAmount(testPayment.amount).toString(),
        any(),
      )
    }
  }

  @Test
  fun `test handleSep24Transaction DEPOSIT`() {
    val testTxn = createTestLedgerTransaction()
    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep24Transaction()
    testJdbcSepTransaction.id = "123"

    // Test WITHDRAWAL
    testJdbcSepTransaction.kind = PlatformTransactionData.Kind.DEPOSIT.kind

    every {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    } answers {}

    paymentListener.handleSep24Transaction(testTxn, testPayment, testJdbcSepTransaction)

    verify(exactly = 1) {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    }
    verify(exactly = 1) { platformApiClient.notifyOnchainFundsSent("123", testTxn.hash, any()) }
  }

  @Test
  fun `test handleSep6Transaction WITHDRAWAL`() {
    val testTxn = createTestLedgerTransaction()
    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep6Transaction()
    testJdbcSepTransaction.id = "123"

    // Test WITHDRAWAL
    testJdbcSepTransaction.kind = PlatformTransactionData.Kind.WITHDRAWAL.kind

    every {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    } answers {}

    paymentListener.handleSep6Transaction(testTxn, testPayment, testJdbcSepTransaction)

    verify(exactly = 1) {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    }
    verify(exactly = 1) {
      platformApiClient.notifyOnchainFundsReceived(
        "123",
        testTxn.hash,
        fromXdrAmount(testPayment.amount).toString(),
        any(),
      )
    }
  }

  @Test
  fun `test handleSep6Transaction DEPOSIT`() {
    val testTxn = createTestLedgerTransaction()
    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep6Transaction()
    testJdbcSepTransaction.id = "123"

    // Test WITHDRAWAL
    testJdbcSepTransaction.kind = PlatformTransactionData.Kind.DEPOSIT.kind

    every {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    } answers {}

    paymentListener.handleSep6Transaction(testTxn, testPayment, testJdbcSepTransaction)

    verify(exactly = 1) {
      paymentListener.checkAndWarnAssetAmountMismatch(testTxn, testPayment, testJdbcSepTransaction)
    }
    verify(exactly = 1) { platformApiClient.notifyOnchainFundsSent("123", testTxn.hash, any()) }
  }
}
