package org.stellar.anchor.platform.configurator;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * V29 shipped in 4.6.0 with an inline backfill INSERT, then was reverted to its originally released
 * form to fix a Flyway checksum mismatch. Databases that already booted 4.6.0 successfully recorded
 * the with-backfill checksum; databases that never got that far still have the original checksum.
 * Since neither history can be edited by hand, this normalizes the former to the latter before
 * Flyway validates, so both histories are accepted regardless of which checksum a given database
 * recorded.
 */
public class FlywayChecksumCompatibilityCallback extends BaseCallback {

  private static final int ORIGINAL_V29_CHECKSUM = 1190516276;
  private static final int V4_6_0_BACKFILL_V29_CHECKSUM = 1803170936;

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_VALIDATE;
  }

  @Override
  public void handle(Event event, Context context) {
    Connection connection = context.getConnection();
    try {
      if (!schemaHistoryTableExists(connection)) {
        return; // fresh database; nothing has been applied yet, so nothing to normalize.
      }
      String sql =
          "UPDATE flyway_schema_history SET checksum = ? "
              + "WHERE version = '29' AND success = true AND checksum = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setInt(1, ORIGINAL_V29_CHECKSUM);
        statement.setInt(2, V4_6_0_BACKFILL_V29_CHECKSUM);
        statement.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to normalize V29 checksum in flyway_schema_history", e);
    }
  }

  private boolean schemaHistoryTableExists(Connection connection) throws SQLException {
    DatabaseMetaData metaData = connection.getMetaData();
    try (ResultSet tables =
        metaData.getTables(
            null, connection.getSchema(), "flyway_schema_history", new String[] {"TABLE"})) {
      return tables.next();
    }
  }
}
