package org.stellar.anchor.ledger;

import static org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.*;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.stellar.sdk.xdr.Asset;
import org.stellar.sdk.xdr.Memo;
import org.stellar.sdk.xdr.OperationType;

@Builder
@Data
public class LedgerTransaction {
  String hash;
  Long ledger;
  int applicationOrder;
  String envelopeXdr;
  String sourceAccount;
  Asset sourceAsset;
  Long fee;
  Memo memo;
  Long sequenceNumber;
  Instant createdAt;

  List<LedgerOperation> operations;

  @Builder
  @Data
  public static class LedgerOperation {
    OperationType type;
    LedgerPaymentOperation paymentOperation;
    LedgerPathPaymentOperation pathPaymentOperation;
  }

  @Builder
  @Data
  public static class LedgerPaymentOperation {
    String id;
    String from;
    String to;
    Long amount;
    Asset asset;
    String sourceAccount;
  }

  @Builder
  @Data
  public static class LedgerPathPaymentOperation {
    String id;
    String from;
    String to;
    Long amount;
    Asset asset;
    String sourceAccount;
  }

  @Builder
  @Data
  public static class LedgerTransactionResponse {
    String hash;
    String errorResultXdr;
    SendTransactionStatus status;
  }
}
