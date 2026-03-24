package org.stellar.anchor.api.custody.fireblocks;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreateAddressResponse {

  private String address;
  private String legacyAddress;
  private String enterpriseAddress;
  private String tag;
  private Integer bip44AddressIndex;
}
