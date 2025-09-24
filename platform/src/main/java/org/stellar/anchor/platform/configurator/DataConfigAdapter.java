package org.stellar.anchor.platform.configurator;

import static org.stellar.anchor.util.Log.error;
import static org.stellar.anchor.util.StringHelper.isEmpty;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.stellar.anchor.api.exception.InvalidConfigException;
import org.stellar.anchor.platform.config.PropertySecretConfig;
import org.stellar.anchor.util.Log;

/**
 * This adapter manages Spring datasource and jpa configurations accoring to the config map. The
 * following lists Spring data configurations that we manage based on: <a
 * href="https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html">https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html</a>
 *
 * <p>Spring Datasource:
 *
 * <pre>
 * spring.datasource.driver-class-name:            The JDBC driver class name
 * spring.datasource.name:                         Datasource name to use if "generate-unique-name" is false. Defaults to "testdb" when using an embedded database, otherwise null.
 * spring.datasource.type:                         Fully qualified name of the connection pool implementation to use. By default, it is auto-detected from the classpath.
 * spring.datasource.url:                          JDBC URL of the database.
 * spring.datasource.username:                     Login username of the database.
 * spring.datasource.password:                     Login password of the database.
 * spring.datasource.generate-unique-name:         Whether to generate a random datasource name.
 * </pre>
 *
 * Spring Connection Pool Management
 *
 * <pre>
 * spring.datasource.hikari.connection-timeout = 20000 # maximum number of milliseconds that a client will wait for a connection
 * spring.datasource.hikari.minimum-idle= 10           # minimum number of idle connections maintained by HikariCP in a connection pool
 * spring.datasource.hikari.maximum-pool-size= 10      # maximum pool size
 * spring.datasource.hikari.idle-timeout=10000         # maximum idle time for connection
 * spring.datasource.hikari.max-lifetime= 1000         # maximum lifetime in milliseconds of a connection in the pool after it is closed.
 * spring.datasource.hikari.auto-commit =true          # default auto-commit behavior.
 * </pre>
 *
 * Spring JPA.
 *
 * <pre>
 * spring.jpa.database: 	                           Target database to operate on, auto-detected by default. Can be alternatively set using the "databasePlatform" property.
 * spring.jpa.database-platform:                       Name of the target database to operate on, auto-detected by default. Can be alternatively set using the "Database" enum.
 * spring.jpa.defer-datasource-initialization:         Whether to defer DataSource initialization until after any EntityManagerFactory beans have been created and initialized.: false
 * spring.jpa.generate-ddl:                            Whether to initialize the schema on startup. : false
 * spring.jpa.hibernate.ddl-auto:                      DDL mode. This is actually a shortcut for the "hibernate.hbm2ddl.auto" property. Defaults to "create-drop" when using an embedded database and no schema manager was detected. Otherwise, defaults to "none".
 * spring.jpa.hibernate.naming.implicit-strategy:      Fully qualified name of the implicit naming strategy.
 * spring.jpa.hibernate.naming.physical-strategy:      Fully qualified name of the physical naming strategy.
 * spring.jpa.hibernate.use-new-id-generator-mappings: Whether to use Hibernate's newer IdentifierGenerator for AUTO, TABLE and SEQUENCE. This is actually a shortcut for the "hibernate.id.new_generator_mappings" property. When not specified will default to "true".
 * spring.jpa.mapping-resources:                       Mapping resources (equivalent to "mapping-file" entries in persistence.xml).
 * spring.jpa.open-in-view:                            Register OpenEntityManagerInViewInterceptor. Binds a JPA EntityManager to the thread for the entire processing of the request. : true
 * spring.jpa.show-sql:                                Show SQL : false
 * </pre>
 */
public class DataConfigAdapter extends SpringConfigAdapter {
  public static final String DATABASE_H2 = "h2";
  public static final String DATABASE_SQLITE = "sqlite";
  public static final String DATABASE_AURORA = "aurora";
  public static final String DATABASE_POSTGRES = "postgres";
  final List<String> allFields =
      Arrays.asList(
          "spring.datasource.driver-class-name",
          "spring.datasource.name",
          "spring.datasource.type",
          "spring.datasource.url",
          "spring.datasource.username",
          "spring.datasource.password",
          "spring.datasource.generate-unique-name",
          "spring.datasource.hikari.connection-timeout ",
          "spring.datasource.hikari.minimum-idle",
          "spring.datasource.hikari.maximum-pool-size",
          "spring.datasource.hikari.idle-timeout",
          "spring.datasource.hikari.max-lifetime",
          "spring.datasource.hikari.auto-commit ",
          "spring.jpa.database",
          "spring.jpa.database-platform",
          "spring.jpa.defer-datasource-initialization",
          "spring.jpa.generate-ddl",
          "spring.jpa.hibernate.use-new-id-generator-mappings",
          "spring.jpa.open-in-view",
          "spring.jpa.show-sql",
          "spring.flyway.enabled");

