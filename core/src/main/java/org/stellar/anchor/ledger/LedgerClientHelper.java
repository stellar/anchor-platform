package org.stellar.anchor.ledger;

import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;

import org.stellar.anchor.api.exception.LedgerException;
import org.stellar.sdk.StrKey;
import org.stellar.sdk.TOID;
import org.stellar.sdk.xdr.*;

public class LedgerClientHelper {

  static LedgerOperation convert(
      String sourceAccount,
      Long sequenceNumber,
      Integer applicationOrder,
      int opIndex,
      Operation op) {
    if (op == null) {
      return null;
    }
    if (op.getBody() == null) {
      return null;
    }
    String operationId =
        String.valueOf(new TOID(sequenceNumber.intValue(), applicationOrder, opIndex).toInt64());
    PaymentOp payment = op.getBody().getPaymentOp();
    return switch (op.getBody().getDiscriminant()) {
      case PAYMENT ->
          LedgerOperation.builder()
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
      case PATH_PAYMENT_STRICT_RECEIVE, PATH_PAYMENT_STRICT_SEND ->
          LedgerOperation.builder()
              .type(
                  switch (op.getBody().getDiscriminant()) {
                    case PATH_PAYMENT_STRICT_RECEIVE -> OperationType.PATH_PAYMENT_STRICT_RECEIVE;
                    case PATH_PAYMENT_STRICT_SEND -> OperationType.PATH_PAYMENT_STRICT_SEND;
                    default -> null;
                  })
              .pathPaymentOperation(
                  LedgerPathPaymentOperation.builder()
                      .id(operationId)
                      .asset(payment.getAsset())
                      .amount(payment.getAmount().getInt64())
                      .from(sourceAccount)
                      .to(
                          StrKey.encodeEd25519PublicKey(
                              payment.getDestination().getEd25519().getUint256()))
                      .sourceAccount(sourceAccount)
                      .build())
              .build();
      default -> null;
    };
  }

  public static ParsedTransaction parseTransaction(TransactionEnvelope txnEnv, String txnHash)
      throws LedgerException {
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
        memo = txnEnv.getV1().getTx().getMemo(); // Fixed: was incorrectly using V0 memo
        break;
      default:
        throw new LedgerException(
            String.format(
                "Malformed transaction detected. The transaction(hash=%s) has unknown envelope type.",
                txnHash));
    }

    return new ParsedTransaction(operations, sourceAccount, memo);
  }

  public static class ParsedTransaction {
    public final Operation[] operations;
    public final String sourceAccount;
    public final Memo memo;

    public ParsedTransaction(Operation[] operations, String sourceAccount, Memo memo) {
      this.operations = operations;
      this.sourceAccount = sourceAccount;
      this.memo = memo;
    }
  }
}
