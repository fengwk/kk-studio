package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 验证 Spring Boot 4 的 Flyway 自动配置在启动全新 PostgreSQL 数据库时自动执行迁移。
 *
 * <p>PostgreSQL 容器仅启动空库，严禁测试前进行任何手工 Flyway 迁移、SQL 引导或容器初始化脚本。 真实 {@link SpringBootTest} 必须依赖 Spring
 * Boot 的 Flyway auto-configuration 成功就绪， 并确保依赖数据库表的 bean（如装配期读取 {@code system_setting} 的组件）能够正常启动。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayAutoConfigurationIntegrationTest {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_flyway_test");

  static {
    POSTGRES.start();
  }

  @Autowired private DataSource dataSource;
  @LocalServerPort private int serverPort;

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed/dev");
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "false");
  }

  @Test
  void webContextAutoMigratesBaselineAndDevSeedThroughDataSource() throws Exception {
    // 启动真实 web 上下文走的是 Boot 自动配置，而不是直接操作 Flyway 测试基座。
    assertTrue(serverPort > 0);
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      try (ResultSet history =
          st.executeQuery(
              "select count(*) from flyway_schema_history"
                  + " where success = true and (version = '1' or (version is null and description = 'dev seed'))")) {
        assertTrue(history.next());
        assertEquals(2L, history.getLong(1), "baseline and dev seed must be recorded");
      }
      try (ResultSet noV2Plus =
          st.executeQuery(
              "select count(*) from flyway_schema_history where version is not null and version != '1'")) {
        assertTrue(noV2Plus.next());
        assertEquals(0L, noV2Plus.getLong(1), "no versioned migration beyond V1 must exist");
      }
      try (ResultSet seed =
          st.executeQuery(
              "select count(*) from agent_definition where name = 'default-assistant'")) {
        assertTrue(seed.next());
        assertEquals(1L, seed.getLong(1), "dev seed must be visible through the data-source");
      }
      try (ResultSet ownerSessionTables =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public'"
                  + " and table_name in ('chat_session', 'canvas_session')")) {
        assertTrue(ownerSessionTables.next());
        assertEquals(
            2L,
            ownerSessionTables.getLong(1),
            "Chat/Canvas owner-to-Session tables must be part of V1");
      }
      try (ResultSet ownerSessionForeignKeys =
          st.executeQuery(
              "select count(*) from information_schema.table_constraints"
                  + " where table_schema = 'public'"
                  + " and table_name in ('chat_session', 'canvas_session')"
                  + " and constraint_type = 'FOREIGN KEY'")) {
        assertTrue(ownerSessionForeignKeys.next());
        assertEquals(
            4L,
            ownerSessionForeignKeys.getLong(1),
            "owner-to-Session relations must retain real owner and Harness Session FKs");
      }
    }
  }
}
