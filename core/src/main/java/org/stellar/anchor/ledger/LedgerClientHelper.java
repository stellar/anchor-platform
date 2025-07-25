package org.stellar.anchor.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.sdk.xdr.HostFunctionType.HOST_FUNCTION_TYPE_INVOKE_CONTRACT;
import static org.stellar.sdk.xdr.OperationType.*;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.stellar.anchor.api.exception.LedgerException;
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
      Operation op)
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
        yield LedgerOperation.builder()
            .type(PAYMENT)
            .paymentOperation(
                LedgerPaymentOperation.builder()
                    .id(operationId)
                    .asset(payment.getAsset())
                    .amount(BigInteger.valueOf(payment.getAmount().getInt64()))
                    .from(sourceAccount)
                    .sourceAccount(sourceAccount)
                    .to(
                        StrKey.encodeEd25519PublicKey(
                            payment.getDestination().getEd25519().getUint256()))
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
          toAddress =
              StrKey.encodeEd25519PublicKey(
                  op.getBody()
                      .getPathPaymentStrictReceiveOp()
                      .getDestination()
                      .getEd25519()
                      .getUint256());

        } else {
          asset = op.getBody().getPathPaymentStrictSendOp().getDestAsset();
          amount = op.getBody().getPathPaymentStrictSendOp().getSendAmount().getInt64();
          toAddress =
              StrKey.encodeEd25519PublicKey(
                  op.getBody()
                      .getPathPaymentStrictSendOp()
                      .getDestination()
                      .getEd25519()
                      .getUint256());
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

  static String getAddressOrContractId(SCAddress address) throws IOException {
    return switch (address.getDiscriminant()) {
      case SC_ADDRESS_TYPE_ACCOUNT ->
          StrKey.encodeEd25519PublicKey(
              address.getAccountId().getAccountID().getEd25519().getUint256());
      case SC_ADDRESS_TYPE_CONTRACT ->
          StrKey.encodeContract(address.getContractId().toXdrByteArray());
      case SC_ADDRESS_TYPE_MUXED_ACCOUNT,
              SC_ADDRESS_TYPE_CLAIMABLE_BALANCE,
              SC_ADDRESS_TYPE_LIQUIDITY_POOL ->
          null;
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
      Integer applicationOrder, Long sequenceNumber, ParseResult parseResult)
      throws LedgerException {
    List<LedgerOperation> operations = new ArrayList<>(parseResult.operations().length);
    for (int opIndex = 0; opIndex < parseResult.operations().length; opIndex++) {
      LedgerOperation ledgerOp =
          LedgerClientHelper.convert(
              parseResult.sourceAccount(),
              sequenceNumber,
              applicationOrder,
              opIndex + 1, // operation index is 1-based
              parseResult.operations()[opIndex]);
      if (ledgerOp != null) {
        operations.add(ledgerOp);
      }
    }
    return operations;
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
