package org.stellar.anchor.ledger;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.stellar.sdk.Asset;
import org.stellar.sdk.Memo;
import org.stellar.sdk.xdr.OperationType;

@Builder
@Data
public class LedgerTransaction {
  String hash;
  String envelopXdr;
  String metaXdr;
  String sourceAccount;
  Long fee;
  Memo memo;
  Long sequenceNumber;
  String createdAt;

  List<LedgerOperation> operations;

  @Builder
  @Data
  public static class LedgerOperation {
    OperationType type;
    LedgerPaymentOperation paymentOperation;
    LedgerPathPaymentStrictReceiveOperation pathPaymentStrictReceiveOperation;
    LedgerPathPaymentStrictSendOperation pathPaymentStrictSendOperation;
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
  public static class LedgerPathPaymentStrictReceiveOperation {
    String assetType;
    String sourceAccount;
    String sourceAmount;
    String sourceAsset;
    String sourceAssetType;
    String from;
    String to;
    String amount;
    Asset asset;
  }

  @Builder
  @Data
  public static class LedgerPathPaymentStrictSendOperation {
    String assetType;
    String sourceAccount;
    String sourceAmount;
    String sourceAsset;
    String sourceAssetType;
    String from;
    String to;
    String amount;
    Asset asset;
  }

  //
  //  public interface LedgerPathPaymentOperation {
  //    String getAssetType();
  //
  //    String getSourceAccount();
  //
  //    String getSourceAmount();
  //
  //    String getSourceAsset();
  //
  //    String getSourceAssetType();
  //
  //    String getFrom();
  //
  //    String getTo();
  //
  //    String getAmount();
  //
  //    Asset getAsset();
  //  }

  @Builder
  @Data
  public static class LedgerTransactionResponse {
    String hash;
    String envelopXdr;
    String metaXdr;
    String sourceAccount;
    String feeCharged;
    Memo memo;
    Long sequenceNumber;
    String createdAt;
  }
}
