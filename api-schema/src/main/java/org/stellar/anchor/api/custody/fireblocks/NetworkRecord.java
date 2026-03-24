package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class NetworkRecord {
  private TransferPeerPathResponse source;
  private TransferPeerPathResponse destination;
  private String txHash;
  private Float networkFee;
  private String assetId;
  private Float netAmount;
  private NetworkStatus status;
  private String type;
  private String destinationAddress;
  private String sourceAddress;
}