  @Override
  void updateSpringEnv(ConfigMap config) throws InvalidConfigException {
    // Set our default value to start with
    setSpringDataDefaults();

    //
    // The following code block purposely kept long for easier understanding of how the Spring
    // database configurations are set.
    //
    String type = config.getString("data.type").toLowerCase();
    switch (type) {
      case DATABASE_H2:
        set("spring.datasource.driver-class-name", "org.h2.Driver");
        set("spring.datasource.embedded-database-connection", "H2");
        set("spring.datasource.name", "anchor-platform");
        set("spring.datasource.url", constructH2Url(config));
        set("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
        set("spring.jpa.properties.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        break;
      case DATABASE_SQLITE:
        set("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        set("spring.datasource.name", "anchor-platform");
        set("spring.jpa.database-platform", "org.stellar.anchor.platform.sqlite.SQLiteDialect");
        set("spring.jpa.generate-ddl", true);
        set("spring.jpa.hibernate.ddl-auto", "update");
        set("spring.datasource.url", constructSQLiteUrl(config));
        set(
            "spring.datasource.username",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME));
        set(
            "spring.datasource.password",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD));
        break;
      case DATABASE_AURORA:
        set("spring.datasource.driver-class-name", "org.postgresql.Driver");
        set("spring.datasource.name", "anchor-platform");
        set("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQL9Dialect");
        set(
            "spring.datasource.hikari.max-lifetime",
            840000); // 14 minutes because IAM tokens are valid for 15 min
        set("spring.datasource.url", constructPostgressUrl(config));
        set(
            "spring.datasource.username",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME));
        set(
            "spring.datasource.password",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD));
        configureFlyway(config);
        break;
      case DATABASE_POSTGRES:
        set("spring.datasource.driver-class-name", "org.postgresql.Driver");
        set("spring.datasource.name", "anchor-platform");
        set("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQL9Dialect");
        set("spring.datasource.url", constructPostgressUrl(config));
        set(
            "spring.datasource.username",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME));
        set(
            "spring.datasource.password",
            SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD));
        configureFlyway(config);
        break;
      default:
        Log.errorF("Invalid config[data.type]={}", type);
        throw new InvalidConfigException(String.format("Invalid config[data.type]=%s", type));
    }

    checkIfAllFieldsAreSet();
  }

  private void configureFlyway(ConfigMap config) {
    if (config.getString("data.flyway_enabled", "").equalsIgnoreCase("true")) {
      set("spring.flyway.enabled", true);
      set("spring.flyway.locations", "classpath:/db/migration");
      set(
          "spring.flyway.user",
          SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME));
      set(
          "spring.flyway.password",
          SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD));
      set("spring.flyway.url", constructPostgressUrl(config));
      set("spring.flyway.validate-migration-naming", true);
      boolean baselineOnMigrate =
          config.getString("data.flyway_baseline_on_migrate").equalsIgnoreCase("true");
      if (baselineOnMigrate) {
        set("spring.flyway.baseline-on-migrate", true);
        set("spring.flyway.baseline-version", "0");
      }
    } else {
      set("spring.jpa.generate-ddl", true);
      set("spring.jpa.hibernate.ddl-auto", "update");
    }
  }

  private String constructPostgressUrl(ConfigMap config) {
    return String.format(
        "jdbc:postgresql://%s/%s",
        config.getString("data.server"), config.getString("data.database"));
  }

  private String constructSQLiteUrl(ConfigMap config) {
    return String.format("jdbc:sqlite:%s.db", config.getString("data.database"));
  }

  private String constructH2Url(ConfigMap config) {
    return "jdbc:h2:mem:anchor-platform";
  }

  @Override
  void validate(ConfigMap config) throws InvalidConfigException {
    validateCredential(config);
    validateConnection(config);
  }

  void validateConnection(ConfigMap config) throws InvalidConfigException {
    String type = config.getString("data.type").toLowerCase();
    switch (type) {
      case DATABASE_H2:
      case DATABASE_SQLITE:
        // no need for connection validation.
        break;
      case DATABASE_AURORA:
      case DATABASE_POSTGRES:
        String url = constructPostgressUrl(config);
        try {
          Properties props = new Properties();
          props.setProperty(
              "user", SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME));
          props.setProperty(
              "password",
              SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD));
          DriverManager.getConnection(url, props);
        } catch (SQLException e) {
          error(e.getMessage());
          throw new InvalidConfigException(
              String.format("Unable to connect to database. url=%s", url));
        }
        break;
    }
  }

  void validateCredential(ConfigMap config) throws InvalidConfigException {
    String type = config.getString("data.type").toLowerCase();
    switch (type) {
      case DATABASE_H2:
        break;
      case DATABASE_SQLITE:
      case DATABASE_AURORA:
      case DATABASE_POSTGRES:
        if (isEmpty(SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_USERNAME))) {
          String msg =
              PropertySecretConfig.SECRET_DATA_USERNAME
                  + " is not set. Please provide the datasource username.";
          error(msg);
          throw new InvalidConfigException(msg);
        }
        if (isEmpty(SecretManager.getInstance().get(PropertySecretConfig.SECRET_DATA_PASSWORD))) {
          String msg =
              PropertySecretConfig.SECRET_DATA_PASSWORD
                  + " is not set. Please provide the datasource username.";
          error(msg);
          throw new InvalidConfigException(msg);
        }
        break;
    }
  }

  private void checkIfAllFieldsAreSet() {
    allFields.forEach(
        field -> {
          if (get(field) == null) {
            Log.debugF("{} is not set.", field);
          }
        });
  }

  void setSpringDataDefaults() {
    set("spring.datasource.generate-unique-name", "false");

    set("spring.datasource.hikari.connection-timeout ", 10000); // in ms
    set("spring.datasource.hikari.minimum-idle", 5);
    set("spring.datasource.hikari.maximum-pool-size", 10);
    set("spring.datasource.hikari.idle-timeout", 10000);
    set("spring.datasource.hikari.max-lifetime", 840000);
    set("spring.datasource.hikari.auto-commit ", true);

    set("spring.jpa.defer-datasource-initialization", false);
    set("spring.jpa.generate-ddl", false);
    set("spring.jpa.hibernate.use-new-id-generator-mappings", true);
    set("spring.jpa.open-in-view", true);
    set("spring.jpa.show-sql", false);

    set("spring.flyway.enabled", false);
  }
}
