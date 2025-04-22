package org.stellar.anchor.ledger;

import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.sdk.xdr.OperationType.PAYMENT;

import org.stellar.sdk.StrKey;
import org.stellar.sdk.TOID;
import org.stellar.sdk.xdr.Operation;
import org.stellar.sdk.xdr.OperationType;
import org.stellar.sdk.xdr.PaymentOp;

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
                      .build())
              .build();
      default -> null;
    };
  }
}
