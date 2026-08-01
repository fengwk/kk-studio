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

/** Proves Spring Boot's Flyway auto-configuration migrates the application's multi-data-source. */
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
    // Starting the real web context exercises Boot auto-configuration, not a direct Flyway harness.
    assertTrue(serverPort > 0);
    try (Connection conn = dataSource.getConnection();
        Statement st = conn.createStatement();
        ResultSet history =
            st.executeQuery(
                "select count(*) from flyway_schema_history"
                    + " where success = true and version in ('1', '2')")) {
      assertTrue(history.next());
      assertEquals(2L, history.getLong(1), "baseline and dev seed must be recorded");
      try (ResultSet seed =
          st.executeQuery(
              "select count(*) from agent_definition where name = 'default-assistant'")) {
        assertTrue(seed.next());
        assertEquals(1L, seed.getLong(1), "dev seed must be visible through the multi-data-source");
      }
      try (ResultSet chatThread =
          st.executeQuery(
              "select count(*) from information_schema.tables"
                  + " where table_schema = 'public' and table_name = 'chat_thread'")) {
        assertTrue(chatThread.next());
        assertEquals(1L, chatThread.getLong(1), "Chat↔Thread table must be part of V1");
      }
    }
  }
}
