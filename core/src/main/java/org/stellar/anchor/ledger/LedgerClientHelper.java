package org.stellar.anchor.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.sdk.xdr.CryptoKeyType.KEY_TYPE_ED25519;
import static org.stellar.sdk.xdr.HostFunctionType.HOST_FUNCTION_TYPE_INVOKE_CONTRACT;
import static org.stellar.sdk.xdr.OperationType.*;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.sdk.MuxedAccount;
import org.stellar.sdk.StrKey;
import org.stellar.sdk.TOID;
import org.stellar.sdk.exception.BadRequestException;
import org.stellar.sdk.scval.Scv;
import org.stellar.sdk.xdr.*;

public class LedgerClientHelper {

  /**
   * Convert a Stellar operation to a LedgerOperation.
   *
   * @param sourceAccount the source account.
   * @param sequenceNumber the sequence number of the transaction
   * @param applicationOrder the application order of the transaction
   * @param opIndex the operation index of the transaction
   * @param op the operation to convert
   * @return the converted LedgerOperation
   * @throws LedgerException if the operation is null or malformed
   */
  static LedgerOperation convert(
      String sourceAccount,
      Long sequenceNumber,
      Integer applicationOrder,
      int opIndex,
      Operation op,
      OperationResult opResult)
      throws LedgerException {
    if (op == null) {
      throw new LedgerException(
          "Malformed transaction detected. The operation is null. Please check the transaction.");
    }
    if (op.getBody() == null) {
      throw new LedgerException("Malformed transaction detected. The operation body is null.");
    }
    String operationId =
        String.valueOf(new TOID(sequenceNumber.intValue(), applicationOrder, opIndex).toInt64());
    return switch (op.getBody().getDiscriminant()) {
      case PAYMENT -> {
        PaymentOp payment = op.getBody().getPaymentOp();
        String toAddress =
            switch (payment.getDestination().getDiscriminant()) {
              case KEY_TYPE_ED25519 ->
                  StrKey.encodeEd25519PublicKey(payment.getDestination().getEd25519().getUint256());
              case KEY_TYPE_MUXED_ED25519 -> {
                try {
                  yield StrKey.encodeMed25519PublicKey(
                      payment.getDestination().getMed25519().toXdrByteArray());
                } catch (IOException ioex) {
                  throw new LedgerException(
                      "Failed to encode muxed account: " + payment.getDestination(), ioex);
                }
              }
              default -> {
                debugF("Unsupported payment destination type: {}", payment.getDestination());
                yield null;
              }
            };
        yield LedgerOperation.builder()
            .type(PAYMENT)
            .paymentOperation(
                LedgerPaymentOperation.builder()
                    .id(operationId)
                    .asset(payment.getAsset())
                    .amount(BigInteger.valueOf(payment.getAmount().getInt64()))
                    .from(sourceAccount)
                    .sourceAccount(sourceAccount)
                    .to(toAddress)
                    .build())
            .build();
      }
      case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND -> {
        Asset asset;
        Long amount;
        String toAddress;
        if (op.getBody().getDiscriminant() == PATH_PAYMENT_STRICT_RECEIVE) {
          asset = op.getBody().getPathPaymentStrictReceiveOp().getDestAsset();
          amount = op.getBody().getPathPaymentStrictReceiveOp().getDestAmount().getInt64();
          PathPaymentStrictReceiveOp payment = op.getBody().getPathPaymentStrictReceiveOp();
          toAddress =
              switch (payment.getDestination().getDiscriminant()) {
                case KEY_TYPE_ED25519 ->
                    StrKey.encodeEd25519PublicKey(
                        op.getBody()
                            .getPathPaymentStrictReceiveOp()
                            .getDestination()
                            .getEd25519()
                            .getUint256());
                case KEY_TYPE_MUXED_ED25519 -> {
                  try {
                    yield StrKey.encodeMed25519PublicKey(
                        payment.getDestination().getMed25519().toXdrByteArray());
                  } catch (IOException ioex) {
                    throw new LedgerException(
                        "Failed to encode muxed account: " + payment.getDestination(), ioex);
                  }
                }
                default -> {
                  debugF("Unsupported payment destination type: {}", payment.getDestination());
                  yield null;
                }
              };
        } else {
          asset = op.getBody().getPathPaymentStrictSendOp().getDestAsset();
          amount = extractStrictSendReceivedAmount(opResult, operationId);
          PathPaymentStrictSendOp payment = op.getBody().getPathPaymentStrictSendOp();
          toAddress =
              switch (payment.getDestination().getDiscriminant()) {
                case KEY_TYPE_ED25519 ->
                    StrKey.encodeEd25519PublicKey(
                        payment.getDestination().getEd25519().getUint256());
                case KEY_TYPE_MUXED_ED25519 -> {
                  try {
                    yield StrKey.encodeMed25519PublicKey(
                        payment.getDestination().getMed25519().toXdrByteArray());
                  } catch (IOException ioex) {
                    throw new LedgerException(
                        "Failed to encode muxed account: " + payment.getDestination(), ioex);
                  }
                }
                default -> {
                  debugF("Unsupported payment destination type: {}", payment.getDestination());
                  yield null;
                }
              };
        }
        yield LedgerOperation.builder()
            .type(
                switch (op.getBody().getDiscriminant()) {
                  case PATH_PAYMENT_STRICT_RECEIVE -> PATH_PAYMENT_STRICT_RECEIVE;
                  case PATH_PAYMENT_STRICT_SEND -> PATH_PAYMENT_STRICT_SEND;
                  default -> null;
                })
            .pathPaymentOperation(
                LedgerPathPaymentOperation.builder()
                    .type(op.getBody().getDiscriminant())
                    .id(operationId)
                    .asset(asset)
                    .amount(BigInteger.valueOf(amount))
                    .from(sourceAccount)
                    .to(toAddress)
                    .sourceAccount(sourceAccount)
                    .build())
            .build();
      }
      case INVOKE_HOST_FUNCTION -> {
        HostFunction hostFunction = op.getBody().getInvokeHostFunctionOp().getHostFunction();
        if (hostFunction.getDiscriminant() != HOST_FUNCTION_TYPE_INVOKE_CONTRACT) yield null;
        if (!hostFunction
            .getInvokeContract()
            .getFunctionName()
            .getSCSymbol()
            .toString()
            .equals("transfer")) yield null;
        SCAddress contractAddress = hostFunction.getInvokeContract().getContractAddress();
        SCVal from = hostFunction.getInvokeContract().getArgs()[0];
        SCVal to = hostFunction.getInvokeContract().getArgs()[1];
        SCVal amount = hostFunction.getInvokeContract().getArgs()[2];

        String contractId;
        String fromAddr;
        String toAddr;

        try {
          contractId = StrKey.encodeContract(contractAddress.getContractId().toXdrByteArray());
          fromAddr = getAddressOrContractId(from.getAddress());
          toAddr = getAddressOrContractId(to.getAddress());
        } catch (IOException ioex) {
          throw new LedgerException("Failed to encode contract address: " + contractAddress, ioex);
        }

        yield LedgerOperation.builder()
            .type(INVOKE_HOST_FUNCTION)
            .invokeHostFunctionOperation(
                LedgerInvokeHostFunctionOperation.builder()
                    .contractId(contractId)
                    .hostFunction("transfer")
                    .id(operationId)
                    .amount(Scv.fromInt128(amount))
                    .from(fromAddr)
                    .to(toAddr)
                    .sourceAccount(sourceAccount)
                    .build())
            .build();
      }
      default -> null;
    };
  }

