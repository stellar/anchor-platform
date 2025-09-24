package org.stellar.anchor.util;

/**
 * This class contains constants for metrics names and tag names.
 *
 * <p>Please note the constants prefixed with TV_ are used for tag values.
 */
public class MetricConstants {
  // SEP-1 metrics
  public static final String SEP1_TOML_ACCESSED = "sep1.toml_accessed";

  // SEP-6 metrics
  public static final String SEP6_TRANSACTION_REQUESTED = "sep6.transaction.requested";
  public static final String SEP6_TRANSACTION_CREATED = "sep6.transaction.created";
  public static final String SEP6_TRANSACTION_QUERIED = "sep6.transaction.queried";
  public static final String TV_SEP6_WITHDRAWAL = "withdrawal";
  public static final String TV_SEP6_WITHDRAWAL_EXCHANGE = "withdrawal_exchange";
  public static final String TV_SEP6_DEPOSIT = "deposit";
  public static final String TV_SEP6_DEPOSIT_EXCHANGE = "deposit_exchange";

  // SEP-10 metrics
  public static final String SEP10_CHALLENGE_CREATED = "sep10.transaction";
  public static final String SEP10_CHALLENGE_VALIDATED = "sep10.challenge.signed";

  // SEP-12 metrics
  public static final String SEP12_CUSTOMER = "sep12.customer";
  public static final String TV_SEP12_GET_CUSTOMER = "get";
  public static final String TV_SEP12_PUT_CUSTOMER = "put";
  public static final String TV_SEP12_DELETE_CUSTOMER = "delete";

  // SEP-24 metrics
  public static final String SEP24_TRANSACTION_REQUESTED = "sep24.transaction.requested";
  public static final String SEP24_TRANSACTION_CREATED = "sep24.transaction.created";
  public static final String SEP24_TRANSACTION_QUERIED = "sep24.transaction.queried";
  public static final String TV_SEP24_WITHDRAWAL = "withdrawal";
  public static final String TV_SEP24_DEPOSIT = "deposit";

  // SEP-31 metrics
  public static final String SEP31_TRANSACTION_REQUESTED = "sep31.transaction.requested";
  public static final String SEP31_TRANSACTION_CREATED = "sep31.transaction.created";
  public static final String SEP31_TRANSACTION_PATCHED = "sep31.transaction.patched";

  // SEP-38 metrics
  public static final String SEP38_PRICE_QUERIED = "sep38.price.queried";
  public static final String SEP38_QUOTE_CREATED = "sep38.quote.created";

  // payment observer metrics
  public static final String PAYMENT_OBSERVER_LATEST_BLOCK_READ =
      "payment_observer.latest_block_read";
  public static final String PAYMENT_OBSERVER_LATEST_BLOCK_PROCESSED =
      "payment_observer.latest_block_processed";

  // event processor metrics
  public static final String EVENT_RECEIVED = "event_processor.event_received";
  public static final String EVENT_PROCESSED = "event_processor.event_processed";
  public static final String TV_BUSINESS_SERVER_CALLBACK = "business_server_callback_api";
  public static final String TV_STATUS_CALLBACK = "status_callback";
  public static final String TV_UNKNOWN = "unknown";

  // platform server metrics
  public static final String PLATFORM_PATCH_TRANSACTION = "platform_server.patch_transaction";
  public static final String PLATFORM_FIND_TRANSACTION = "platform_server.get_transaction";
  public static final String PLATFORM_FIND_TRANSACTIONS = "platform_server.get_transactions";
  public static final String TV_SEP6 = "sep6";
  public static final String TV_SEP24 = "sep24";
  public static final String TV_SEP31 = "sep31";

  // Logger metrics
  public static final String LOGGER = "logger";

  // Tag names
  public static final String SEP = "SEP";
  public static final String QUEUE = "queue";
  public static final String STATUS = "status";
  public static final String TYPE = "type";
}
