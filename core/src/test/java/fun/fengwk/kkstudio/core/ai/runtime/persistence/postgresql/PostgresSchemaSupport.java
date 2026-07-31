package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

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
 * Shared support base for the PostgreSQL final-schema tests.
 *
 * <p>Starts one process-wide {@code postgres:17-alpine} Testcontainers instance and exposes the
 * JDBC URL plus Flyway helpers for the baseline and profile seeds. Keeping one stable container
 * allows schema tests and cached Spring contexts to share the same JDBC endpoint. Docker must be
 * available, otherwise class initialization fails rather than silently skipping.
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

  /** Counter shared across fixtures so per-test ids never clash across tests. */
  public static final AtomicLong FIXTURE_IDS = new AtomicLong(10_000_000L);

  /** Open a new JDBC connection to the running container. */
  public static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Migrate the baseline schema on the caller-owned connection. */
  public static void applyBaseline(Connection conn) {
    migrate(conn, "classpath:db/migration");
  }

  /** Migrate the baseline schema and the dev seed on the caller-owned connection. */
  public static void applyDevDatabase(Connection conn) {
    migrate(conn, "classpath:db/migration", "classpath:db/seed/dev");
  }

  /** Migrate the baseline schema and the e2e seed on the caller-owned connection. */
  public static void applyE2eDatabase(Connection conn) {
    migrate(conn, "classpath:db/migration", "classpath:db/seed/e2e");
  }

  private static void migrate(Connection conn, String... locations) {
    Flyway.configure()
        // suppressClose preserves the connection lifecycle for the caller.
        .dataSource(new SingleConnectionDataSource(conn, true))
        .locations(locations)
        // Profile seeds retain their V2 filenames; a baseline-only test may already have applied
        // V3.
        .outOfOrder(true)
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }

  /** Drop every object in the public schema, leaving an empty database for the next test. */
  public static void resetDatabase(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("drop schema if exists public cascade");
      st.execute("create schema public");
    }
  }

  /** Run one transaction and require PostgreSQL to reject it with the named constraint. */
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
    /** Execute SQL inside the transaction managed by the assertion helper. */
    void run() throws SQLException;
  }
}
