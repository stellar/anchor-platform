package org.stellar.reference.client

import kotlin.random.Random
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Network
import org.stellar.sdk.SorobanServer
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.Operation
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse.GetTransactionStatus
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse
import org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode

private const val MAX_SEND_ATTEMPTS = 5
private const val TRANSACTION_TIMEOUT_SECONDS = 30L
private const val MAX_LEDGER_CHECKS = 45

fun sendWithSequenceRetry(
  rpc: SorobanServer,
  keyPair: KeyPair,
  operation: Operation,
  pause: () -> Unit = { Thread.sleep(Random.nextLong(1000, 4000)) },
  poll: () -> Unit = { Thread.sleep(1000) },
): String {
  repeat(MAX_SEND_ATTEMPTS) { attempt ->
    val transaction =
      TransactionBuilder(rpc.getAccount(keyPair.accountId), Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(TRANSACTION_TIMEOUT_SECONDS)
        .build()
    val prepared = rpc.prepareTransaction(transaction)
    prepared.sign(keyPair)

    val response = rpc.sendTransaction(prepared)
    val accepted =
      response.status == SendTransactionStatus.PENDING ||
        response.status == SendTransactionStatus.DUPLICATE
    if (accepted && landed(rpc, response.hash, poll)) return response.hash
    if (!accepted && !isRetryable(response)) {
      throw RuntimeException(
        "sendTransaction rejected: status=${response.status}, errorResultXdr=${response.errorResultXdr}"
      )
    }
    if (attempt < MAX_SEND_ATTEMPTS - 1) pause()
  }
  throw RuntimeException("transaction did not land on the ledger after $MAX_SEND_ATTEMPTS attempts")
}

private fun landed(rpc: SorobanServer, hash: String, poll: () -> Unit): Boolean {
  repeat(MAX_LEDGER_CHECKS) {
    val result = runCatching { rpc.getTransaction(hash) }.getOrNull()
    when (result?.status) {
      GetTransactionStatus.SUCCESS -> return true
      GetTransactionStatus.FAILED ->
        if (resultCode(result.resultXdr) == TransactionResultCode.txBAD_SEQ) return false
        else throw RuntimeException("transaction $hash failed: resultXdr=${result.resultXdr}")
      else -> poll()
    }
  }
  return false
}

private fun isRetryable(response: SendTransactionResponse): Boolean =
  when (response.status) {
    SendTransactionStatus.TRY_AGAIN_LATER -> true
    SendTransactionStatus.ERROR ->
      resultCode(response.errorResultXdr) == TransactionResultCode.txBAD_SEQ
    else -> false
  }

private fun resultCode(resultXdr: String?): TransactionResultCode? =
  resultXdr?.let { TransactionResult.fromXdrBase64(it).result.discriminant }
