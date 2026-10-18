package fun.fengwk.kkstudio.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全新安装基线契约：在测试自己拥有的临时 PostgreSQL 里经 Flyway（生产建库的同一条路径）对空库执行 {@code V1__schema.sql}， 再用 SQL
 * 探针（{@code fresh-install-probes.sql}）逐条验证约束对非法写入的拒绝。
 */
class FreshInstallSchemaContractTest {

  private static final String POSTGRES_IMAGE = "postgres:17-alpine";
  private static final String DATABASE = "kkstudio_schema_fresh_install";
  private static final String USERNAME = "kkstudio_schema";
  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final String PROBE_RESOURCE =
      "fun/fengwk/kkstudio/schema/fresh-install-probes.sql";
  private static final String PROBE_CONTAINER_PATH = "/tmp/fresh-install-probes.sql";
  private static final int PROBE_ASSERTION_COUNT = 197;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
          .withDatabaseName(DATABASE)
          .withUsername(USERNAME)
          .withPassword(USERNAME)
          .withCopyFileToContainer(
              MountableFile.forClasspathResource(PROBE_RESOURCE, 0644), PROBE_CONTAINER_PATH);

  @BeforeAll
  static void applyFreshInstallBaseline() {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATION_LOCATION)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  @Test
  void freshInstallAppliesExactlyOneVersionedBaseline() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select version, type, success from flyway_schema_history order by installed_rank")) {
      assertTrue(rows.next());
      assertEquals(
          "1|SQL|t",
          rows.getString(1) + "|" + rows.getString(2) + "|" + (rows.getBoolean(3) ? "t" : "f"));
      assertTrue(!rows.next(), "exactly one versioned baseline must be applied");
    }
  }

  @Test
  void postgresRejectsInvalidWritesThroughTheContractProbes() throws Exception {
    ExecResult result =
        POSTGRES.execInContainer(
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            USERNAME,
            "-d",
            DATABASE,
            "-f",
            PROBE_CONTAINER_PATH);
    assertEquals(
        0,
        result.getExitCode(),
        () -> "probe run failed:\n" + result.getStdout() + result.getStderr());
    Matcher matcher =
        Pattern.compile("PASS (\\d+) database contract assertions").matcher(result.getStdout());
    assertTrue(matcher.find(), () -> "probe run reported no PASS line:\n" + result.getStdout());
    // 精确断言计数：任何探针被静默跳过都会让这条失败。
    assertEquals(PROBE_ASSERTION_COUNT, Integer.parseInt(matcher.group(1)));
  }
}
