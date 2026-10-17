package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 升级内核的真实 PostgreSQL 17 测试基座：进程级单例容器，每个测试用例取自己的独立数据库。
 *
 * <p>源库只应用与 Git blob/SHA-256 逐字节一致的冻结 V1 DDL（放测试资源目录，不在 {@code db/migration} 下），目标库只应用当前 classpath
 * 的唯一 V1 迁移，因此目标天然处于 pristine 形态，绝不用带 dev seed 的共享 helper 伪装。
 *
 * <p>包内可见（package-private）：它只是升级测试的物理基座，不是生产代码。
 */
final class UpgradePostgresTestSupport {

  private static final String MIGRATION_LOCATIONS = "classpath:db/migration";

  private static final String SOURCE_LOCATIONS_ROOT =
      "classpath:fun/fengwk/kkstudio/web/upgrade/source/";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_upgrade_test");

  static {
    POSTGRES.start();
  }

  private UpgradePostgresTestSupport() {}

  /** DEV / MAIN 冻结基线源库：只应用对应冻结 DDL，历史表由真实 Flyway 11.14.1 写出真实校验和。 */
  static DataSource sourceDatabase(String profile) {
    return isolatedDatabase(SOURCE_LOCATIONS_ROOT + profile);
  }

  /** 当前 V1 的 pristine 目标库：46 张业务表 + epoch 0 目录 seed，没有任何业务行或 dev seed。 */
  static DataSource pristineTargetDatabase() {
    return isolatedDatabase(MIGRATION_LOCATIONS);
  }

  /** 全新空库：完全不应用任何迁移，供显式 prepare 从零初始化并验证空库门禁。 */
  static DataSource blankDatabase() {
    return isolatedDatabase(null);
  }

  /** 新建一个全新 UUID 库并只应用给定迁移位置（null 表示完全不迁移）；同一个物理容器上不同库互不可见。 */
  private static DataSource isolatedDatabase(String locations) {
    String database = "kk_studio_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection =
            DriverManager.getConnection(
                databaseUrl(POSTGRES.getDatabaseName()),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("create database " + database);
    } catch (SQLException error) {
      throw new IllegalStateException("cannot create an isolated upgrade test database", error);
    }
    SimpleDriverDataSource dataSource =
        new SimpleDriverDataSource(
            new Driver(), databaseUrl(database), POSTGRES.getUsername(), POSTGRES.getPassword());
    if (locations != null) {
      Flyway.configure()
          .dataSource(dataSource)
          .locations(locations)
          .validateMigrationNaming(true)
          .load()
          .migrate();
    }
    return dataSource;
  }

  /** 单列整数结果，供断言目标行数与 epoch。 */
  static long longValue(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new IllegalStateException("expected a single row: " + sql);
      }
      return rows.getLong(1);
    }
  }

  /** 单列文本结果，供断言被改写的标量值。 */
  static String text(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new IllegalStateException("expected a single row: " + sql);
      }
      return rows.getString(1);
    }
  }

  static long count(DataSource dataSource, String table) throws SQLException {
    return longValue(dataSource, "select count(*) from " + table);
  }

  /** 真实 flyway_schema_history 中唯一 V1 的校验和，用于验证冻结常量。 */
  static long v1Checksum(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select checksum from flyway_schema_history where version = '1'")) {
      if (!rows.next()) {
        throw new IllegalStateException("the baseline migration history row is missing");
      }
      return rows.getLong(1);
    }
  }

  /** 单列 bytea 结果，供凭据密文的真实解密断言。 */
  static byte[] bytes(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw new IllegalStateException("expected a single row: " + sql);
      }
      return rows.getBytes(1);
    }
  }

  /** 目标 pristine 形态：全部业务表为空（settings 除外）、目录只有 epoch 0、cluster_node 初始空。 */
  static void assertPristine(DataSource target) throws SQLException {
    for (String table : LegacySchemaProfile.SHARED_TABLES) {
      if (!"system_setting".equals(table)) {
        assertEquals(0L, count(target, table), table);
      }
    }
    for (String table : LegacySchemaProfile.TARGET_ONLY_TABLES) {
      if (!"plugin_catalog_revision".equals(table) && !"plugin_catalog_state".equals(table)) {
        assertEquals(0L, count(target, table), table);
      }
    }
    assertEquals(1L, count(target, "system_setting"));
    assertEquals(0L, count(target, LegacySchemaProfile.CLUSTER_NODE));
    assertEquals(1L, count(target, "plugin_catalog_state"));
    assertEquals(0L, longValue(target, "select epoch from plugin_catalog_state where id = 1"));
    assertEquals(1L, count(target, "plugin_catalog_revision"));
    assertEquals(
        1L,
        count(
            target,
            "plugin_catalog_revision where epoch = 0"
                + " and manifest_json = cast('{\"plugins\":{},\"catalogFingerprint\":null}' as jsonb)"));
  }

  /** 用容器暴露的真实 JDBC URL 替换库名，不自己拼主机与端口。 */
  private static String databaseUrl(String database) {
    String jdbcUrl = POSTGRES.getJdbcUrl();
    int prefix = "jdbc:postgresql://".length();
    int pathStart = jdbcUrl.indexOf('/', prefix);
    int queryStart = jdbcUrl.indexOf('?', pathStart);
    String suffix = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
    return jdbcUrl.substring(0, pathStart + 1) + database + suffix;
  }
}
