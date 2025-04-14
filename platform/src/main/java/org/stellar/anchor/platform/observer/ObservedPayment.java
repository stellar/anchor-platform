package org.stellar.anchor.platform.observer;

import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.anchor.util.AssetHelper.*;
import static org.stellar.sdk.Memo.fromXdr;

import com.google.gson.annotations.SerializedName;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.xdr.Memo;

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

  @SneakyThrows
  public static ObservedPayment from(LedgerTransaction txn, LedgerPaymentOperation paymentOp) {
    String assetName = getSep11AssetName(paymentOp.getAsset());
    String sourceAccount = txn.getSourceAccount();
    String from = paymentOp.getFrom() != null ? paymentOp.getFrom() : sourceAccount;
    Memo memo = txn.getMemo();

    return ObservedPayment.builder()
        .id(paymentOp.getId())
        .type(Type.PAYMENT)
        .from(from)
        .to(paymentOp.getTo())
        .amount(fromXdrAmount(paymentOp.getAmount()))
        .assetCode(AssetHelper.getAssetCode(assetName))
        .assetIssuer(AssetHelper.getAssetIssuer(assetName))
        .assetName(assetName)
        .sourceAccount(sourceAccount)
        .createdAt(txn.getCreatedAt().toString())
        .transactionHash(txn.getHash())
        .transactionMemo(MemoHelper.memoAsString(fromXdr(txn.getMemo())))
        .transactionMemoType(MemoHelper.memoTypeAsString(fromXdr(memo)))
        .transactionEnvelope(txn.getEnvelopeXdr())
        .build();
  }

  @SneakyThrows
  public static ObservedPayment from(
      LedgerTransaction ledgerTxn, LedgerPathPaymentOperation pathPaymentOp) {
    String assetName = getSep11AssetName(pathPaymentOp.getAsset());
    String sourceAssetName = getSep11AssetName(pathPaymentOp.getSourceAsset());
    String sourceAccount =
        pathPaymentOp.getSourceAccount() != null
            ? pathPaymentOp.getSourceAccount()
            : ledgerTxn.getSourceAccount();
    String from = pathPaymentOp.getFrom() != null ? pathPaymentOp.getFrom() : sourceAccount;
    Memo memo = ledgerTxn.getMemo();

    return ObservedPayment.builder()
        .id(pathPaymentOp.getId())
        .type(Type.PATH_PAYMENT)
        .from(from)
        .to(pathPaymentOp.getTo())
        .amount(AssetHelper.fromXdrAmount(pathPaymentOp.getAmount()))
        .assetCode(AssetHelper.getAssetCode(assetName))
        .assetIssuer(AssetHelper.getAssetIssuer(assetName))
        .assetName(pathPaymentOp.getAsset().toString())
        .sourceAmount(pathPaymentOp.getSourceAmount())
        .sourceAssetType(pathPaymentOp.getSourceAsset().getDiscriminant().name())
        .sourceAssetName(sourceAssetName)
        .sourceAssetCode(AssetHelper.getAssetCode(sourceAssetName))
        .sourceAssetIssuer(AssetHelper.getAssetIssuer(sourceAssetName))
        .sourceAccount(sourceAccount)
        .createdAt(ledgerTxn.getCreatedAt().toString())
        .transactionHash(ledgerTxn.getHash())
        .transactionMemo(MemoHelper.memoAsString(fromXdr(memo)))
        .transactionMemoType(MemoHelper.memoTypeAsString(fromXdr(memo)))
        .transactionEnvelope(ledgerTxn.getEnvelopeXdr())
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
