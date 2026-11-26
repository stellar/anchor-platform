package org.stellar.anchor.platform.controller.sep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A typed view of a SEP-24 interactive request that is safe to write to the log.
 *
 * <p>The request body is an open-ended map: it carries the SEP-24 parameters and any SEP-9 customer
 * field the wallet chooses to send. An exclusion strategy is only asked about declared fields, so
 * the map itself cannot be scrubbed. The view holds an allowlist of non-sensitive values instead,
 * so a key that is not on the list can never reach the log, plus the names of every key received.
 */
class Sep24RequestLogView {
  private static final Set<String> LOGGABLE_KEYS =
      Set.of(
          "asset_code",
          "asset_issuer",
          "amount",
          "lang",
          "memo_type",
          "refund_memo_type",
          "quote_id",
          "source_asset",
          "destination_asset",
          "claimable_balance_supported");

  private final Map<String, String> values = new LinkedHashMap<>();
  private final List<String> receivedFields;

  private Sep24RequestLogView(Map<String, String> request) {
    Set<String> names = new TreeSet<>(request.keySet());
    for (String name : names) {
      String value = request.get(name);
      if (value != null && LOGGABLE_KEYS.contains(name)) {
        values.put(name, value);
      }
    }
    this.receivedFields = List.copyOf(names);
  }

  static Sep24RequestLogView from(Map<String, String> request) {
    return new Sep24RequestLogView(request == null ? Map.of() : request);
  }
}
