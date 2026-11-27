package org.stellar.anchor.platform.utils;

import org.stellar.anchor.platform.data.JdbcSep24Transaction;
import org.stellar.anchor.platform.data.JdbcSep6Transaction;
import org.stellar.anchor.platform.data.JdbcSepTransaction;

/**
 * A one-line description of a SEP transaction that is safe to write to the log.
 *
 * <p>The transaction entity carries customer data: the SEP-6 {@code fields} the user submits, the
 * memo and the client IP address. {@code Log.infoF} and {@code Log.debugF} hand their arguments to
 * SLF4J, which never consults the {@code @PII} exclusion strategy, so an entity must not be passed
 * to them. This summary holds identifiers only, so a field added to the entity later can never
 * reach the log through it.
 */
public final class SepTransactionLogSummary {
  private SepTransactionLogSummary() {}

  /**
   * @param txn the transaction, or {@code null} when it was not found.
   * @return {@code id}, {@code protocol}, {@code kind} (SEP-6 and SEP-24 only) and {@code status}.
   */
  public static String of(JdbcSepTransaction txn) {
    if (txn == null) {
      return "null";
    }
    String kind = null;
    if (txn instanceof JdbcSep6Transaction) {
      kind = ((JdbcSep6Transaction) txn).getKind();
    } else if (txn instanceof JdbcSep24Transaction) {
      kind = ((JdbcSep24Transaction) txn).getKind();
    }
    StringBuilder summary =
        new StringBuilder("id=")
            .append(txn.getId())
            .append(", protocol=")
            .append(txn.getProtocol());
    if (kind != null) {
      summary.append(", kind=").append(kind);
    }
    return summary.append(", status=").append(txn.getStatus()).toString();
  }
}
