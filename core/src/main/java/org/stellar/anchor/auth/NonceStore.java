package org.stellar.anchor.auth;

public interface NonceStore {
  Nonce newInstance();

  Nonce findById(String id);

  Nonce save(Nonce nonce);

  void deleteExpiredNonces();
}
