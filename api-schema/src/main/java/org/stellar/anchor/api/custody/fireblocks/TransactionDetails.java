package org.stellar.anchor.api.custody.fireblocks;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransactionDetails {
  private String id;
  private String assetId;
  private TransferPeerPathResponse source;
  private TransferPeerPathResponse destination;
  private Float requestedAmount;
  private AmountInfo amountInfo;
  private Float fee;
  private FeeInfo feeInfo;
  private Float amount;
  private Float netAmount;
  private Float amountUSD;
  private Float serviceFee;
  private Boolean treatAsGrossAmount;
  private Float networkFee;
  private Long createdAt;
  private Long lastUpdated;
  private TransactionStatus status;
  private String txHash;
  private Long index;
  private TransactionSubStatus subStatus;
  private String sourceAddress;
  private String destinationAddress;
  private String destinationAddressDescription;
  private String destinationTag;
  private String[] signedBy;
  private String createdBy;
  private String rejectedBy;
  private String addressType;
  private String note;
  private String exchangeTxId;
  private String feeCurrency;
  private String operation;
  private AmlScreeningResult amlScreeningResult;
  private String customerRefId;
  private Long numOfConfirmations;
  private NetworkRecord[] networkRecords;
  private String replacedTxHash;
  private String externalTxId;
  private DestinationsResponse[] destinations;
  private BlockInfo blockInfo;
  private RewardsInfo rewardsInfo;
  private AuthorizationInfo authorizationInfo;
  private SignedMessage[] signedMessages;
  private Object extraParameters;
  private SystemMessageInfo systemMessages;
}
