package db.migration;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.stellar.sdk.MuxedAccount;

public class V30__backfill_muxed_sep31_customer_id_owner extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    String findWinningReferences =
        "SELECT DISTINCT ON (customer_id) customer_id, creator ->> 'account' AS winning_account "
            + "FROM ("
            + "  SELECT receiver_id AS customer_id, creator::jsonb AS creator, client_name, started_at, id "
            + "    FROM sep31_transaction WHERE receiver_id IS NOT NULL "
            + "  UNION ALL "
            + "  SELECT sender_id AS customer_id, creator::jsonb AS creator, client_name, started_at, id "
            + "    FROM sep31_transaction WHERE sender_id IS NOT NULL "
            + ") refs "
            + "WHERE COALESCE(client_name, creator ->> 'account') IS NOT NULL "
            + "ORDER BY customer_id, started_at ASC NULLS LAST, id ASC";

    String repairMemo =
        "UPDATE sep31_customer_id_owner SET creator_memo = ? "
            + "WHERE customer_id = ? AND creator_memo IS NULL";

    try (PreparedStatement select = connection.prepareStatement(findWinningReferences);
        ResultSet rs = select.executeQuery();
        PreparedStatement update = connection.prepareStatement(repairMemo)) {
      while (rs.next()) {
        String customerId = rs.getString("customer_id");
        String winningAccount = rs.getString("winning_account");
        if (winningAccount == null || !winningAccount.startsWith("M")) continue;

        String muxedId;
        try {
          BigInteger id = new MuxedAccount(winningAccount).getMuxedId();
          if (id == null) continue;
          muxedId = id.toString();
        } catch (RuntimeException e) {
          continue;
        }

        update.setString(1, muxedId);
        update.setString(2, customerId);
        update.executeUpdate();
      }
    }
  }
}
