package org.stellar.reference.client

import java.math.BigInteger
import org.stellar.sdk.*
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.Auth.authorizeEntry
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry

/** Sends payments to classic accounts and contract accounts. */
class PaymentClient(
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
   * @return The transaction hash.
   */
  fun send(destination: String, asset: Asset, amount: String, memo: String? = null): String {
    if (destination.isEmpty()) {
      throw Exception("Destination account is required")
    }
    return when (destination[0]) {
      'C' -> sendToAccount(destination, asset, amount, null)
      'G',
      'M' -> sendToAccount(destination, asset, amount, memo)
      else -> throw Exception("Unsupported destination account type")
    }
  }

  fun getTransaction(transactionId: String): GetTransactionResponse? {
    return try {
      rpc.getTransaction(transactionId)
    } catch (e: BadRequestException) {
      throw RuntimeException("Error fetching transaction: ${e.problem?.extras?.resultCodes}")
    }
  }

  private fun sendToAccount(
    destination: String,
    asset: Asset,
    amount: String,
    memo: String?
  ): String {
    var destAddress = destination
    if (memo != null) {
      // memo must be a number for MuxedAccount
      destAddress = MuxedAccount(destination, BigInteger.valueOf(memo.toLong())).address
    }

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
          .address(Scv.toAddress(destAddress).address)
          .build(),
        SCVal.builder()
          .discriminant(SCValType.SCV_I128)
          .i128(Scv.toInt128(BigInteger.valueOf((amount.toFloat() * 10000000).toLong())).i128)
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

    var account = rpc.getAccount(keyPair.accountId)
    val transaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(operation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val simulationResponse = rpc.simulateTransaction(transaction)
    val signedAuthEntries = mutableListOf<SorobanAuthorizationEntry>()
    simulationResponse.results.forEach {
      it.auth.forEach { entryXdr ->
        val entry = SorobanAuthorizationEntry.fromXdrBase64(entryXdr)
        val validUntilLedgerSeq = simulationResponse.latestLedger + 10

        val signedEntry = authorizeEntry(entry, keyPair, validUntilLedgerSeq, Network.TESTNET)
        signedAuthEntries.add(signedEntry)
      }
    }

    val signedOperation =
      InvokeHostFunctionOperation.invokeContractFunctionOperationBuilder(
          asset.getContractId(Network.TESTNET),
          "transfer",
          parameters,
        )
        .sourceAccount(keyPair.accountId)
        .auth(signedAuthEntries)
        .build()

    account = rpc.getAccount(keyPair.accountId)
    val authorizedTransaction =
      TransactionBuilder(account, Network.TESTNET)
        .addOperation(signedOperation)
        .setBaseFee(MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val preparedTransaction = rpc.prepareTransaction(authorizedTransaction)
    preparedTransaction.sign(keyPair)

    val transactionResponse = rpc.sendTransaction(preparedTransaction)

    return transactionResponse.hash
  }
}
