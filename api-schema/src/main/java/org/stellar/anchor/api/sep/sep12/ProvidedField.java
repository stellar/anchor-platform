package org.stellar.anchor.api.sep.sep12;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Refer to SEP-12.
 *
 * <p>https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0012.md#provided-fields
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProvidedField {
  Field.Type type;
  String description;
  List<String> choices;
  Boolean optional;
  Sep12Status status;
  String error;
}
