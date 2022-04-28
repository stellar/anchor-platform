package org.stellar.anchor.api.shared;

import java.util.List;
import lombok.Data;

@Data
public class Field {
  String type;
  String description;
  List<String> choices;
  Boolean optional;
}
