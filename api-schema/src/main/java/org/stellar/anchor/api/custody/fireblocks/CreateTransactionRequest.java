package org.stellar.anchor.api.custody.fireblocks;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateTransactionRequest {
  private String assetId;
  private TransferPeerPath source;
  private DestinationTransferPeerPath destination;
  private TransactionRequestDestination[] destinations;
  private String amount;
  private Boolean treatAsGrossAmount;
  private String fee;
  private String gasPrice;
  private String gasLimit;
  private String networkFee;
  private String priorityFee;
  private FeeLevel feeLevel;
  private String maxFee;
  private Boolean failOnLowFee;
  private Boolean forceSweep;
  private String note;
  private OperationType operation;
  private String customerRefId;
  private String replaceTxByHash;
  private String externalTxId;
  private Object extraParameters;

  @Data
  @AllArgsConstructor
  public static class TransferPeerPath {
    private TransferPeerPathType type;
    private String id;
  }

  @Data
  public static class DestinationTransferPeerPath {

    public DestinationTransferPeerPath(
        DestinationTransferPeerPathType type, OneTimeAddress oneTimeAddress) {
      this.type = type;
      this.oneTimeAddress = oneTimeAddress;
    }

    private DestinationTransferPeerPathType type;
    private String id;
    private OneTimeAddress oneTimeAddress;
  }

  @Data
  @AllArgsConstructor
  public static class OneTimeAddress {
    private String address;
    private String tag;
  }

  @Data
  @AllArgsConstructor
  public static class TransactionRequestDestination {
    private String amount;
    private DestinationTransferPeerPath destination;
  }

  public enum OperationType {
    BURN,
    CONTRACT_CALL,
    MINT,
    RAW,
    REDEEM_FROM_COMPOUND,
    SUPPLY_TO_COMPOUND,
    TRANSFER,
    TYPED_MESSAGE
  }

  public enum TransferPeerPathType {
    VAULT_ACCOUNT,
    EXCHANGE_ACCOUNT,
    FIAT_ACCOUNT,
    GAS_STATION
  }

  public enum DestinationTransferPeerPathType {
    VAULT_ACCOUNT,
    EXCHANGE_ACCOUNT,
    INTERNAL_WALLET,
    EXTERNAL_WALLET,
    ONE_TIME_ADDRESS,
    NETWORK_CONNECTION,
    FIAT_ACCOUNT,
    COMPOUND
  }

  public enum FeeLevel {
    LOW,
    MEDIUM,
    HIGH
  }
}
