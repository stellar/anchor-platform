package org.stellar.anchor.ledger;

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
    String assetType;
    String sourceAccount;
    String from;
    String to;
    String amount;
    Asset asset;
  }

  @Builder
  @Data
  public static class LedgerPathPaymentOperation {
    String assetType;
    String sourceAccount;
    String sourceAmount;
    Asset sourceAsset;
    String from;
    String to;
    String amount;
    Asset asset;
  }

  @Builder
  @Data
  public static class LedgerTransactionResponse {
    String hash;
    String envelopXdr;
    String sourceAccount;
    String feeCharged;
    Memo memo;
    Long sequenceNumber;
    Instant createdAt;
  }
}
