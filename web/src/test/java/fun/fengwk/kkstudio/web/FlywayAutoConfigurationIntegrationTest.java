package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 验证 Spring Boot 的 Flyway 自动配置会迁移应用的多数据源。 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlywayAutoConfigurationIntegrationTest {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_flyway_test");

  static {
    POSTGRES.start();
    // 上下文创建期装配 bean 会读取 system_setting 默认行：按测试注入的 Flyway 位置（V1+dev seed）预先迁移，
    // 保证缺行不导致启动失败；Boot 自动迁移随后对已应用版本是幂等 no-op。
    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      Flyway.configure()
          .dataSource(new SingleConnectionDataSource(conn, true))
          .locations("classpath:db/migration", "classpath:db/seed/dev")
          .validateMigrationNaming(true)
          .load()
          .migrate();
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @Autowired private DataSource dataSource;
  @LocalServerPort private int serverPort;

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed/dev");
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "false");
  }

  @Test
  void webContextAutoMigratesBaselineAndDevSeedThroughMultiDataSource() throws Exception {
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
        assertEquals(1L, seed.getLong(1), "dev seed must be visible through the multi-data-source");
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
      try (ResultSet legacyThreadRelations =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public'"
                  + " and table_name in ('chat_thread', 'canvas_thread')")) {
        assertTrue(legacyThreadRelations.next());
        assertEquals(
            0L,
            legacyThreadRelations.getLong(1),
            "clean-slate V1 must not retain legacy owner-to-Thread relation tables");
      }
    }
  }
}
