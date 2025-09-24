package org.stellar.anchor.api.shared;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeeDescription {
  String name;
  String description;
  String amount;

  public FeeDescription(String name, String amount) {
    this.name = name;
    this.amount = amount;
  }
}