  /**
   * Extract the amount actually received by the destination of a PATH_PAYMENT_STRICT_SEND
   * operation. This is NOT present in the operation body (that only carries {@code sendAmount}, the
   * source's debit in a different asset) — it only exists in the operation's execution result.
   * Fails closed: if the result cannot prove the received amount, an exception is thrown rather
   * than falling back to {@code sendAmount}, which is attacker-controlled and denominated in a
   * different asset than the one credited.
   *
   * @param opResult the operation's execution result, or null if unavailable
   * @param operationId the operation id, for error messages
   * @return the destination-received amount, in the smallest unit of destAsset
   * @throws LedgerException if the result does not prove a successful strict-send received amount
   */
  private static long extractStrictSendReceivedAmount(OperationResult opResult, String operationId)
      throws LedgerException {
    PathPaymentStrictSendResult result =
        (opResult != null
                && opResult.getDiscriminant() == OperationResultCode.opINNER
                && opResult.getTr() != null
                && opResult.getTr().getDiscriminant() == PATH_PAYMENT_STRICT_SEND)
            ? opResult.getTr().getPathPaymentStrictSendResult()
            : null;

    PathPaymentStrictSendResult.PathPaymentStrictSendResultSuccess success =
        (result != null
                && result.getDiscriminant()
                    == PathPaymentStrictSendResultCode.PATH_PAYMENT_STRICT_SEND_SUCCESS)
            ? result.getSuccess()
            : null;

    SimplePaymentResult last = success == null ? null : success.getLast();
    Int64 amount = last == null ? null : last.getAmount();

    if (amount == null) {
      throw new LedgerException(
          "Cannot determine the actual received amount for PATH_PAYMENT_STRICT_SEND operation "
              + "id="
              + operationId
              + ": missing or non-success operation result.");
    }
    return amount.getInt64();
  }

