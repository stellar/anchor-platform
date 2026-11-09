package org.stellar.anchor.platform.utils

import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.exception.rpc.InvalidParamsException
import org.stellar.anchor.ledger.LedgerTransaction
import org.stellar.anchor.ledger.LedgerTransaction.LedgerInvokeHostFunctionOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPathPaymentOperation
import org.stellar.anchor.ledger.LedgerTransaction.LedgerPaymentOperation
import org.stellar.anchor.platform.observer.stellar.SacToAssetMapper
import org.stellar.anchor.platform.utils.LedgerPaymentMatcher.ExpectedPayment
import org.stellar.anchor.util.MemoHelper
import org.stellar.sdk.Asset
import org.stellar.sdk.Memo
import org.stellar.sdk.MuxedAccount
import org.stellar.sdk.StrKey
import org.stellar.sdk.xdr.OperationType

/**
 * The matcher answers one question: does this ledger transaction hold a payment to the expected
 * account, with the expected memo and asset, for an amount above zero (spec FGATE-01..05, 07, 08
 * and SENT-05).
 */
class LedgerPaymentMatcherTest {
  companion object {
    private const val HASH = "3cd62e48724426be96cf2cdb65d5dacb8fac2e403e50bedb717bfc8eaf05af32"
    private const val ACCOUNT = "GBZ4HPSEHKEEJ6MOZBSVV2B3LE27EZLV6LJY55G47V7BGBODWUXQM364"
    private const val OTHER_ACCOUNT = "GBT7YF22QEVUDUTBUIS2OWLTZMP7Z4J4ON6DCSHR3JXYTZRKCPXVV5J5"
    private const val USDC_CODE_ISSUER =
      "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN"
    private const val USDC_ID = "stellar:$USDC_CODE_ISSUER"
    private const val CONTRACT_ID = "CBIELTK6YBZJU5UP2WWQEUCYKLPU6AUNZ2BQ4WWFEIE3USCIHMXQDAMA"
    private const val ONE_USDC = 10_000_000L
    private const val HASH_MEMO_HEX =
      "0101010101010101010101010101010101010101010101010101010101010101"

    private val usdc = Asset.create(USDC_CODE_ISSUER).toXdr()
    private val eurc =
      Asset.create("EURC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN").toXdr()
    private val sacMapper = mockk<SacToAssetMapper>()
  }

  private fun payment(
    to: String = ACCOUNT,
    asset: org.stellar.sdk.xdr.Asset = usdc,
    amount: Long = ONE_USDC,
    from: String = OTHER_ACCOUNT,
  ): LedgerOperation =
    LedgerOperation.builder()
      .type(OperationType.PAYMENT)
      .paymentOperation(
        LedgerPaymentOperation.builder()
          .id("1")
          .from(from)
          .to(to)
          .amount(BigInteger.valueOf(amount))
          .asset(asset)
          .build()
      )
      .build()

  private fun ledgerTxn(memo: Memo?, vararg operations: LedgerOperation): LedgerTransaction =
    LedgerTransaction.builder()
      .hash(HASH)
      .memo(if (memo == null) null else MemoHelper.toXdr(memo))
      .operations(operations.toList())
      .build()

  private fun expected(
    account: String = ACCOUNT,
    memo: String? = "12345",
    memoType: String? = "id",
    asset: String = USDC_ID,
    muxedIdFromAnySender: Boolean = false,
  ) = ExpectedPayment(account, memo, memoType, asset, muxedIdFromAnySender)

  private fun rejectionMessage(txn: LedgerTransaction, expected: ExpectedPayment): String =
    assertFailsWith<InvalidParamsException> {
        LedgerPaymentMatcher.requirePayment(sacMapper, txn, expected)
      }
      .message!!

  private fun assertAccepted(txn: LedgerTransaction, expected: ExpectedPayment) =
    LedgerPaymentMatcher.requirePayment(sacMapper, txn, expected)

  @Test
  fun `a transaction with no payment operation names the destination and the hash`() {
    val message = rejectionMessage(ledgerTxn(Memo.id(12345)), expected())

    assertContains(message, "destination")
    assertContains(message, HASH)
  }

  @Test
  fun `a payment to another account names the destination`() {
    val message =
      rejectionMessage(ledgerTxn(Memo.id(12345), payment(to = OTHER_ACCOUNT)), expected())

    assertContains(message, "destination")
    assertContains(message, HASH)
  }

  @Test
  fun `another memo value names the memo`() {
    val message = rejectionMessage(ledgerTxn(Memo.id(54321), payment()), expected())

    assertContains(message, "memo")
    assertContains(message, HASH)
  }

