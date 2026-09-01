package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V33__rewrite_wallet_only_customer_id_owner_keys extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    String findWinningReferences =
        "SELECT DISTINCT ON (customer_id) customer_id, client_name, "
            + "       creator::jsonb ->> 'account' AS winning_account "
            + "FROM ("
            + "  SELECT receiver_id AS customer_id, creator, client_name, started_at, id "
            + "    FROM sep31_transaction WHERE receiver_id IS NOT NULL "
            + "  UNION ALL "
            + "  SELECT sender_id AS customer_id, creator, client_name, started_at, id "
            + "    FROM sep31_transaction WHERE sender_id IS NOT NULL "
            + ") refs "
            + "WHERE client_name IS NOT NULL "
            + "ORDER BY customer_id, started_at ASC NULLS LAST, id ASC";

    String rewriteKey =
        "UPDATE sep31_customer_id_owner SET creator_account = ? "
            + "WHERE customer_id = ? AND creator_account = ?";

    try (PreparedStatement select = connection.prepareStatement(findWinningReferences);
        ResultSet rs = select.executeQuery();
        PreparedStatement update = connection.prepareStatement(rewriteKey)) {
      while (rs.next()) {
        String customerId = rs.getString("customer_id");
        String clientName = rs.getString("client_name");
        String winningAccount = rs.getString("winning_account");
        if (winningAccount == null) continue;

        update.setString(1, clientName + ":" + winningAccount);
        update.setString(2, customerId);
        update.setString(3, clientName);
        update.executeUpdate();
      }
    }
  }
}