  /**
   * Extract the per-operation results from a transaction result, unwrapping the fee-bump inner
   * result if present.
   *
   * @param txResult the transaction result
   * @param txnHash the transaction hash, for logging
   * @return the per-operation results, or null if the transaction did not succeed
   */
  public static OperationResult[] parseOperationResults(
      TransactionResult txResult, String txnHash) {
    TransactionResultCode code = txResult.getResult().getDiscriminant();
    return switch (code) {
      case txSUCCESS -> txResult.getResult().getResults();
      case txFEE_BUMP_INNER_SUCCESS ->
          txResult.getResult().getInnerResultPair().getResult().getResult().getResults();
      default -> {
        debugF("Transaction result code={} has no operation results. tx.hash={}", code, txnHash);
        yield null;
      }
    };
  }

  static String getAddressOrContractId(SCAddress address) throws IOException {
    return switch (address.getDiscriminant()) {
      case SC_ADDRESS_TYPE_ACCOUNT ->
          StrKey.encodeEd25519PublicKey(
              address.getAccountId().getAccountID().getEd25519().getUint256());
      case SC_ADDRESS_TYPE_CONTRACT ->
          StrKey.encodeContract(address.getContractId().toXdrByteArray());
      case SC_ADDRESS_TYPE_MUXED_ACCOUNT -> {
        MuxedEd25519Account ma = address.getMuxedAccount();
        String accountId = StrKey.encodeEd25519PublicKey(ma.getEd25519().getUint256());
        BigInteger muxedId = ma.getId().getUint64().getNumber();
        yield new org.stellar.sdk.MuxedAccount(accountId, muxedId).getAddress();
      }
      case SC_ADDRESS_TYPE_CLAIMABLE_BALANCE, SC_ADDRESS_TYPE_LIQUIDITY_POOL -> null;
    };
  }

  /**
   * Parse the transaction envelope and extract the operations, source account, and memo.
   *
   * @param txnEnv the transaction envelope
   * @param txnHash the transaction hash
   * @return a ParseResult containing the operations, source account, and memo
   */
  public static ParseResult parseOperationAndSourceAccountAndMemo(
      TransactionEnvelope txnEnv, String txnHash) {
    Operation[] operations;
    String sourceAccount;
    Memo memo;

    switch (txnEnv.getDiscriminant()) {
      case ENVELOPE_TYPE_TX_V0:
        operations = txnEnv.getV0().getTx().getOperations();
        sourceAccount =
            StrKey.encodeEd25519PublicKey(
                txnEnv.getV0().getTx().getSourceAccountEd25519().getUint256());
        memo = txnEnv.getV0().getTx().getMemo();
        break;
      case ENVELOPE_TYPE_TX:
        operations = txnEnv.getV1().getTx().getOperations();
        sourceAccount =
            StrKey.encodeEd25519PublicKey(
                txnEnv.getV1().getTx().getSourceAccount().getEd25519().getUint256());
        memo = txnEnv.getV1().getTx().getMemo();
        break;
      case ENVELOPE_TYPE_TX_FEE_BUMP:
        if (txnEnv.getFeeBump().getTx().getInnerTx().getDiscriminant()
            == EnvelopeType.ENVELOPE_TYPE_TX) {
          Transaction txnFeeBump = txnEnv.getFeeBump().getTx().getInnerTx().getV1().getTx();
          operations = txnFeeBump.getOperations();
          sourceAccount =
              StrKey.encodeEd25519PublicKey(
                  txnFeeBump.getSourceAccount().getEd25519().getUint256());
          memo = txnFeeBump.getMemo();
        } else {
          debugF("FeeBump tx does not have a ENVELOPE_TYPE_TX discriminant. tx.hash={}", txnHash);
          return null;
        }
        break;
      default:
        debugF(
            "Error parsing transaction: (hash={}, discriminant={}). ",
            txnHash,
            txnEnv.getDiscriminant());

        return null;
    }

    return new ParseResult(operations, sourceAccount, memo);
  }

