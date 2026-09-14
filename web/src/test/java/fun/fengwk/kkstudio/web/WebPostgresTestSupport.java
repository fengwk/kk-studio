package fun.fengwk.kkstudio.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.platform.environment.operation.EnvironmentOperationDispatcher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * web 模块 SpringBoot 测试的共享 PostgreSQL 支持。
 *
 * <p>使用进程级单例容器（而非 {@code @Container}），从而跨测试类复用缓存 Spring 上下文时 JDBC URL 保持稳定。本类在静态初始化阶段 即执行 baseline
 * 与 dev seed 迁移，保证任何 Spring 上下文创建前 {@code system_setting} 等表与默认行已经存在 （SystemSettingsSnapshot
 * 在上下文启动时读取权威配置）。每个测试前 {@link #resetAndApplySchema} 再次重置并迁移，保持隔离。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
public abstract class WebPostgresTestSupport {

  private static final String FLYWAY_DISABLED = "false";
  private static final String WORKERS_DISABLED = "false";

  @MockitoBean private CanvasFunctionDispatcher canvasFunctionDispatcher;
  @MockitoBean protected EnvironmentOperationDispatcher environmentOperationDispatcher;

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_web_test");

  static {
    POSTGRES.start();
    // SystemSettingsSnapshot 作为共享启动快照，在上下文创建期读取一次 system_setting 默认行；任何 Spring 测试上下文加载前
    // 都必须先有 baseline + dev seed 迁移，否则缺行上下文启动失败。@BeforeEach 仍负责每个测试前的
    // reset+remigrate。
    try (Connection conn = newConnection()) {
      resetAndMigrateDevDatabase(conn);
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> FLYWAY_DISABLED);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
  }

  @BeforeEach
  final void resetAndApplySchema() throws Exception {
    try (Connection conn = newConnection()) {
      resetAndMigrateDevDatabase(conn);
      verifyDevSeed(conn);
    }
  }

  /**
   * 供 S3 场景测试在 Spring 上下文创建前把 {@code system_setting} 的 {@code storageMedia.s3Enabled} 置为 true， 使 S3
   * 服务族 flush 装配（而非返回 null）。
   */
  public static void enableS3InSystemSettings() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement statement =
            conn.prepareStatement(
                "update system_setting set config ="
                    + " jsonb_set(config, '{storageMedia,s3Enabled}', 'true'::jsonb)"
                    + " where id = 1")) {
      if (statement.executeUpdate() != 1) {
        throw new IllegalStateException("system_setting baseline row is missing");
      }
    }
  }

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void resetAndMigrateDevDatabase(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("drop schema public cascade");
      st.execute("create schema public");
    }
    migrateDevDatabase(conn);
  }

  private static void verifyDevSeed(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select count(*) from agent_definition where name = 'default-assistant'")) {
      if (!rs.next() || rs.getLong(1) != 1L) {
        throw new IllegalStateException(
            "dev seed did not insert agent_definition default-assistant");
      }
    }
  }

  private static void migrateDevDatabase(Connection conn) {
    Flyway.configure()
        // suppressClose 用于保留调用方管理的 JDBC 连接。
        .dataSource(new SingleConnectionDataSource(conn, true))
        .locations("classpath:db/migration", "classpath:db/seed/dev")
        .validateMigrationNaming(true)
        .load()
        .migrate();
  }
}