  @Test
  fun `the same memo digits in another memo type name the memo`() {
    val message = rejectionMessage(ledgerTxn(Memo.text("12345"), payment()), expected())

    assertContains(message, "memo")
  }

  @Test
  fun `a transaction without a memo names the memo when one is stored`() {
    val message = rejectionMessage(ledgerTxn(null, payment()), expected())

    assertContains(message, "memo")
  }

  @Test
  fun `another asset names the asset`() {
    val message = rejectionMessage(ledgerTxn(Memo.id(12345), payment(asset = eurc)), expected())

    assertContains(message, "asset")
    assertContains(message, HASH)
  }

  @Test
  fun `an amount of zero names the amount`() {
    val message = rejectionMessage(ledgerTxn(Memo.id(12345), payment(amount = 0)), expected())

    assertContains(message, "amount")
    assertContains(message, HASH)
  }

  @Test
  fun `the smallest positive amount is accepted and zero is rejected`() {
    assertAccepted(ledgerTxn(Memo.id(12345), payment(amount = 1)), expected())

    assertFailsWith<InvalidParamsException> {
      assertAccepted(ledgerTxn(Memo.id(12345), payment(amount = 0)), expected())
    }
  }

  @Test
  fun `the message names the furthest stage any payment reached`() {
    // The first payment gets as far as the memo; the second fails at the destination.
    val txn = ledgerTxn(Memo.id(54321), payment(), payment(to = OTHER_ACCOUNT))

    assertContains(rejectionMessage(txn, expected()), "memo")
  }

  @Test
  fun `one matching payment among several is accepted`() {
    assertAccepted(
      ledgerTxn(
        Memo.id(12345),
        payment(to = OTHER_ACCOUNT),
        payment(asset = eurc),
        payment(amount = 0),
        payment()
      ),
      expected()
    )
  }

  @Test
  fun `the right destination in the wrong asset and the right asset elsewhere do not combine`() {
    val txn = ledgerTxn(Memo.id(12345), payment(asset = eurc), payment(to = OTHER_ACCOUNT))

    assertContains(rejectionMessage(txn, expected()), "asset")
  }

  @Test
  fun `no memo is compared when none is stored`() {
    assertAccepted(ledgerTxn(Memo.text("anything"), payment()), expected(memo = null))
    assertAccepted(ledgerTxn(null, payment()), expected(memo = null, memoType = null))
  }

  @Test
  fun `a stored memo without a type is compared by value`() {
    assertAccepted(ledgerTxn(Memo.id(12345), payment()), expected(memoType = null))
    assertAccepted(ledgerTxn(Memo.text("12345"), payment()), expected(memoType = null))
  }

  @Test
  fun `text id and hash memos are accepted`() {
    assertAccepted(
      ledgerTxn(Memo.text("deposit-1"), payment()),
      expected(memo = "deposit-1", memoType = "text")
    )
    assertAccepted(ledgerTxn(Memo.id(12345), payment()), expected())
    assertAccepted(
      ledgerTxn(Memo.hash(HASH_MEMO_HEX), payment()),
      expected(memo = HASH_MEMO_HEX, memoType = "hash")
    )
  }

  @Test
  fun `a hash memo stored in base64 is accepted`() {
    assertAccepted(
      ledgerTxn(Memo.hash(HASH_MEMO_HEX), payment()),
      expected(memo = MemoHelper.convertHexToBase64(HASH_MEMO_HEX), memoType = "hash")
    )
  }

  @Test
  fun `the memo type is compared when one is stored`() {
    val message =
      rejectionMessage(
        ledgerTxn(Memo.hash(HASH_MEMO_HEX), payment()),
        expected(memo = HASH_MEMO_HEX, memoType = "text")
      )

    assertContains(message, "memo")
  }

