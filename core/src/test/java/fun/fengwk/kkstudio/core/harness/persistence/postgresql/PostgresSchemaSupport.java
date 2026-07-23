package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import org.postgresql.util.PSQLException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 * <p>Spins up a {@code postgres:17-alpine} Testcontainers instance and exposes the JDBC URL plus a
 * helper that applies the authoritative {@code schema-postgresql.sql}. Tests run outside any Spring
 * {@code ApplicationContext}; Docker must be available, otherwise tests fail rather than silently
 * skip.
 */
@Testcontainers
abstract class PostgresSchemaSupport {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  /** Counter shared across fixtures so per-test ids never clash across tests. */
  static final AtomicLong FIXTURE_IDS = new AtomicLong(10_000_000L);

  /** Open a new JDBC connection to the running container. */
  static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Apply the authoritative PostgreSQL schema. */
  static void applySchema(Connection conn) {
    ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-postgresql.sql"));
  }

  /** Apply a SQL classpath resource. */
  static void applyScript(Connection conn, String classpathLocation) {
    ScriptUtils.executeSqlScript(conn, new ClassPathResource(classpathLocation));
  }

  /** Drop every object in the public schema, leaving an empty database for the next test. */
  static void resetDatabase(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("drop schema if exists public cascade");
      st.execute("create schema public");
    }
  }

  /** Run one transaction and require PostgreSQL to reject it with the named constraint. */
  static void assertTransactionConstraintViolation(
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
