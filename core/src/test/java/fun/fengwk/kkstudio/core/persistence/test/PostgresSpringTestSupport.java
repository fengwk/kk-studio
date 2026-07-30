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
 * Shared Spring PostgreSQL Testcontainers support for non-Harness business integration tests.
 *
 * <p>Reuses the single {@code postgres:17-alpine} container declared on {@link
 * fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport}. Wires the
 * authoritative multi-datasource configuration to the running container so {@code
 * CoreTestApplication} binds {@code spring.datasource.multi.primary} to PostgreSQL rather than H2.
 *
 * <p>Disables automatic Flyway so each test can reset public and explicitly migrate its baseline
 * after the cached context is ready.
 *
 * <p>Docker must be available — when it is not, container start fails and the test fails rather
 * than silently skipping.
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
  }

  @BeforeEach
  final void resetAndApplySchema() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      migrateDatabase(conn);
    }
  }

  /** Override to select one of the explicit Flyway profile migration locations. */
  protected void migrateDatabase(Connection conn) {
    applyBaseline(conn);
  }
}
