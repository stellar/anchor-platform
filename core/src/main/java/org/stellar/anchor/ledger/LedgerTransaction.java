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

  public interface LedgerPayment {
    String getId();

    String getFrom();

    String getTo();

    Long getAmount();

    Asset getAsset();

    String getSourceAccount();
  }

  @Builder
  @Data
  public static class LedgerPaymentOperation implements LedgerPayment {
    String id;
    String from;
    String to;
    Long amount;
    Asset asset;
    String sourceAccount;
  }

  @Builder
  @Data
  public static class LedgerPathPaymentOperation implements LedgerPayment {
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
