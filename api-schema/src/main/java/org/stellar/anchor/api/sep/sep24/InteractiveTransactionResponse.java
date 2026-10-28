package org.stellar.anchor.api.sep.sep24;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.stellar.anchor.config.PII;

/**
 * The response to the GET /transactions/deposit/interactive and /transactions/withdraw/interactive
 * endpoints of SEP-24.
 */
@AllArgsConstructor
@Data
public class InteractiveTransactionResponse {
  String type;

  /** Carries a short-lived bearer token; excluded from logs by {@link PII}. */
  @PII String url;

  String id;
}
