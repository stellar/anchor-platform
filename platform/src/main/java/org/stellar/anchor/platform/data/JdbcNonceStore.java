package org.stellar.anchor.platform.data;

import lombok.RequiredArgsConstructor;
import org.stellar.anchor.auth.Nonce;
import org.stellar.anchor.auth.NonceStore;
import org.stellar.anchor.util.Log;

@RequiredArgsConstructor
public class JdbcNonceStore implements NonceStore {
  private final JdbcNonceRepo jdbcNonceRepo;

  public Nonce newInstance() {
    return new JdbcNonce();
  }

  public Nonce findById(String id) {
    return jdbcNonceRepo.findById(id).orElse(null);
  }

  public Nonce save(Nonce nonce) {
    return jdbcNonceRepo.save((JdbcNonce) nonce);
  }

  public void deleteExpiredNonces() {
    int expired = jdbcNonceRepo.countExpired();
    jdbcNonceRepo.deleteAllExpired();
    Log.debug("Deleted " + expired + " expired nonces");
  }
}
