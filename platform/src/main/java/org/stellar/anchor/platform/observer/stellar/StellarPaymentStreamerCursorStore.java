package org.stellar.anchor.platform.observer.stellar;

public interface StellarPaymentStreamerCursorStore {
  void saveHorizonCursor(String cursor);

  String loadHorizonCursor();

  void saveStellarRpcCursor(String cursor);

  String loadStellarRpcCursor();
}
