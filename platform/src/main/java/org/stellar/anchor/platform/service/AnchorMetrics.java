package org.stellar.anchor.platform.service;

public enum AnchorMetrics {
  SEP6_TRANSACTION_OBSERVED("sep6.transaction.observed"),
  SEP24_TRANSACTION_OBSERVED("sep24.transaction.observed"),
  SEP31_TRANSACTION_OBSERVED("sep31.transaction.observed"),
  SEP31_TRANSACTION_DB("sep31.transaction.db"),
  SEP24_TRANSACTION_DB("sep24.transaction.db"),
  SEP6_TRANSACTION_DB("sep6.transaction.db"),
  PAYMENT_RECEIVED("payment.received"),
  PAYMENT_SENT("payment.sent"),
  PAYMENT_OBSERVER_AMBIGUOUS_ROUTING("payment_observer.ambiguous_routing"),
  LOGGER("logger"),

  PLATFORM_RPC_TRANSACTION("platform_server.rpc_transaction"),

  // Metric Tags
  TAG_SEP31_STATUS_PENDING_STELLAR("pending_stellar"),
  TAG_SEP31_STATUS_PENDING_CUSTOMER("pending_customer_info_update"),
  TAG_SEP31_STATUS_PENDING_SENDER("pending_sender"),
  TAG_SEP31_STATUS_PENDING_RECEIVER("pending_receiver"),
  TAG_SEP31_STATUS_PENDING_EXTERNAL("pending_external"),
  TAG_SEP31_STATUS_COMPLETED("completed"),
  TAG_SEP31_STATUS_ERROR("error");

  private final String name;

  AnchorMetrics(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return this.name;
  }
}
