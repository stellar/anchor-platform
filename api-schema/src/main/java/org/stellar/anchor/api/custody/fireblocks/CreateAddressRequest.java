package org.stellar.anchor.api.custody.fireblocks;

import lombok.Data;

@Data
public class CreateAddressRequest {

  private String description;
  private String customerRefId;
}
