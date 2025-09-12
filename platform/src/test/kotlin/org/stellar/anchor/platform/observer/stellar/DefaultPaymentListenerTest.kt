package org.stellar.anchor.platform.observer.stellar

import io.mockk.*
import io.mockk.impl.annotations.MockK
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.platform.PlatformTransactionData
import org.stellar.anchor.apiclient.PlatformApiClient
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.PaymentTransferEvent
import org.stellar.anchor.platform.config.RpcConfig
import org.stellar.anchor.platform.data.*
import org.stellar.anchor.util.AssetHelper.fromXdrAmount
import org.stellar.sdk.TOID
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
  @MockK(relaxed = true) private lateinit var sacToAssetMapper: SacToAssetMapper

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
          sacToAssetMapper
        )
      )
  }

  @Test
  fun `test validate()`() {
    var ledgerTransaction = createTestTransferEvent().ledgerTransaction
    // empty hash
    ledgerTransaction.hash = null
    var testPayment = ledgerTransaction.operations[0].paymentOperation

    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // null memo
    ledgerTransaction = createTestTransferEvent().ledgerTransaction
    ledgerTransaction.memo = null
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // empty memo
    ledgerTransaction = createTestTransferEvent().ledgerTransaction
    ledgerTransaction.memo = Memo()
    ledgerTransaction.memo.discriminant = MEMO_TEXT
    ledgerTransaction.memo.text = XdrString("")
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))

    // unsupported asset type
    ledgerTransaction = createTestTransferEvent().ledgerTransaction
    testPayment = ledgerTransaction.operations[0].paymentOperation
    testPayment.asset = Asset()
    testPayment.asset.discriminant = ASSET_TYPE_POOL_SHARE
    assertFalse(paymentListener.validate(ledgerTransaction, testPayment))
  }

  @Test
  fun `test unsupported assets should not trigger any process`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction
    val poolShareAsset = Asset()
    poolShareAsset.discriminant = ASSET_TYPE_POOL_SHARE
    ledgerTransaction.operations[0].paymentOperation.asset = poolShareAsset

    paymentListener.onReceived(event)

    verify { sep31TransactionStore wasNot Called }
    verify { sep24TransactionStore wasNot Called }
    verify { sep6TransactionStore wasNot Called }
  }

  @Test
  fun `test handleSep31Transaction are called properly`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction
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

    paymentListener.onReceived(event)

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
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction
    xdrMemoText.text = XdrString("my_memo_1")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null

    every {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns JdbcSep24Transaction()

    every { paymentListener.handleSep24Transaction(any(), any(), any()) } answers {}

    paymentListener.onReceived(event)

    verify(exactly = 1) {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
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
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction
    xdrMemoText.text = XdrString("my_memo_1")
    ledgerTransaction.memo = xdrMemoText

    val slotMemo = slot<String>()
    val slotAccount = slot<String>()
    val slotStatus = slot<String>()

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any())
    } returns null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        capture(slotAccount),
        capture(slotMemo),
        capture(slotStatus),
      )
    } returns JdbcSep6Transaction()

    every { paymentListener.handleSep6Transaction(any(), any(), any()) } answers {}

    paymentListener.onReceived(event)

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

  private val ledgerSequence = 1234567
  private val applicationOrder = 1
  private val testTOID = TOID(ledgerSequence, applicationOrder, 1)

  private fun createTestTransferEvent(): PaymentTransferEvent {
    val testAssetFoo =
      org.stellar.sdk.Asset.create(
        null,
        "FOO",
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
      )

    val ledgerTransaction =
      LedgerTransaction.builder()
        .hash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
        .memo(xdrMemoText)
        .ledger(ledgerSequence.toLong())
        .operations(
          listOf(
            LedgerOperation.builder()
              .type(OperationType.PAYMENT)
              .paymentOperation(
                LedgerTransaction.LedgerPaymentOperation.builder()
                  .id(testTOID.toInt64().toString())
                  .asset(testAssetFoo.toXdr())
                  .amount(BigInteger.valueOf(1))
                  .sourceAccount("GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5")
                  .from("GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5")
                  .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
                  .build()
              )
              .build()
          )
        )
        .build()

    return PaymentTransferEvent.builder()
      .from("GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5")
      .to("GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364")
      .amount(BigInteger.valueOf(1))
      .txHash("1ad62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af30")
      .operationId(testTOID.toInt64().toString())
      .ledgerTransaction(ledgerTransaction)
      .build()
  }

  @Test
  fun `test if Sep31 findByToAccountAndMemoAndStatus throws an exception, we shouldn't trigger any updates`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction

    xdrMemoText.text = XdrString("my_memo_3")
    ledgerTransaction.memo = xdrMemoText

    every {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_sender",
      )
    } throws RuntimeException("Something went wrong")

    paymentListener.onReceived(event)

    verify(exactly = 1) {
      sep31TransactionStore.findByToAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_sender",
      )
    }

    verify(exactly = 0) {
      paymentListener.handleSep31Transaction(
        ledgerTransaction,
        ledgerTransaction.operations[0].paymentOperation,
        any(),
      )
    }
  }

  @Test
  fun `test if Sep24 findByStellarAccountIdAndMemoAndStatus throws an exception, we shouldn't trigger any updates`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction

    xdrMemoText.text = XdrString("my_memo_3")
    ledgerTransaction.memo = xdrMemoText

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_user_transfer_start",
      )
    } throws RuntimeException("Something went wrong")

    paymentListener.onReceived(event)

    verify(exactly = 1) {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_user_transfer_start",
      )
    }

    verify(exactly = 0) {
      paymentListener.handleSep24Transaction(
        ledgerTransaction,
        ledgerTransaction.operations[0].paymentOperation,
        any(),
      )
    }
  }

  @Test
  fun `test if Sep6 findOneByWithdrawAnchorAccountAndMemoAndStatus throws an exception, we shouldn't trigger any updates`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction

    xdrMemoText.text = XdrString("my_memo_3")
    ledgerTransaction.memo = xdrMemoText

    every { sep31TransactionStore.findByToAccountAndMemoAndStatus(any(), any(), any()) } returns
      null
    every {
      sep24TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(any(), any(), any())
    } returns null
    every {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_user_transfer_start",
      )
    } throws RuntimeException("Something went wrong")

    paymentListener.onReceived(event)

    verify(exactly = 1) {
      sep6TransactionStore.findOneByWithdrawAnchorAccountAndMemoAndStatus(
        "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364",
        "my_memo_3",
        "pending_user_transfer_start",
      )
    }

    verify(exactly = 0) {
      paymentListener.handleSep24Transaction(
        ledgerTransaction,
        ledgerTransaction.operations[0].paymentOperation,
        any(),
      )
    }
  }

  @Test
  fun `test If asset code from the fetched tx is different, don't trigger event`() {
    val event = createTestTransferEvent()
    val ledgerTransaction = event.ledgerTransaction

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
    paymentListener.onReceived(event)
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
    val event = createTestTransferEvent()
    val testTxn = event.ledgerTransaction

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
    val event = createTestTransferEvent()
    val testTxn = event.ledgerTransaction

    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep24Transaction()
    testJdbcSepTransaction.id = "123"
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
    val event = createTestTransferEvent()
    val testTxn = event.ledgerTransaction

    val testPayment = testTxn.operations[0].paymentOperation
    val testJdbcSepTransaction = JdbcSep24Transaction()
    testJdbcSepTransaction.id = "123"
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
    val event = createTestTransferEvent()
    val testTxn = event.ledgerTransaction

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
    val event = createTestTransferEvent()
    val testTxn = event.ledgerTransaction
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