  public record ParseResult(Operation[] operations, String sourceAccount, Memo memo) {}

  public static List<LedgerOperation> getLedgerOperations(
      Integer applicationOrder,
      Long sequenceNumber,
      ParseResult parseResult,
      OperationResult[] operationResults)
      throws LedgerException {
    return getLedgerOperations(
        applicationOrder, sequenceNumber, parseResult, operationResults, null, null);
  }

  public static List<LedgerOperation> getLedgerOperations(
      Integer applicationOrder,
      Long sequenceNumber,
      ParseResult parseResult,
      OperationResult[] operationResults,
      List<List<ContractEvent>> perOperationContractEvents,
      Function<String, Asset> sacResolver)
      throws LedgerException {
    if (operationResults != null && operationResults.length != parseResult.operations().length) {
      throw new LedgerException(
          "Operation/result count mismatch ("
              + parseResult.operations().length
              + " operations vs "
              + operationResults.length
              + " results); refusing to process to avoid misattributing amounts.");
    }
    List<LedgerOperation> operations = new ArrayList<>(parseResult.operations().length);
    for (int opIndex = 0; opIndex < parseResult.operations().length; opIndex++) {
      OperationResult opResult = operationResults == null ? null : operationResults[opIndex];
      LedgerOperation ledgerOp =
          LedgerClientHelper.convert(
              parseResult.sourceAccount(),
              sequenceNumber,
              applicationOrder,
              opIndex + 1, // operation index is 1-based
              parseResult.operations()[opIndex],
              opResult);
      List<ContractEvent> contractEvents =
          perOperationContractEvents != null && opIndex < perOperationContractEvents.size()
              ? perOperationContractEvents.get(opIndex)
              : null;
      if (contractEvents != null
          && (ledgerOp == null || ledgerOp.getType() == INVOKE_HOST_FUNCTION)) {
        List<LedgerOperation> verifiedTransfers =
            synthesizeVerifiedTransfers(
                parseResult.sourceAccount(),
                sequenceNumber,
                applicationOrder,
                opIndex + 1,
                contractEvents,
                sacResolver);
        operations.addAll(verifiedTransfers);
        continue;
      }
      if (ledgerOp != null) {
        operations.add(ledgerOp);
      }
    }
    return operations;
  }

  private static List<LedgerOperation> synthesizeVerifiedTransfers(
      String sourceAccount,
      Long sequenceNumber,
      Integer applicationOrder,
      int opIndex,
      List<ContractEvent> contractEvents,
      Function<String, Asset> sacResolver) {
    List<LedgerOperation> verified = new ArrayList<>();
    if (contractEvents == null || sacResolver == null) {
      return verified;
    }
    String operationId =
        String.valueOf(new TOID(sequenceNumber.intValue(), applicationOrder, opIndex).toInt64());
    for (ContractEvent event : contractEvents) {
      TransferEventData data = parseTransferEvent(event);
      if (data == null) {
        continue;
      }
      String contractId;
      try {
        contractId = StrKey.encodeContract(event.getContractID().toXdrByteArray());
      } catch (IOException ioex) {
        continue;
      }
      Asset canonicalAsset = sacResolver.apply(contractId);
      if (canonicalAsset == null) {
        continue;
      }
      String canonicalSep11Asset = AssetHelper.getSep11AssetName(canonicalAsset);
      if (!canonicalSep11Asset.equals(data.sep11Asset())) {
        continue;
      }
      verified.add(
          LedgerOperation.builder()
              .type(INVOKE_HOST_FUNCTION)
              .invokeHostFunctionOperation(
                  LedgerInvokeHostFunctionOperation.builder()
                      .id(operationId)
                      .contractId(contractId)
                      .hostFunction("transfer")
                      .from(data.fromAddr())
                      .to(data.toAddr())
                      .amount(data.amount())
                      .asset(canonicalAsset)
                      .sourceAccount(sourceAccount)
                      .build())
              .build());
    }
    return verified;
  }

