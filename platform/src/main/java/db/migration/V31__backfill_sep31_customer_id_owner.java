package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V31__backfill_sep31_customer_id_owner extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    String backfill =
        "INSERT INTO sep31_customer_id_owner (customer_id, creator_account, creator_memo) "
            + "SELECT DISTINCT ON (customer_id) "
            + "       customer_id, "
            + "       COALESCE(client_name, creator::jsonb ->> 'account') AS creator_account, "
            + "       creator::jsonb ->> 'memo' AS creator_memo "
            + "  FROM ("
            + "    SELECT receiver_id AS customer_id, creator, client_name, started_at, id "
            + "      FROM sep31_transaction "
            + "     WHERE receiver_id IS NOT NULL "
            + "    UNION ALL "
            + "    SELECT sender_id AS customer_id, creator, client_name, started_at, id "
            + "      FROM sep31_transaction "
            + "     WHERE sender_id IS NOT NULL "
            + "  ) refs "
            + " WHERE COALESCE(client_name, creator::jsonb ->> 'account') IS NOT NULL "
            + " ORDER BY customer_id, started_at ASC NULLS LAST, id ASC "
            + "ON CONFLICT (customer_id) DO NOTHING";

    try (PreparedStatement insert = connection.prepareStatement(backfill)) {
      insert.executeUpdate();
    }

    new V30__backfill_muxed_sep31_customer_id_owner().migrate(context);
  }
}
