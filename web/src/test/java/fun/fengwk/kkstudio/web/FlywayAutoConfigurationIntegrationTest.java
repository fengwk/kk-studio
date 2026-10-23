package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.sql.SQLException;
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
    registry.add(
        "spring.flyway.locations", () -> "classpath:db/migration,classpath:db/seed/fixture");
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "false");
  }

  @Test
  void webContextAutoMigratesBaselineAndFixtureSeedThroughDataSource() throws Exception {
    // 启动真实 web 上下文走的是 Boot 自动配置，而不是直接操作 Flyway 测试基座。
    assertTrue(serverPort > 0);
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement()) {
      try (ResultSet history =
          st.executeQuery(
              "select count(*) from flyway_schema_history"
                  + " where success = true and ((version = '1')"
                  + " or (version is null and description = 'fixture seed'))")) {
        assertTrue(history.next());
        assertEquals(
            2L, history.getLong(1), "the canonical V1 baseline and fixture seed must be recorded");
      }
      try (ResultSet unexpectedMigration =
          st.executeQuery(
              "select count(*) from flyway_schema_history"
                  + " where version is not null"
                  + " and version <> '1'")) {
        assertTrue(unexpectedMigration.next());
        assertEquals(
            0L, unexpectedMigration.getLong(1), "only the canonical V1 migration is known");
      }
      // model_id 只对 MODEL 行必填（GROUP 行为 null），由 kind 形状约束而不是列级 NOT NULL 保证。
      try (ResultSet modelIdColumn =
          st.executeQuery(
              "select count(*) from information_schema.columns"
                  + " where table_schema = 'public'"
                  + " and table_name = 'agent_model'"
                  + " and column_name = 'model_id' and is_nullable = 'YES'")) {
        assertTrue(modelIdColumn.next());
        assertEquals(1L, modelIdColumn.getLong(1), "GROUP has no wire model_id");
      }
      try (ResultSet kindColumn =
          st.executeQuery(
              "select count(*) from information_schema.columns where table_schema='public'"
                  + " and table_name='agent_model' and column_name='kind' and is_nullable='NO'")) {
        assertTrue(kindColumn.next());
        assertEquals(1L, kindColumn.getLong(1));
      }
      // 列可空不等于放宽 MODEL：形状约束必须拒绝缺失 wire id，GROUP 则合法。
      SQLException invalidModel =
          assertThrows(
              SQLException.class,
              () ->
                  st.executeUpdate(
                      "insert into agent_model(name,kind,provider_name,config)"
                          + " values ('missing-wire','MODEL','stub','{}'::jsonb)"));
      assertEquals("23514", invalidModel.getSQLState());
      assertTrue(invalidModel.getMessage().contains("ck_agent_model_shape"));
      try {
        assertEquals(
            1,
            st.executeUpdate(
                "insert into agent_model(name,kind,group_config)"
                    + " values ('group-without-wire','GROUP','{}'::jsonb)"));
        try (ResultSet group =
            st.executeQuery(
                "select count(*) from agent_model where name='group-without-wire'"
                    + " and kind='GROUP' and model_id is null and provider_name is null and config is null")) {
          assertTrue(group.next());
          assertEquals(1L, group.getLong(1));
        }
      } finally {
        st.executeUpdate("delete from agent_model where name='group-without-wire'");
      }
      try (ResultSet seed =
          st.executeQuery(
              "select count(*) from agent_definition where name = 'default-assistant'")) {
        assertTrue(seed.next());
        assertEquals(1L, seed.getLong(1), "fixture seed must be visible through the data-source");
      }
      try (ResultSet chatSessionTable =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public'"
                  + " and table_name = 'chat_session'")) {
        assertTrue(chatSessionTable.next());
        assertEquals(
            1L,
            chatSessionTable.getLong(1),
            "the product Chat Session association must be part of V1");
      }
      try (ResultSet chatSessionForeignKeys =
          st.executeQuery(
              "select count(*) from information_schema.table_constraints"
                  + " where table_schema = 'public'"
                  + " and table_name = 'chat_session'"
                  + " and constraint_type = 'FOREIGN KEY'")) {
        assertTrue(chatSessionForeignKeys.next());
        assertEquals(
            2L,
            chatSessionForeignKeys.getLong(1),
            "the chat_session association keeps the Harness Session FK and Chat FK");
      }
      try (ResultSet businessTables =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public' and table_type = 'BASE TABLE'"
                  + " and table_name <> 'flyway_schema_history'")) {
        assertTrue(businessTables.next());
        assertEquals(
            45L,
            businessTables.getLong(1),
            "the canonical V1 baseline must expose exactly 45 business tables");
      }
      try (ResultSet clusterNode =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public' and table_name = 'cluster_node'")) {
        assertTrue(clusterNode.next());
        assertEquals(
            1L, clusterNode.getLong(1), "the restored IDE routing registry must be part of V1");
      }
    }
  }
}
