package org.stellar.anchor.platform.observer;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.asset.AssetInfo;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.*;
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse;
import org.stellar.sdk.responses.operations.PaymentOperationResponse;

@Builder
@Data
public class ObservedPayment {
  String id;
  String externalTransactionId;
  Type type;

  String from;
  String to;

  String amount;
  String assetType;
  String assetCode;
  String assetIssuer;
  String assetName;

  String sourceAmount;
  String sourceAssetType;
  String sourceAssetCode;
  String sourceAssetIssuer;
  String sourceAssetName;

  String sourceAccount;
  String createdAt;

  String transactionHash;
  String transactionMemo;
  String transactionMemoType;
  String transactionEnvelope;

  public static ObservedPayment fromPaymentOperationResponse(PaymentOperationResponse paymentOp)
      throws SepException {
    String assetCode = null, assetIssuer = null;

    if (paymentOp.getAsset() instanceof AssetTypeCreditAlphaNum) {
      AssetTypeCreditAlphaNum issuedAsset = (AssetTypeCreditAlphaNum) paymentOp.getAsset();
      assetCode = issuedAsset.getCode();
      assetIssuer = issuedAsset.getIssuer();
    } else if (paymentOp.getAsset() instanceof AssetTypeNative) {
      assetCode = AssetInfo.NATIVE_ASSET_CODE;
    }

    String sourceAccount =
        paymentOp.getSourceAccount() != null
            ? paymentOp.getSourceAccount()
            : paymentOp.getTransaction().get().getSourceAccount();
    String from = paymentOp.getFrom() != null ? paymentOp.getFrom() : sourceAccount;
    Memo memo = paymentOp.getTransaction().get().getMemo();
    return ObservedPayment.builder()
        .id(paymentOp.getId().toString())
        .type(Type.PAYMENT)
        .from(from)
        .to(paymentOp.getTo())
        .amount(paymentOp.getAmount())
        .assetType(paymentOp.getAsset().getType())
        .assetCode(assetCode)
        .assetIssuer(assetIssuer)
        .assetName(paymentOp.getAsset().toString())
        .sourceAccount(sourceAccount)
        .createdAt(paymentOp.getCreatedAt())
        .transactionHash(paymentOp.getTransactionHash())
        .transactionMemo(MemoHelper.memoAsString(memo))
        .transactionMemoType(MemoHelper.memoTypeAsString(memo))
        .transactionEnvelope(paymentOp.getTransaction().get().getEnvelopeXdr())
        .build();
  }

  public static ObservedPayment fromPathPaymentOperationResponse(
      PathPaymentBaseOperationResponse pathPaymentOp) throws SepException {
    String assetCode = null, assetIssuer = null;
    if (pathPaymentOp.getAsset() instanceof AssetTypeCreditAlphaNum) {
      AssetTypeCreditAlphaNum issuedAsset = (AssetTypeCreditAlphaNum) pathPaymentOp.getAsset();
      assetCode = issuedAsset.getCode();
      assetIssuer = issuedAsset.getIssuer();
    } else if (pathPaymentOp.getAsset() instanceof AssetTypeNative) {
      assetCode = AssetInfo.NATIVE_ASSET_CODE;
    }

    String sourceAssetCode = null, sourceAssetIssuer = null;
    if (pathPaymentOp.getSourceAsset() instanceof AssetTypeCreditAlphaNum) {
      AssetTypeCreditAlphaNum sourceIssuedAsset =
          (AssetTypeCreditAlphaNum) pathPaymentOp.getSourceAsset();
      sourceAssetCode = sourceIssuedAsset.getCode();
      sourceAssetIssuer = sourceIssuedAsset.getIssuer();
    } else if (pathPaymentOp.getSourceAsset() instanceof AssetTypeNative) {
      sourceAssetCode = AssetInfo.NATIVE_ASSET_CODE;
    }

    String sourceAccount =
        pathPaymentOp.getSourceAccount() != null
            ? pathPaymentOp.getSourceAccount()
            : pathPaymentOp.getTransaction().get().getSourceAccount();
    String from = pathPaymentOp.getFrom() != null ? pathPaymentOp.getFrom() : sourceAccount;
    Memo memo = pathPaymentOp.getTransaction().get().getMemo();
    return ObservedPayment.builder()
        .id(pathPaymentOp.getId().toString())
        .type(Type.PATH_PAYMENT)
        .from(from)
        .to(pathPaymentOp.getTo())
        .amount(pathPaymentOp.getAmount())
        .assetType(pathPaymentOp.getAsset().getType())
        .assetCode(assetCode)
        .assetIssuer(assetIssuer)
        .assetName(pathPaymentOp.getAsset().toString())
        .sourceAmount(pathPaymentOp.getSourceAmount())
        .sourceAssetType(pathPaymentOp.getSourceAsset().getType())
        .sourceAssetCode(sourceAssetCode)
        .sourceAssetIssuer(sourceAssetIssuer)
        .sourceAssetName(pathPaymentOp.getSourceAsset().toString())
        .sourceAccount(sourceAccount)
        .createdAt(pathPaymentOp.getCreatedAt())
        .transactionHash(pathPaymentOp.getTransactionHash())
        .transactionMemo(MemoHelper.memoAsString(memo))
        .transactionMemoType(MemoHelper.memoTypeAsString(memo))
        .transactionEnvelope(pathPaymentOp.getTransaction().get().getEnvelopeXdr())
        .build();
  }

  public enum Type {
    @SerializedName("payment")
    PAYMENT("payment"),

    @SerializedName("path_payment")
    PATH_PAYMENT("path_payment"),

    @SerializedName("circle_transfer")
    CIRCLE_TRANSFER("circle_transfer");

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