  public record TransferEventData(
      String fromAddr, String toAddr, BigInteger amount, String sep11Asset, String eventMemo) {}

  public static TransferEventData parseTransferEvent(ContractEvent event) {
    if (event.getType() != ContractEventType.CONTRACT || event.getBody().getV0() == null) {
      return null;
    }
    SCVal[] topics = event.getBody().getV0().getTopics();
    if (topics == null || topics.length != 4) {
      return null;
    }

    SCVal function = topics[0];
    SCVal from = topics[1];
    SCVal to = topics[2];
    SCVal asset = topics[3];

    if (function.getDiscriminant() != SCValType.SCV_SYMBOL
        || !function.getSym().getSCSymbol().toString().equals("transfer")) {
      return null;
    }
    if (from.getDiscriminant() != SCValType.SCV_ADDRESS
        || to.getDiscriminant() != SCValType.SCV_ADDRESS
        || asset.getDiscriminant() != SCValType.SCV_STRING) {
      return null;
    }

    String fromAddr;
    String toAddr;
    try {
      fromAddr = Scv.fromAddress(from).toString();
      toAddr = Scv.fromAddress(to).toString();
    } catch (RuntimeException ex) {
      return null;
    }

    BigInteger amount;
    String eventMemo = null;
    SCVal scValue = event.getBody().getV0().getData();
    if (scValue.getDiscriminant() == SCValType.SCV_I128) {
      amount = Scv.fromInt128(scValue);
    } else if (scValue.getDiscriminant() == SCValType.SCV_MAP) {
      var entries = scValue.getMap() == null ? null : scValue.getMap().getSCMap();
      if (entries == null || entries.length < 2) {
        return null;
      }
      SCVal amountVal = entries[0].getVal();
      SCVal memoVal = entries[1].getVal();
      if (amountVal.getDiscriminant() != SCValType.SCV_I128) {
        return null;
      }
      amount = Scv.fromInt128(amountVal);
      eventMemo =
          switch (memoVal.getDiscriminant()) {
            case SCV_STRING -> memoVal.getStr().getSCString().toString();
            case SCV_U64 -> memoVal.getU64().toString();
            case SCV_BYTES ->
                new String(Base64.getEncoder().encode(memoVal.getBytes().getSCBytes()));
            default -> null;
          };
      if (memoVal.getDiscriminant() == SCValType.SCV_U64) {
        try {
          toAddr =
              new MuxedAccount(Scv.fromAddress(to).toString(), Scv.fromUint64(memoVal))
                  .getAddress();
        } catch (IllegalArgumentException iae) {
          warnF(
              "Cannot build MuxedAccount for address '{}', using unmuxed value. ex={}",
              toAddr,
              iae.getMessage());
        }
      }
    } else {
      return null;
    }

    return new TransferEventData(
        fromAddr, toAddr, amount, asset.getStr().getSCString().toString(), eventMemo);
  }

  public static LedgerTransaction waitForTransactionAvailable(
      LedgerClient ledgerClient, String txhHash) throws LedgerException {
    return waitForTransactionAvailable(ledgerClient, txhHash, 10, 10);
  }

  public static LedgerTransaction waitForTransactionAvailable(
      LedgerClient ledgerClient, String txhHash, long maxTimeout, int maxPollCount)
      throws LedgerException {
    Instant startTime = Instant.now();
    int pollCount = 0;
    try {
      do {
        if (Duration.between(startTime, Instant.now()).getSeconds() > maxTimeout
            || pollCount >= maxPollCount)
          throw new InterruptedException("Transaction took too long to complete");
        try {
          LedgerTransaction txn = ledgerClient.getTransaction(txhHash);
          if (txn != null) return txn;
          pollCount++;
        } catch (BadRequestException e) {
          debug("Transaction not yet available: " + e.getMessage());
        }
        SECONDS.sleep(1);
      } while (true);
    } catch (InterruptedException e) {
      info("Interrupted while waiting for transaction to complete");
    }
    throw new LedgerException("Transaction took too long to complete");
  }
}
