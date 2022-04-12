package org.stellar.anchor.platform.paymentobserver;

import java.util.HashMap;
import java.util.Map;

public class MemoryPaymentStreamerCursorStore implements PaymentStreamerCursorStore {
  Map<String, String> mapTokens = new HashMap<>();

  @Override
  public void save(String account, String cursor) {
    mapTokens.put(account, cursor);
  }

  @Override
  public String load(String account) {
    return mapTokens.get(account);
  }
}
