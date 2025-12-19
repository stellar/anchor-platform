package org.stellar.anchor.ledger;

import static org.stellar.sdk.responses.sorobanrpc.SendTransactionResponse.SendTransactionStatus;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
  @AllArgsConstructor
  @NoArgsConstructor
  public static class LedgerOperation {
    OperationType type;
    LedgerPaymentOperation paymentOperation;
    LedgerPathPaymentOperation pathPaymentOperation;
    LedgerInvokeHostFunctionOperation invokeHostFunctionOperation;
  }

  public interface LedgerPayment {
    OperationType getType();

    String getId();

    String getFrom();

    String getTo();

    // The amount is in the smallest unit of the asset as in 10^-7.
    BigInteger getAmount();

    Asset getAsset();

    String getSourceAccount();
  }

  @Builder
  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class LedgerPaymentOperation implements LedgerPayment {
    String id;
    String from;
    String to;
    BigInteger amount;
    Asset asset;
    String sourceAccount;

    @Override
    public OperationType getType() {
      return OperationType.PAYMENT;
    }
  }

  @Builder
  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class LedgerPathPaymentOperation implements LedgerPayment {
    OperationType type;
    String id;
    String from;
    String to;
    BigInteger amount;
    Asset asset;
    String sourceAccount;
  }

  @Builder
  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class LedgerInvokeHostFunctionOperation implements LedgerPayment {
    String contractId;
    String hostFunction;

    String id;
    String from;
    String to;
    BigInteger amount;
    Asset asset;
    String sourceAccount;

    @Override
    public OperationType getType() {
      return OperationType.INVOKE_HOST_FUNCTION;
    }

    public Asset getAsset(boolean checkContractId) {
      if (asset == null) {
        if (checkContractId && contractId != null) {
          // The SAC to Asset conversion requires a network call to the ledger. This should be
          // converted before using the operation.
          throw new IllegalStateException(
              "Please convert stellarAssetContractId to Asset before calling getAsset(true)");
        }
      }
      return asset;
    }

    public Asset getAsset() {
      return getAsset(true);
    }
  }

  @Builder
  @Data
  public static class LedgerTransactionResponse {
    String hash;
    String errorResultXdr;
    SendTransactionStatus status;
  }
}
