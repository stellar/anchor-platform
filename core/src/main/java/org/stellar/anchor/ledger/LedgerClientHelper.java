package org.stellar.anchor.ledger;

import static java.lang.Thread.sleep;
import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.anchor.util.AssetHelper.toXdrAmount;
import static org.stellar.anchor.util.Log.*;
import static org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.*;
import static org.stellar.sdk.xdr.OperationType.PATH_PAYMENT_STRICT_RECEIVE;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;
import static org.stellar.sdk.xdr.SignerKeyType.*;
import static org.stellar.sdk.xdr.SignerKeyType.SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.sdk.StrKey;
import org.stellar.sdk.TOID;
import org.stellar.sdk.responses.SubmitTransactionAsyncResponse;
import org.stellar.sdk.responses.operations.OperationResponse;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionResponse;
import org.stellar.sdk.responses.sorobanrpc.GetTransactionsResponse;
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
                    .amount(payment.getAmount().getInt64())
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
                  case PATH_PAYMENT_STRICT_SEND -> OperationType.PATH_PAYMENT_STRICT_SEND;
                  default -> null;
                })
            .pathPaymentOperation(
                LedgerPathPaymentOperation.builder()
                    .id(operationId)
                    .asset(asset)
                    .amount(amount)
                    .from(sourceAccount)
                    .to(toAddress)
                    .sourceAccount(sourceAccount)
                    .build())
            .build();
      }
      default -> null;
    };
  }

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

  /**
   * Convert from Horizon signer key type to XDR signer key type.
   *
   * @param type the Horizon signer key type
   * @return the XDR signer key type
   */
  public static SignerKeyType getKeyTypeDiscriminant(String type) {
    return switch (type) {
      case "ed25519_public_key" -> SIGNER_KEY_TYPE_ED25519;
      case "preauth_tx" -> SIGNER_KEY_TYPE_PRE_AUTH_TX;
      case "sha256_hash" -> SIGNER_KEY_TYPE_HASH_X;
      case "ed25519_signed_payload" -> SIGNER_KEY_TYPE_ED25519_SIGNED_PAYLOAD;
      default -> throw new IllegalArgumentException("Invalid signer key type: " + type);
    };
  }

  public static SendTransactionStatus convert(
      SubmitTransactionAsyncResponse.TransactionStatus status) {
    return switch (status) {
      case PENDING -> SendTransactionStatus.PENDING;
      case ERROR -> SendTransactionStatus.ERROR;
      case DUPLICATE -> SendTransactionStatus.DUPLICATE;
      case TRY_AGAIN_LATER -> SendTransactionStatus.TRY_AGAIN_LATER;
    };
  }

  public static List<LedgerOperation> getLedgerOperations(
      Integer applicationOrder, Long sequenceNumber, ParseResult parseResult)
      throws LedgerException {
    List<LedgerTransaction.LedgerOperation> operations =
        new ArrayList<>(parseResult.operations().length);
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
        if (java.time.Duration.between(startTime, Instant.now()).getSeconds() > maxTimeout
            || pollCount >= maxPollCount)
          throw new InterruptedException("Transaction took too long to complete");
        try {
          LedgerTransaction txn = ledgerClient.getTransaction(txhHash);
          if (txn != null) return txn;
          pollCount++;
        } catch (org.stellar.sdk.exception.BadRequestException e) {
          debug("Transaction not yet available: " + e.getMessage());
        }
        sleep(1000);

      } while (true);
    } catch (InterruptedException e) {
      info("Interrupted while waiting for transaction to complete");
    }
    throw new LedgerException("Transaction took too long to complete");
  }

  public static LedgerOperation toLedgerOperation(OperationResponse op) {
    LedgerOperation.LedgerOperationBuilder builder = LedgerOperation.builder();
    // TODO: Capture muxed account events
    if (op instanceof PaymentOperationResponse paymentOp) {
      builder.type(PAYMENT);
      builder.paymentOperation(
          LedgerTransaction.LedgerPaymentOperation.builder()
              .id(String.valueOf(paymentOp.getId()))
              .from(paymentOp.getFrom())
              .to(paymentOp.getTo())
              .amount(toXdrAmount(paymentOp.getAmount()))
              .asset(paymentOp.getAsset().toXdr())
              .sourceAccount(paymentOp.getSourceAccount())
              .build());
    } else if (op instanceof PathPaymentBaseOperationResponse pathPaymentOp) {
      builder.type(PATH_PAYMENT_STRICT_RECEIVE);
      builder.pathPaymentOperation(
          LedgerTransaction.LedgerPathPaymentOperation.builder()
              .id(String.valueOf(pathPaymentOp.getId()))
              .from(pathPaymentOp.getFrom())
              .to(pathPaymentOp.getTo())
              .amount(toXdrAmount(pathPaymentOp.getAmount()))
              .asset(pathPaymentOp.getAsset().toXdr())
              .sourceAccount(pathPaymentOp.getSourceAccount())
              .build());
    } else {
      return null;
    }
    return builder.build();
  }

  /**
   * Convert a GetTransactionResponse to a LedgerTransaction.
   *
   * @param txnResponse the GetTransactionResponse to convert
   * @return the converted LedgerTransaction
   * @throws LedgerException if the transaction is null or malformed
   */
  public static LedgerTransaction fromGetTransactionResponse(GetTransactionResponse txnResponse)
      throws LedgerException {
    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txnResponse.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }
    Integer applicationOrder = txnResponse.getApplicationOrder();
    Long sequenceNumber = txnResponse.getLedger();
    ParseResult parseResult =
        LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, txnResponse.getTxHash());
    if (parseResult == null) return null;
    List<LedgerTransaction.LedgerOperation> operations =
        LedgerClientHelper.getLedgerOperations(applicationOrder, sequenceNumber, parseResult);

    return LedgerTransaction.builder()
        .hash(txnResponse.getTxHash())
        .ledger(txnResponse.getLedger())
        .applicationOrder(txnResponse.getApplicationOrder())
        .sourceAccount(parseResult.sourceAccount())
        .envelopeXdr(txnResponse.getEnvelopeXdr())
        .memo(parseResult.memo())
        .sequenceNumber(sequenceNumber)
        .createdAt(Instant.ofEpochSecond(txnResponse.getCreatedAt()))
        .operations(operations)
        .build();
  }

  /**
   * Converting from Stellar RPC transaction to LedgerTransaction. TODO: This function will be
   * removed after migrating to getEvents methods.
   *
   * @param txn the Stellar RPC transaction to convert
   * @return the converted LedgerTransaction
   * @throws LedgerException if the transaction is null or malformed
   */
  public static LedgerTransaction fromStellarRpcTransaction(GetTransactionsResponse.Transaction txn)
      throws LedgerException {
    TransactionEnvelope txnEnv;
    try {
      txnEnv = TransactionEnvelope.fromXdrBase64(txn.getEnvelopeXdr());
    } catch (IOException ioex) {
      throw new LedgerException("Unable to parse transaction envelope", ioex);
    }
    Integer applicationOrder = txn.getApplicationOrder();
    Long sequenceNumber = txn.getLedger();
    ParseResult parseResult =
        LedgerClientHelper.parseOperationAndSourceAccountAndMemo(txnEnv, txn.getTxHash());
    if (parseResult == null) return null;
    List<LedgerTransaction.LedgerOperation> operations =
        LedgerClientHelper.getLedgerOperations(applicationOrder, sequenceNumber, parseResult);
    return LedgerTransaction.builder()
        .hash(txn.getTxHash())
        .ledger(txn.getLedger())
        .applicationOrder(txn.getApplicationOrder())
        .sourceAccount(parseResult.sourceAccount())
        .envelopeXdr(txn.getEnvelopeXdr())
        .memo(parseResult.memo())
        .sequenceNumber(sequenceNumber)
        .createdAt(Instant.ofEpochSecond(txn.getCreatedAt()))
        .operations(operations)
        .build();
  }
}
