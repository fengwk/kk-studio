package fun.fengwk.kkstudio.platform.persistence.test;

import static fun.fengwk.kkstudio.platform.ai.runtime.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static fun.fengwk.kkstudio.platform.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyBaseline;
import static fun.fengwk.kkstudio.platform.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static fun.fengwk.kkstudio.platform.ai.runtime.persistence.postgresql.PostgresSchemaSupport.resetDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.platform.PlatformTestApplication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 非 Harness 业务集成测试的共享 Spring PostgreSQL Testcontainers 支持。
 *
 * <p>复用 {@link
 * fun.fengwk.kkstudio.platform.ai.runtime.persistence.postgresql.PostgresSchemaSupport} 上声明的单一
 * {@code postgres:17-alpine} 容器，并将权威的多数据源配置连接到该容器，使 {@code PlatformTestApplication} 把 {@code
 * spring.datasource.multi.primary} 绑定到 PostgreSQL 而不是 H2。
 *
 * <p>禁用自动 Flyway；本类在静态初始化阶段即执行 baseline 迁移，保证任何 Spring 上下文创建前 {@code system_setting} 等表与默认行已经
 * 存在（SystemSettingsSnapshot 在上下文启动时读取权威配置）。{@link #resetAndApplySchema} 在每个测试前再次重置并迁移，保持测试隔离。
 *
 * <p>Docker 必须可用——不可用时容器启动失败，本测试也失败而非静默跳过。
 */
@SpringBootTest(classes = PlatformTestApplication.class)
public abstract class PostgresSpringTestSupport {

  private static final String FLYWAY_DISABLED = "false";
  private static final String WORKERS_DISABLED = "false";

  static {
    // SystemSettingsSnapshot 作为共享启动快照，在上下文创建期读取一次 system_setting 默认行；任何 Spring 测试上下文加载前
    // 都必须先有 baseline 迁移，否则缺行上下文启动失败。@BeforeEach 仍负责每个测试前的 reset+baseline。
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void overrideMultiDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> FLYWAY_DISABLED);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
    registry.add("kk-studio.canvas.function.recovery-enabled", () -> WORKERS_DISABLED);
  }

  @BeforeEach
  final void resetAndApplySchema() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      migrateDatabase(conn);
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

  /** 重写以选择某个明确的 Flyway profile migration 位置。 */
  protected void migrateDatabase(Connection conn) {
    applyBaseline(conn);
  }
}
