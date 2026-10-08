package org.stellar.anchor.sep24

import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.stellar.anchor.api.asset.AssetInfo

class Sep24RefundsTest {
  private fun asset(decimals: Int) =
    mockk<AssetInfo> { every { significantDecimals } returns decimals }

  private fun refunds(vararg amountAndFee: Pair<String, String>): PojoSep24Refunds {
    val refunds = PojoSep24Refunds()
    refunds.refundPayments =
      amountAndFee.mapIndexed { i, (amount, fee) ->
        val payment = PojoSep24RefundPayment()
        payment.id = "refund-$i"
        payment.amount = amount
        payment.fee = fee
        payment
      }
    return refunds
  }

  @Test
  fun `recalculateAmounts persists a 7-decimal amount without truncation`() {
    val refunds = refunds("12.3456789" to "0")
    refunds.recalculateAmounts(asset(7))
    assertEquals("12.3456789", refunds.amountRefunded)
    assertEquals("0", refunds.amountFee)
  }

  @Test
  fun `recalculateAmounts persists an amount that rounded down at 4 decimals`() {
    val refunds = refunds("12.34564" to "0")
    refunds.recalculateAmounts(asset(7))
    assertEquals("12.34564", refunds.amountRefunded)
  }

  @Test
  fun `recalculateAmounts sums two high-precision payments`() {
    val refunds = refunds("5.1234567" to "0.0000001", "5.0000002" to "0")
    refunds.recalculateAmounts(asset(7))
    assertEquals("10.123457", refunds.amountRefunded)
    assertEquals("0.0000001", refunds.amountFee)
  }

  @Test
  fun `recalculateAmounts keeps a 4-decimal amount unchanged on a 7-decimal asset`() {
    val refunds = refunds("12.3456" to "0")
    refunds.recalculateAmounts(asset(7))
    assertEquals("12.3456", refunds.amountRefunded)
  }

  @Test
  fun `recalculateAmounts rounds half down to the asset scale`() {
    val refunds = refunds("10.005" to "0")
    refunds.recalculateAmounts(asset(2))
    assertEquals("10", refunds.amountRefunded)
  }

  @Test
  fun `recalculateAmounts is unchanged for a 4-decimal asset`() {
    val refunds = refunds("1.2345" to "0.1")
    refunds.recalculateAmounts(asset(4))
    assertEquals("1.3345", refunds.amountRefunded)
    assertEquals("0.1", refunds.amountFee)
  }
}
