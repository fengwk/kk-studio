package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
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

  @Test
  void dataSourceConfiguresHikariAndPostgreSqlNetworkTimeouts() {
    // 验证经过 Spring Boot 自动配置与 application.yml 绑定后，注入的真实 DataSource 为 HikariDataSource，
    // 并且已按预期配置了连接池获取等待与 PostgreSQL JDBC 底层网络超时属性：
    // 1. Hikari connection-timeout 为 5000ms（连接池借出等待上限）；
    // 2. driver dataSourceProperties 中的 connectTimeout 为 "5" 秒（TCP 建立连接超时）；
    // 3. driver dataSourceProperties 中的 socketTimeout 为 "5" 秒（socket I/O 读写超时）。
    // 此处直接断言运行时 Spring 容器内生成的 DataSource 实例与属性，确保生产配置有效生效，而非仅静态解析 YAML。
    assertInstanceOf(
        HikariDataSource.class, dataSource, "injected dataSource must be HikariDataSource");
    HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
    assertEquals(
        5000L, hikariDataSource.getConnectionTimeout(), "Hikari connection-timeout must be 5000ms");
    assertEquals(
        "5",
        hikariDataSource.getDataSourceProperties().getProperty("connectTimeout"),
        "PostgreSQL JDBC connectTimeout must be configured to 5s");
    assertEquals(
        "5",
        hikariDataSource.getDataSourceProperties().getProperty("socketTimeout"),
        "PostgreSQL JDBC socketTimeout must be configured to 5s");
  }
}
