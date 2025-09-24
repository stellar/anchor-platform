package org.stellar.anchor.platform.config;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MoreInfoUrlConfig {
  String baseUrl;
  long jwtExpiration;
  List<String> txnFields;
}
