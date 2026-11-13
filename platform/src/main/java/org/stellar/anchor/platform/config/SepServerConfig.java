package org.stellar.anchor.platform.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SepServerConfig {
  List<String> trustedProxies = new ArrayList<>();
}
