package org.stellar.anchor.platform.observer.stellar;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unused")
public class MemoryStellarPaymentStreamerCursorStore implements StellarPaymentStreamerCursorStore {
  Map<String, String> mapTokens = new HashMap<>();
  String horizonCursor = null;
  String stellarRpcCursor = null;

  @Override
  public void saveHorizonCursor(String cursor) {
    this.horizonCursor = cursor;
  }

  @Override
  public String loadHorizonCursor() {
    return this.horizonCursor;
  }

  @Override
  public void saveStellarRpcCursor(String cursor) {
    this.stellarRpcCursor = cursor;
  }

  @Override
  public String loadStellarRpcCursor() {
    return this.stellarRpcCursor;
  }
}
