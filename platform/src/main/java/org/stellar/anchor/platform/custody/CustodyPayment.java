package org.stellar.anchor.platform.custody;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.AssetTypeCreditAlphaNum;
import org.stellar.sdk.AssetTypeNative;
import org.stellar.sdk.Memo;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

/**
 * A class, that contains payment (inbound/outbound) information for custody transaction. It is an
 * abstract representation of payment and is not custody-specific
 */
@Data
@Builder
public class CustodyPayment {

  String id;
  String externalTxId;
  Type type;

  String from;
  String to;

  String amount;
  String assetType;
  String assetCode;
  String assetIssuer;
  String assetName;

  Instant updatedAt;
  CustodyPaymentStatus status;
  String message;

  String transactionHash;
  String transactionMemo;
  String transactionMemoType;
  String transactionEnvelope;

  public static CustodyPayment fromPayment(
      Optional<PaymentOperationResponse> paymentOperation,
      String externalTxId,
      Instant updatedAt,
      CustodyPaymentStatus status,
      String message,
      String transactionHash)
      throws SepException {
    String id = null;
    String from = null;
    String to = null;
    String assetCode = null;
    String assetIssuer = null;
    String amount = null;
    String assetType = null;
    String assetName = null;
    String transactionMemo = null;
    String transactionMemoType = null;
    String transactionEnvelope = null;

    if (paymentOperation.isPresent()) {
      id = paymentOperation.get().getId().toString();
      to = paymentOperation.get().getTo();
      amount = paymentOperation.get().getAmount();
      assetType = paymentOperation.get().getAssetType();
      assetName = paymentOperation.get().getAsset().toString();

      if (paymentOperation.get().getAsset() instanceof AssetTypeCreditAlphaNum) {
        AssetTypeCreditAlphaNum issuedAsset =
            (AssetTypeCreditAlphaNum) paymentOperation.get().getAsset();
        assetCode = issuedAsset.getCode();
        assetIssuer = issuedAsset.getIssuer();
      } else if (paymentOperation.get().getAsset() instanceof AssetTypeNative) {
        assetCode = paymentOperation.get().getAssetType(); // "native"
      }

      String sourceAccount =
          paymentOperation.get().getSourceAccount() != null
              ? paymentOperation.get().getSourceAccount()
              : paymentOperation.get().getTransaction().getSourceAccount();
      from =
          paymentOperation.get().getFrom() != null
              ? paymentOperation.get().getFrom()
              : sourceAccount;
      Memo memo = paymentOperation.get().getTransaction().getMemo();

      transactionMemo = MemoHelper.memoAsString(memo);
      transactionMemoType = MemoHelper.memoTypeAsString(memo);
      transactionEnvelope = paymentOperation.get().getTransaction().getEnvelopeXdr();
    }

    return CustodyPayment.builder()
        .id(id)
        .externalTxId(externalTxId)
        .type(Type.PAYMENT)
        .from(from)
        .to(to)
        .amount(amount)
        .assetType(assetType)
        .assetCode(assetCode)
        .assetIssuer(assetIssuer)
        .assetName(assetName)
        .updatedAt(updatedAt)
        .status(status)
        .message(message)
        .transactionHash(transactionHash)
        .transactionMemo(transactionMemo)
        .transactionMemoType(transactionMemoType)
        .transactionEnvelope(transactionEnvelope)
        .build();
  }

  public static CustodyPayment fromPathPayment(
      Optional<PathPaymentBaseOperationResponse> pathPaymentOperation,
      String externalTxId,
      Instant updatedAt,
      CustodyPaymentStatus status,
      String message,
      String transactionHash)
      throws SepException {
    String id = null;
    String from = null;
    String to = null;
    String assetCode = null;
    String assetIssuer = null;
    String amount = null;
    String assetType = null;
    String assetName = null;
    String transactionMemo = null;
    String transactionMemoType = null;
    String transactionEnvelope = null;

    if (pathPaymentOperation.isPresent()) {
      id = pathPaymentOperation.get().getId().toString();
      to = pathPaymentOperation.get().getTo();
      amount = pathPaymentOperation.get().getAmount();
      assetType = pathPaymentOperation.get().getAssetType();
      assetName = pathPaymentOperation.get().getAsset().toString();

      if (pathPaymentOperation.get().getAsset() instanceof AssetTypeCreditAlphaNum) {
        AssetTypeCreditAlphaNum issuedAsset =
            (AssetTypeCreditAlphaNum) pathPaymentOperation.get().getAsset();
        assetCode = issuedAsset.getCode();
        assetIssuer = issuedAsset.getIssuer();
      } else if (pathPaymentOperation.get().getAsset() instanceof AssetTypeNative) {
        assetCode = pathPaymentOperation.get().getAssetType(); // "native"
      }

      String sourceAccount =
          pathPaymentOperation.get().getSourceAccount() != null
              ? pathPaymentOperation.get().getSourceAccount()
              : pathPaymentOperation.get().getTransaction().getSourceAccount();
      from =
          pathPaymentOperation.get().getFrom() != null
              ? pathPaymentOperation.get().getFrom()
              : sourceAccount;
      Memo memo = pathPaymentOperation.get().getTransaction().getMemo();

      transactionMemo = MemoHelper.memoAsString(memo);
      transactionMemoType = MemoHelper.memoTypeAsString(memo);
      transactionEnvelope = pathPaymentOperation.get().getTransaction().getEnvelopeXdr();
    }

    return CustodyPayment.builder()
        .id(id)
        .externalTxId(externalTxId)
        .type(Type.PATH_PAYMENT)
        .from(from)
        .to(to)
        .amount(amount)
        .assetType(assetType)
        .assetCode(assetCode)
        .assetIssuer(assetIssuer)
        .assetName(assetName)
        .updatedAt(updatedAt)
        .status(status)
        .message(message)
        .transactionHash(transactionHash)
        .transactionMemo(transactionMemo)
        .transactionMemoType(transactionMemoType)
        .transactionEnvelope(transactionEnvelope)
        .build();
  }

  public enum CustodyPaymentStatus {
    SUCCESS,
    ERROR
  }

  public enum Type {
    @SerializedName("payment")
    PAYMENT("payment"),

    @SerializedName("path_payment")
    PATH_PAYMENT("path_payment");

    private final String name;

    Type(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