  @Test
  fun `a muxed destination on the account whose id is the stored memo is accepted from any sender`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(12345)).address

    assertAccepted(ledgerTxn(null, payment(to = muxed)), expected(muxedIdFromAnySender = true))
  }

  @Test
  fun `a muxed destination with another id is rejected`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(99)).address

    val message =
      rejectionMessage(
        ledgerTxn(Memo.id(12345), payment(to = muxed)),
        expected(muxedIdFromAnySender = true)
      )

    assertContains(message, "destination")
  }

  @Test
  fun `a muxed id only stands in for a stored memo of type id`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(12345)).address
    val txn = ledgerTxn(null, payment(to = muxed))

    // The id equals the stored digits, but the stored memo is a text or hash memo, or has no type.
    for (memoType in listOf("text", "hash", null)) {
      val message =
        rejectionMessage(txn, expected(memoType = memoType, muxedIdFromAnySender = true))

      assertContains(message, "destination")
    }
    assertAccepted(txn, expected(memoType = "id", muxedIdFromAnySender = true))
  }

  @Test
  fun `a muxed destination on another account is rejected`() {
    val muxed = MuxedAccount(OTHER_ACCOUNT, BigInteger.valueOf(12345)).address

    val message =
      rejectionMessage(ledgerTxn(null, payment(to = muxed)), expected(muxedIdFromAnySender = true))

    assertContains(message, "destination")
  }

  @Test
  fun `a muxed destination from a classic sender is rejected unless any sender is allowed`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(12345)).address
    val txn = ledgerTxn(null, payment(to = muxed))

    assertContains(rejectionMessage(txn, expected(muxedIdFromAnySender = false)), "destination")
    assertAccepted(txn, expected(muxedIdFromAnySender = true))
  }

  @Test
  fun `a muxed destination from a contract is accepted without allowing any sender`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(12345)).address
    val from = StrKey.encodeContract(ByteArray(32))

    assertAccepted(
      ledgerTxn(null, payment(to = muxed, from = from)),
      expected(muxedIdFromAnySender = false)
    )
  }

  @Test
  fun `a muxed destination on the account is accepted when no memo is stored`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(7)).address

    assertAccepted(
      ledgerTxn(null, payment(to = muxed)),
      expected(memo = null, memoType = null, muxedIdFromAnySender = true)
    )
  }

  @Test
  fun `an expected muxed account is matched by equality`() {
    val muxed = MuxedAccount(ACCOUNT, BigInteger.valueOf(7)).address

    assertAccepted(
      ledgerTxn(null, payment(to = muxed)),
      expected(account = muxed, memo = null, memoType = null)
    )
  }

  @Test
  fun `path payments are payments`() {
    for (type in
      listOf(OperationType.PATH_PAYMENT_STRICT_RECEIVE, OperationType.PATH_PAYMENT_STRICT_SEND)) {
      val operation =
        LedgerOperation.builder()
          .type(type)
          .pathPaymentOperation(
            LedgerPathPaymentOperation.builder()
              .type(type)
              .id("1")
              .from(OTHER_ACCOUNT)
              .to(ACCOUNT)
              .amount(BigInteger.valueOf(ONE_USDC))
              .asset(usdc)
              .build()
          )
          .build()

      assertAccepted(ledgerTxn(Memo.id(12345), operation), expected())
    }
  }

  private fun invokeHostFunction(contractAsset: org.stellar.sdk.xdr.Asset?): LedgerOperation =
    LedgerOperation.builder()
      .type(OperationType.INVOKE_HOST_FUNCTION)
      .invokeHostFunctionOperation(
        LedgerInvokeHostFunctionOperation.builder()
          .id("1")
          .contractId(CONTRACT_ID)
          .hostFunction("transfer")
          .from(OTHER_ACCOUNT)
          .to(ACCOUNT)
          .amount(BigInteger.valueOf(ONE_USDC))
          .asset(contractAsset)
          .build()
      )
      .build()

  @Test
  fun `a contract transfer is a payment in the asset its contract resolves to`() {
    every { sacMapper.getAssetFromSac(CONTRACT_ID) } returns usdc

    assertAccepted(ledgerTxn(Memo.id(12345), invokeHostFunction(null)), expected())
  }

  @Test
  fun `a contract transfer whose asset cannot be resolved names the asset`() {
    every { sacMapper.getAssetFromSac(CONTRACT_ID) } returns null

    val message = rejectionMessage(ledgerTxn(Memo.id(12345), invokeHostFunction(null)), expected())

    assertContains(message, "asset")
  }

  @Test
  fun `a contract transfer whose lookup fails names the asset`() {
    every { sacMapper.getAssetFromSac(CONTRACT_ID) } throws IllegalStateException("no rpc")

    val message = rejectionMessage(ledgerTxn(Memo.id(12345), invokeHostFunction(null)), expected())

    assertContains(message, "asset")
  }

  @Test
  fun `the rejection is an InvalidParamsException carrying the expected account`() {
    val ex =
      assertFailsWith<InvalidParamsException> {
        assertAccepted(ledgerTxn(Memo.id(12345), payment(to = OTHER_ACCOUNT)), expected())
      }

    assertEquals(true, ex.message!!.contains(ACCOUNT))
  }
}
