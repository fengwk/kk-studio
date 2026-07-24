package fun.fengwk.kkstudio.web;

import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Shared PostgreSQL Testcontainers support for web module SpringBoot tests.
 *
 * <p>Disables {@code spring.sql.init} and re-applies {@code schema-postgresql.sql} before each
 * test.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
@Testcontainers(disabledWithoutDocker = false)
public abstract class WebPostgresTestSupport {

  private static final String SQL_INIT_NEVER = "never";
  private static final String WORKERS_DISABLED = "false";

  @Container
  @SuppressWarnings("resource")
  protected static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("kk_studio_web_test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void overrideMultiDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
    registry.add("spring.sql.init.mode", () -> SQL_INIT_NEVER);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> WORKERS_DISABLED);
  }

  @BeforeEach
  final void resetAndApplySchema() throws Exception {
    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      try (Statement st = conn.createStatement()) {
        st.execute("drop schema public cascade");
        st.execute("create schema public");
      }
      ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-postgresql.sql"));
    }
  }
}
