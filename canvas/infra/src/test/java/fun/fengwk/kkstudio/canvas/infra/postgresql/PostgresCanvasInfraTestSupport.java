package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.CanvasStore.NodeRecord;
import fun.fengwk.kkstudio.canvas.CanvasTransform;
import fun.fengwk.kkstudio.canvas.infra.CanvasInfraTestApplication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Canvas Infra 真实 PostgreSQL 测试基座。
 *
 * <p>沿用仓库既有模式：进程级 {@code postgres:17-alpine} Testcontainer、权威 schema 模块中的 Flyway baseline，以及每个测试前
 * drop/recreate public schema。Docker 不可用时测试直接失败，不以 mock 或跳过掩盖适配器问题。
 */
@SpringBootTest(classes = CanvasInfraTestApplication.class)
public abstract class PostgresCanvasInfraTestSupport {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_canvas_infra")
          .withUsername("kk_studio")
          .withPassword("kk_studio");

  static {
    POSTGRES.start();
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateDatabase(connection);
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected TransactionTemplate transactions;
  @Autowired protected CanvasStore canvasStore;

  @DynamicPropertySource
  static void configurePostgres(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "false");
  }

  @BeforeEach
  final void resetSchema() throws SQLException {
    try (Connection connection = newConnection()) {
      resetDatabase(connection);
      migrateDatabase(connection);
    }
  }

  protected UUID addDocument() {
    UUID canvasId = UUID.randomUUID();
    canvasStore.addDocument(canvasId, "canvas-" + canvasId);
    return canvasId;
  }

  protected NodeRecord addNode(UUID canvasId, boolean function) {
    UUID nodeId = UUID.randomUUID();
    NodeRecord node =
        new NodeRecord(
            nodeId,
            canvasId,
            "node-" + nodeId,
            new CanvasTransform(10, 20, 300, 200),
            null,
            function ? "test-model" : null,
            function ? "{\"prompt\":{\"segments\":[]},\"parameters\":{}}" : null);
    canvasStore.addNode(node);
    return node;
  }

  protected static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void resetDatabase(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
    }
  }

  private static void migrateDatabase(Connection connection) {
    Flyway.configure()
        .dataSource(new SingleConnectionDataSource(connection, true))
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
