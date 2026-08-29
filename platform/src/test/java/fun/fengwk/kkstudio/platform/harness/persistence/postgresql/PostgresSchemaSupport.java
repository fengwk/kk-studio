package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import org.flywaydb.core.Flyway;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PostgreSQL final-schema 测试的共享基座。
 *
 * <p>启动一个进程级的 {@code postgres:17-alpine} Testcontainers 实例，并暴露 JDBC URL 以及 baseline 和 profile seed
 * 的 Flyway 工具方法。保持单一稳定容器，使 schema 测试与已缓存的 Spring context 能共享同一 JDBC endpoint。Docker
 * 必须可用；否则类初始化失败而不是静默跳过。
 */
public abstract class PostgresSchemaSupport {

  @SuppressWarnings("resource")
  public static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  static {
    POSTGRES.start();
  }

  /** 跨 fixture 共享的计数器，避免每个测试的 id 互相冲突。 */
  public static final AtomicLong FIXTURE_IDS = new AtomicLong(10_000_000L);

  /** 打开到运行中容器的一条新 JDBC 连接。 */
  public static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** 在调用方持有的连接上执行 baseline schema 迁移。 */
  public static void applyBaseline(Connection conn) {
    migrate(conn, "classpath:db/migration");
  }

  /** 在调用方持有的连接上执行 baseline schema 与 dev seed 迁移。 */
  public static void applyDevDatabase(Connection conn) {
    migrate(conn, "classpath:db/migration", "classpath:db/seed/dev");
  }

  /** 在调用方持有的连接上执行 baseline schema 与 e2e seed 迁移。 */
  public static void applyE2eDatabase(Connection conn) {
    migrate(conn, "classpath:db/migration", "classpath:db/seed/e2e");
  }

  /** 在调用方持有的连接上执行 baseline、dev seed 与 Docker Canvas test 专用设置迁移。 */
  public static void applyCanvasTestDatabase(Connection conn) {
    migrate(
        conn, "classpath:db/migration", "classpath:db/seed/dev", "classpath:db/seed/canvas-test");
  }

  private static void migrate(Connection conn, String... locations) {
    Flyway.configure()
        // suppressClose 保留调用方持有的连接生命周期。
        .dataSource(new SingleConnectionDataSource(conn, true))
        .locations(locations)
        // Profile seed 使用 R__ repeatable migration，在 baseline 后执行。
        .outOfOrder(true)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  /** 删除 public schema 中的全部对象，为下一次测试保留空数据库。 */
  public static void resetDatabase(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("drop schema if exists public cascade");
      st.execute("create schema public");
    }
  }

  /** 跑一个事务并要求 PostgreSQL 以指定约束名拒绝它。 */
  public static void assertTransactionConstraintViolation(
      Connection conn, String expectedConstraint, SqlCommand command) throws SQLException {
    boolean previousAutoCommit = conn.getAutoCommit();
    conn.setAutoCommit(false);
    SQLException thrown = null;
    try {
      command.run();
      conn.commit();
    } catch (SQLException e) {
      thrown = e;
    } catch (RuntimeException | Error e) {
      conn.rollback();
      throw e;
    } finally {
      if (thrown != null) {
        conn.rollback();
      }
      if (previousAutoCommit) {
        conn.setAutoCommit(true);
      }
    }
    if (thrown == null) {
      throw new AssertionError("expected constraint " + expectedConstraint + " to reject the SQL");
    }
    verifyConstraintViolation(thrown, expectedConstraint);
  }

  private static void verifyConstraintViolation(SQLException thrown, String expectedConstraint) {
    String sqlState = thrown.getSQLState();
    if (sqlState == null || !sqlState.startsWith("23")) {
      throw new AssertionError(
          expectedConstraint
              + " must fail with constraint violation SQLState (class 23*), but got SQLState="
              + sqlState
              + " message="
              + thrown.getMessage());
    }
    String actualConstraint =
        thrown instanceof PSQLException postgresError
                && postgresError.getServerErrorMessage() != null
            ? postgresError.getServerErrorMessage().getConstraint()
            : null;
    if (!expectedConstraint.equals(actualConstraint)) {
      throw new AssertionError(
          "expected constraint "
              + expectedConstraint
              + " to reject the SQL, but PostgreSQL reported constraint="
              + actualConstraint
              + " message="
              + thrown.getMessage());
    }
  }

  @FunctionalInterface
  interface SqlCommand {
    /** 在断言辅助类所管理的事务中执行 SQL。 */
    void run() throws SQLException;
  }
}
