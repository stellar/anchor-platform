package db.migration;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.stellar.sdk.MuxedAccount;

public class V33__rewrite_wallet_only_customer_id_owner_keys extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    try (PreparedStatement widenColumn =
        connection.prepareStatement(
            "ALTER TABLE sep31_customer_id_owner ALTER COLUMN creator_account TYPE VARCHAR(512)")) {
      widenColumn.executeUpdate();
    }

    String findWinningReferences =
        "SELECT DISTINCT ON (customer_id) customer_id, client_name, "
            + "       creator::jsonb ->> 'account' AS winning_account, "
            + "       creator::jsonb ->> 'memo' AS winning_memo "
            + "FROM ("
            + "  SELECT receiver_id AS customer_id, creator, client_name, started_at, id, status "
            + "    FROM sep31_transaction WHERE receiver_id IS NOT NULL "
            + "  UNION ALL "
            + "  SELECT sender_id AS customer_id, creator, client_name, started_at, id, status "
            + "    FROM sep31_transaction WHERE sender_id IS NOT NULL "
            + ") refs "
            + "WHERE client_name IS NOT NULL "
            + "  AND status <> 'pending_receiver' "
            + "ORDER BY customer_id, started_at ASC NULLS LAST, id ASC";

    String rewriteKey =
        "UPDATE sep31_customer_id_owner SET creator_account = ?, creator_memo = ? "
            + "WHERE customer_id = ? AND creator_account = ?";

    try (PreparedStatement select = connection.prepareStatement(findWinningReferences);
        ResultSet rs = select.executeQuery();
        PreparedStatement update = connection.prepareStatement(rewriteKey)) {
      while (rs.next()) {
        String customerId = rs.getString("customer_id");
        String clientName = rs.getString("client_name");
        String winningAccount = rs.getString("winning_account");
        if (winningAccount == null) continue;

        String winningMemo;
        if (winningAccount.startsWith("M")) {
          BigInteger muxedId;
          try {
            muxedId = new MuxedAccount(winningAccount).getMuxedId();
          } catch (RuntimeException e) {
            muxedId = null;
          }
          winningMemo = muxedId != null ? muxedId.toString() : null;
        } else {
          winningMemo = rs.getString("winning_memo");
        }

        update.setString(1, clientName + ":" + winningAccount);
        update.setString(2, winningMemo);
        update.setString(3, customerId);
        update.setString(4, clientName);
        update.executeUpdate();
      }
    }
  }
}
