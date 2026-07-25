package fun.fengwk.kkstudio.web;

import org.junit.jupiter.api.BeforeEach;
import org.postgresql.Driver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Shared PostgreSQL support for web module SpringBoot tests.
 *
 * <p>Uses a process-level singleton container (not {@code @Container}) so the JDBC URL stays stable
 * for the cached Spring context across test classes. Each test resets {@code public} and re-applies
 * {@code schema-postgresql.sql} plus the dev seed.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
public abstract class WebPostgresTestSupport {

  private static final String SQL_INIT_NEVER = "never";
  private static final String WORKERS_DISABLED = "false";

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
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
      ScriptUtils.executeSqlScript(conn, new ClassPathResource("data-dev-postgresql.sql"));
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select count(*) from agent_definition where id = 1")) {
        if (!rs.next() || rs.getLong(1) != 1L) {
          throw new IllegalStateException("dev seed did not insert agent_definition id=1");
        }
      }
    }
  }
}
