package fun.fengwk.kkstudio.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Fresh V1 contract for pricing configuration, without legacy-data migration. */
class PricingMigrationTest {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kkstudio_schema_pricing")
          .withUsername("kkstudio_schema")
          .withPassword("kkstudio_schema");

  @Test
  void freshBaselineUsesCurrentPricingShapeAndDefault() throws Exception {
    POSTGRES.start();
    try {
      Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .locations("classpath:db/migration", "classpath:db/seed/fixture")
          .load()
          .migrate();

      try (Connection connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "select config -> 'pricing' from model where name = 'acceptance-stub'"); ) {
        assertTrue(result.next());
        String pricing = result.getString(1);
        assertTrue(pricing.contains("\"rules\""));
        assertTrue(pricing.contains("\"changes\""));
        assertTrue(!pricing.contains("\"overrides\""));
        assertTrue(!pricing.contains("\"multipliers\""));
        assertTrue(!result.next());
      }
      try (Connection connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "select count(*) from flyway_schema_history where success and version = '1'")) {
        assertTrue(result.next());
        assertEquals(1, result.getInt(1));
      }
    } finally {
      POSTGRES.stop();
    }
  }
}
