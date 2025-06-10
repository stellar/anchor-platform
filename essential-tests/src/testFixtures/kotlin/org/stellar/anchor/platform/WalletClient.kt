package org.stellar.anchor.platform

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.util.Base64
import org.apache.commons.codec.binary.Hex
import org.stellar.anchor.api.sep.sep45.ChallengeRequest
import org.stellar.anchor.client.*
import org.stellar.anchor.platform.TestSecrets.CLIENT_SMART_WALLET_ACCOUNT
import org.stellar.anchor.platform.TestSecrets.CLIENT_WALLET_SECRET
import org.stellar.anchor.util.Sep1Helper.TomlContent
import org.stellar.sdk.*
import org.stellar.sdk.AbstractTransaction.MIN_BASE_FEE
import org.stellar.sdk.Auth.authorizeEntry
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.InvokeHostFunctionOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.scval.Scv
import org.stellar.sdk.xdr.SCVal
import org.stellar.sdk.xdr.SCValType
import org.stellar.sdk.xdr.SorobanAuthorizationEntry

class WalletClient(
  val account: String,
  private val signingKey: String,
  memo: String? = null,
  toml: TomlContent,
) {
  val sep12: Sep12Client
  val sep38: Sep38Client
  val sep24: Sep24Client
  val sep6: Sep6Client
  val sep31: Sep31Client
  private val horizon = Server("https://horizon-testnet.stellar.org")
  private val rpc = SorobanServer("https://soroban-testnet.stellar.org")

  init {
    val token =
      when (account[0]) {
        'G' -> {
          Sep10Client(
              toml.getString("WEB_AUTH_ENDPOINT"),
              toml.getString("SIGNING_KEY"),
              account,
              signingKey,
            )
            .auth(toml.getString("WEB_AUTH_ENDPOINT").split("/")[2], memo)
        }
        'C' -> {
          val webAuthDomain = toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT")
          val homeDomain = "http://${URI.create(webAuthDomain).authority}"

          val sep45Client =
            Sep45Client(
              toml.getString("WEB_AUTH_FOR_CONTRACTS_ENDPOINT"),
              rpc,
              CLIENT_WALLET_SECRET,
            )
          val challenge =
            sep45Client.getChallenge(
              ChallengeRequest.builder()
                .account(CLIENT_SMART_WALLET_ACCOUNT)
                .homeDomain(homeDomain)
                .build()
            )
          sep45Client.validate(sep45Client.sign(challenge)).token
        }
        else -> throw Exception("Unsupported account type ${account}")
      }

    sep12 = Sep12Client(toml.getString("KYC_SERVER"), token)
    sep38 = Sep38Client(toml.getString("ANCHOR_QUOTE_SERVER"), token)
    sep24 = Sep24Client(toml.getString("TRANSFER_SERVER_SEP0024"), token)
    sep6 = Sep6Client(toml.getString("TRANSFER_SERVER"), token)
    sep31 = Sep31Client(toml.getString("DIRECT_PAYMENT_SERVER"), token)
  }

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
    return when (account[0]) {
      'C' -> sendFromContractAccount(destination, asset, amount)
      'G',
      'M' -> sendFromClassicAccount(destination, asset, amount, memo, memoType)
      else -> throw Exception("Unsupported destination account type")
    }
  }

  private fun sendFromClassicAccount(
    destination: String,
    asset: Asset,
    amount: String,
    memo: String?,
    memoType: String?,
  ): String {
    val keyPair = KeyPair.fromSecretSeed(signingKey)
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

  private fun sendFromContractAccount(destination: String, asset: Asset, amount: String): String {
    val keyPair = KeyPair.fromSecretSeed(signingKey)
    val parameters =
      mutableListOf(
        // from=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(account).address)
          .build(),
        // to=
        SCVal.builder()
          .discriminant(SCValType.SCV_ADDRESS)
          .address(Scv.toAddress(destination).address)
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
        .setBaseFee(Transaction.MIN_BASE_FEE)
        .setTimeout(300)
        .build()

    val preparedTransaction = rpc.prepareTransaction(authorizedTransaction)
    preparedTransaction.sign(keyPair)

    val transactionResponse = rpc.sendTransaction(preparedTransaction)

    return transactionResponse.hash
  }
}
