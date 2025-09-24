package org.stellar.anchor.api.platform;

public enum TransactionsOrderBy {
  CREATED_AT("started_at"),
  TRANSFER_RECEIVED_AT("transfer_received_at"),
  USER_ACTION_REQUIRED_BY("user_action_required_by");

  private final String tableName;

  TransactionsOrderBy(String tableName) {
    this.tableName = tableName;
  }

  public String getTableName() {
    return tableName;
  }
}
