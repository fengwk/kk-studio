package fun.fengwk.kkstudio.core.persistence.test;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.POSTGRES;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.applyBaseline;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.resetDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.core.CoreTestApplication;

import java.sql.Connection;

/**
 * 非 Harness 业务集成测试的共享 Spring PostgreSQL Testcontainers 支持。
 *
 * <p>复用 {@link fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport}
 * 上声明的单一 {@code postgres:17-alpine} 容器，并将权威的多数据源配置连接到该容器，使 {@code CoreTestApplication} 把 {@code
 * spring.datasource.multi.primary} 绑定到 PostgreSQL 而不是 H2。
 *
 * <p>禁用自动 Flyway，使每个测试都能重置 public，并在已缓存 context 就绪后显式执行 baseline 迁移。
 *
 * <p>Docker 必须可用——不可用时容器启动失败，本测试也失败而非静默跳过。
 */
@SpringBootTest(classes = CoreTestApplication.class)
public abstract class PostgresSpringTestSupport {

  private static final String FLYWAY_DISABLED = "false";
  private static final String WORKERS_DISABLED = "false";

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

  /** 重写以选择某个明确的 Flyway profile migration 位置。 */
  protected void migrateDatabase(Connection conn) {
    applyBaseline(conn);
  }
}
