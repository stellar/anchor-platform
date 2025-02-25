package org.stellar.reference.client

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import org.apache.commons.codec.binary.Hex
import org.stellar.sdk.*
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType

/** Sends payments to classic accounts and contract accounts. */
class PaymentClient(
  private val horizon: Server,
  private val rpc: SorobanServer,
  private val keyPair: KeyPair,
) {

  /**
   * Sends a payment to a destination account.
   *
   * @param destination The destination account ID.
   * @param asset The asset to send.
   * @param amount The amount to send.
   * @param memo The memo to attach to the transaction. Currently ignored for contract accounts.
   * @param memoType The type of memo to attach to the transaction. Currently ignored for contract
   *   accounts.
   * @return The transaction hash.
   */
  fun send(
    destination: String,
    asset: Asset,
    amount: String,
    memo: String? = null,
    memoType: String? = null,
  ): String {
    if (destination.isEmpty()) {
      throw Exception("Destination account is required")
    }
    return when (destination[0]) {
      'C' -> sendToContractAccount(destination, asset, amount)
      'G',
      'M' -> sendToClassicAccount(destination, asset, amount, memo, memoType)
      else -> throw Exception("Unsupported destination account type")
    }
  }

  private fun sendToClassicAccount(
    destination: String,
    asset: Asset,
    amount: String,
    memo: String?,
    memoType: String?,
  ): String {
    val account = horizon.accounts().account(keyPair.accountId)
    val transactionBuilder =
      TransactionBuilder(account, Network.TESTNET)
        .setBaseFee(100)
        .addPreconditions(
          TransactionPreconditions.builder().timeBounds(TimeBounds.expiresAfter(60)).build()
        )
        .addOperation(
          PaymentOperation.builder()
            .destination(destination)
            .asset(asset)
            .amount(BigDecimal(amount))
            .build()
        )

    if (memo != null && memoType != null) {
      transactionBuilder.addMemo(
        when (memoType) {
          "text" -> Memo.text(memo)
          "id" -> Memo.id(memo.toLong())
          "hash" -> Memo.hash(Hex.encodeHexString(Base64.getDecoder().decode(memo)))
          else -> throw Exception("Unsupported memo type")
        }
      )
    }

    val transaction = transactionBuilder.build()
    transaction.sign(keyPair)
    val txnResponse: TransactionResponse
    try {
      txnResponse = horizon.submitTransaction(transaction)
    } catch (e: BadRequestException) {
      throw RuntimeException("Error submitting transaction: ${e.problem?.extras?.resultCodes}")
    }
    assert(txnResponse.successful)
    return txnResponse.hash
  }

  private fun sendToContractAccount(destination: String, asset: Asset, amount: String): String {
    val parameters =
      mutableListOf(
        // from=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(keyPair.accountId).address)
          .build(),
        // to=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(destination).address)
          .build(),
        SCVal.builder()
          .discriminant(SCValType.SCV_I128)
          .i128(Scv.toInt128(BigInteger.valueOf(amount.toLong() * 10000000)).i128)
          .build(),
      )
    val operation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .sourceAccount(keyPair.accountId)
        .build()

    val account = rpc.getAccount(keyPair.accountId)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val preparedTransaction = rpc.prepareTransaction(transaction)
    preparedTransaction.sign(keyPair)

    val transactionResponse = rpc.sendTransaction(preparedTransaction)

    return transactionResponse.hash
  }
}
