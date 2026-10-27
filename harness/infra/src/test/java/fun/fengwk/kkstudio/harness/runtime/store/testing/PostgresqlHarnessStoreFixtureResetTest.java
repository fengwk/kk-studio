package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** 验证 reset 从真实 V1 模板整体还原数据、schema 突变和迁移历史，且测试侧变更不会污染模板。 */
class PostgresqlHarnessStoreFixtureResetTest {

  private static final String PROBE_NAME = "fixture-reset-probe";
  private static final String PROBE_COLUMN = "reset_isolation_probe";

  @Test
  void consecutiveResetsRestoreBaselineWithoutMutatingTemplate() {
    PostgresqlHarnessStoreFixture.reset();
    JdbcTemplate jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    List<String> baselineHistory = migrationHistory(jdbc);
    assertTrue(
        baselineHistory.stream().anyMatch(entry -> entry.startsWith("1|")),
        "template must contain the real V1 migration history");

    jdbc.update(
        "insert into environment (id, name, registration_token_hash) values (?, ?, ?)",
        UUID.randomUUID(),
        PROBE_NAME,
        "a".repeat(64));
    jdbc.execute("alter table environment add column " + PROBE_COLUMN + " text");
    jdbc.update("delete from flyway_schema_history");

    assertEquals(1, probeRows(jdbc));
    assertEquals(1, probeColumns(jdbc));
    assertTrue(migrationHistory(jdbc).isEmpty());

    assertBaseline(jdbc, baselineHistory);
    // 连续 reset 仍得到同一 baseline：测试库上的 DML/DDL/迁移历史变更没有回写模板。
    assertBaseline(jdbc, baselineHistory);
    assertFalse(
        templateAllowsConnections(jdbc),
        "template must stay closed to application/test connections");
  }

  @Test
  void resetFailsExplicitlyWhenTestConnectionRemainsOpen() throws SQLException {
    PostgresqlHarnessStoreFixture.reset();
    try (Connection ignored = PostgresqlHarnessStoreFixture.dataSource().getConnection()) {
      IllegalStateException error =
          assertThrows(IllegalStateException.class, PostgresqlHarnessStoreFixture::reset);
      SQLException databaseFailure =
          assertInstanceOf(
              SQLException.class, error.getCause(), "reset must keep the database cause");
      assertEquals(
          "55006",
          databaseFailure.getSQLState(),
          "PostgreSQL must reject DROP DATABASE while a test session remains");
    }
    // 显式关闭后 reset 可以继续，证明失败没有破坏后续隔离重建。
    PostgresqlHarnessStoreFixture.reset();
  }

  private static void assertBaseline(JdbcTemplate jdbc, List<String> baselineHistory) {
    PostgresqlHarnessStoreFixture.reset();
    assertEquals(
        new UUID(0L, 1L),
        PostgresqlHarnessStoreFixture.idGenerator().get(),
        "reset must restart the deterministic UUID sequence");
    assertEquals(0, probeRows(jdbc));
    assertEquals(0, probeColumns(jdbc));
    assertEquals(baselineHistory, migrationHistory(jdbc));
  }

  private static int probeRows(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "select count(*) from environment where name = ?", Integer.class, PROBE_NAME);
  }

  private static int probeColumns(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        """
        select count(*)
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'environment'
          and column_name = ?
        """,
        Integer.class,
        PROBE_COLUMN);
  }

  private static List<String> migrationHistory(JdbcTemplate jdbc) {
    return jdbc.query(
        """
        select version, description, checksum, success
        from flyway_schema_history
        order by installed_rank
        """,
        (resultSet, rowNumber) ->
            resultSet.getString("version")
                + "|"
                + resultSet.getString("description")
                + "|"
                + resultSet.getLong("checksum")
                + "|"
                + resultSet.getBoolean("success"));
  }

  private static boolean templateAllowsConnections(JdbcTemplate jdbc) {
    Boolean allowed =
        jdbc.queryForObject(
            "select datallowconn from pg_database where datname = ?",
            Boolean.class,
            PostgresqlHarnessStoreFixture.templateDatabaseName());
    return Boolean.TRUE.equals(allowed);
  }
}
