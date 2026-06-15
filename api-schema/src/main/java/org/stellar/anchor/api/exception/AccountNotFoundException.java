package org.stellar.anchor.api.exception;

public class AccountNotFoundException extends LedgerException {
  public AccountNotFoundException(String account) {
    super("Account not found: " + account);
  }
}
