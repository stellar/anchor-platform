package org.stellar.anchor.platform.custody;

import static org.stellar.anchor.ledger.LedgerTransaction.*;
import static org.stellar.anchor.util.AssetHelper.fromXdrAmount;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import lombok.Builder;
import lombok.Data;
import org.stellar.anchor.api.exception.SepException;
import org.stellar.anchor.ledger.LedgerTransaction;
import org.stellar.anchor.util.AssetHelper;
import org.stellar.anchor.util.MemoHelper;
import org.stellar.sdk.Memo;

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
      LedgerTransaction ledgerTxn,
      LedgerPaymentOperation paymentOperation,
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

    if (paymentOperation != null) {
      id = paymentOperation.getId();
      to = paymentOperation.getTo();
      amount = fromXdrAmount(paymentOperation.getAmount());
      assetType = AssetHelper.getAssetType(paymentOperation.getAsset());
      assetName = AssetHelper.getSep11AssetName(paymentOperation.getAsset());
      assetCode = AssetHelper.getAssetCode(assetName);
      assetIssuer = AssetHelper.getAssetIssuer(assetName);

      String sourceAccount = ledgerTxn.getSourceAccount();
      from = paymentOperation.getFrom() != null ? paymentOperation.getFrom() : sourceAccount;
      Memo memo = Memo.fromXdr(ledgerTxn.getMemo());

      transactionMemo = MemoHelper.memoAsString(memo);
      transactionMemoType = MemoHelper.memoTypeAsString(memo);
      transactionEnvelope = ledgerTxn.getEnvelopeXdr();
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
      LedgerTransaction ledgerTxn,
      LedgerPathPaymentOperation operation,
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

    if (operation != null) {
      id = operation.getId();
      to = operation.getTo();
      amount = fromXdrAmount(operation.getAmount());
      assetType = AssetHelper.getAssetType(operation.getAsset());
      assetName = AssetHelper.getSep11AssetName(operation.getAsset());
      assetCode = AssetHelper.getAssetCode(assetName);
      assetIssuer = AssetHelper.getAssetIssuer(assetName);

      String sourceAccount =
          operation.getSourceAccount() != null
              ? operation.getSourceAccount()
              : ledgerTxn.getSourceAccount();
      from = operation.getFrom() != null ? operation.getFrom() : sourceAccount;
      Memo memo = Memo.fromXdr(ledgerTxn.getMemo());

      transactionMemo = MemoHelper.memoAsString(memo);
      transactionMemoType = MemoHelper.memoTypeAsString(memo);
      transactionEnvelope = ledgerTxn.getEnvelopeXdr();
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
