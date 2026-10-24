package org.stellar.reference.client

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.stellar.sdk.Account
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.KeyPair
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse.GetTransactionStatus
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus
import org.stellar.sdk.xdr.Int64
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode

class SequenceRetryTest {
  private val keyPair = KeyPair.random()
  private val operation =
    PaymentOperation.builder()
      .destination(KeyPair.random().accountId)
      .asset(AssetTypeNative())
      .amount(BigDecimal.ONE)
      .build()
  private lateinit var rpc: SorobanServer

  @BeforeEach
  fun setUp() {
    rpc = mockk()
    every { rpc.getAccount(keyPair.accountId) } answers { Account(keyPair.accountId, 1L) }
    every { rpc.prepareTransaction(any()) } answers { firstArg() }
    every { rpc.getTransaction(any()) } returns ledger(GetTransactionStatus.SUCCESS)
  }

  @Test
  fun `returns the hash once the transaction lands`() {
    every { rpc.sendTransaction(any()) } returns sent(SendTransactionStatus.PENDING, "first")

    assertEquals("first", send())
    verify(exactly = 1) { rpc.sendTransaction(any()) }
    verify(exactly = 1) { rpc.getTransaction("first") }
  }

  @Test
  fun `reloads the sequence and retries after txBAD_SEQ on send`() {
    every { rpc.sendTransaction(any()) } returnsMany
      listOf(
        rejected(TransactionResultCode.txBAD_SEQ),
        sent(SendTransactionStatus.PENDING, "second")
      )

    assertEquals("second", send())
    verify(exactly = 2) { rpc.getAccount(keyPair.accountId) }
    verify(exactly = 2) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `retries after TRY_AGAIN_LATER`() {
    every { rpc.sendTransaction(any()) } returnsMany
      listOf(
        sent(SendTransactionStatus.TRY_AGAIN_LATER, "first"),
        sent(SendTransactionStatus.DUPLICATE, "second")
      )

    assertEquals("second", send())
    verify(exactly = 2) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `does not retry other send errors`() {
    every { rpc.sendTransaction(any()) } returns
      rejected(TransactionResultCode.txINSUFFICIENT_BALANCE)

    assertThrows<RuntimeException> { send() }
    verify(exactly = 1) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `gives up after 5 attempts`() {
    every { rpc.sendTransaction(any()) } returns rejected(TransactionResultCode.txBAD_SEQ)

    assertThrows<RuntimeException> { send() }
    verify(exactly = 5) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `resends with a fresh sequence when an accepted transaction never lands`() {
    every { rpc.sendTransaction(any()) } returnsMany
      listOf(
        sent(SendTransactionStatus.PENDING, "dropped"),
        sent(SendTransactionStatus.PENDING, "landed")
      )
    every { rpc.getTransaction("dropped") } returns ledger(GetTransactionStatus.NOT_FOUND)

    assertEquals("landed", send())
    verify(exactly = 2) { rpc.getAccount(keyPair.accountId) }
    verify(exactly = 45) { rpc.getTransaction("dropped") }
  }

  @Test
  fun `resends when an accepted transaction fails with txBAD_SEQ`() {
    every { rpc.sendTransaction(any()) } returnsMany
      listOf(
        sent(SendTransactionStatus.PENDING, "lost"),
        sent(SendTransactionStatus.PENDING, "landed")
      )
    every { rpc.getTransaction("lost") } returns
      ledger(GetTransactionStatus.FAILED, TransactionResultCode.txBAD_SEQ)

    assertEquals("landed", send())
    verify(exactly = 2) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `does not resend when an accepted transaction fails for another reason`() {
    every { rpc.sendTransaction(any()) } returns sent(SendTransactionStatus.PENDING, "failed")
    every { rpc.getTransaction("failed") } returns
      ledger(GetTransactionStatus.FAILED, TransactionResultCode.txFAILED)

    assertThrows<RuntimeException> { send() }
    verify(exactly = 1) { rpc.sendTransaction(any()) }
  }

  @Test
  fun `keeps checking through a temporary getTransaction error`() {
    every { rpc.sendTransaction(any()) } returns sent(SendTransactionStatus.PENDING, "first")
    every { rpc.getTransaction("first") } throws
      RuntimeException("rpc unavailable") andThen
      ledger(GetTransactionStatus.SUCCESS)

    assertEquals("first", send())
    verify(exactly = 1) { rpc.sendTransaction(any()) }
  }

  private fun send() = sendWithSequenceRetry(rpc, keyPair, operation, pause = {}, poll = {})

  private fun sent(status: SendTransactionStatus, hash: String): SendTransactionResponse {
    val response = mockk<SendTransactionResponse>()
    every { response.status } returns status
    every { response.hash } returns hash
    every { response.errorResultXdr } returns null
    return response
  }

  private fun rejected(code: TransactionResultCode): SendTransactionResponse {
    val response = mockk<SendTransactionResponse>()
    every { response.status } returns SendTransactionStatus.ERROR
    every { response.hash } returns "rejected"
    every { response.errorResultXdr } returns resultXdr(code)
    return response
  }

  private fun ledger(
    status: GetTransactionStatus,
    code: TransactionResultCode? = null,
  ): GetTransactionResponse {
    val response = mockk<GetTransactionResponse>()
    every { response.status } returns status
    every { response.resultXdr } returns code?.let { resultXdr(it) }
    return response
  }

  private fun resultXdr(code: TransactionResultCode): String =
    TransactionResult.builder()
      .feeCharged(Int64(100L))
      .result(
        TransactionResult.TransactionResultResult.builder()
          .discriminant(code)
          .results(arrayOf())
          .build()
      )
      .ext(TransactionResult.TransactionResultExt.builder().discriminant(0).build())
      .build()
      .toXdrBase64()
}
