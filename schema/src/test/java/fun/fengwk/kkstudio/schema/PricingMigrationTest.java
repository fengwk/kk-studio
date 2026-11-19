package fun.fengwk.kkstudio.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** V2 -> V3 pricing migration behavior on an isolated PostgreSQL database. */
class PricingMigrationTest {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kkstudio_pricing_migration")
          .withUsername("kkstudio_schema")
          .withPassword("kkstudio_schema");

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @BeforeEach
  void migrateToV2() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop schema if exists public cascade");
      statement.execute("create schema public");
    }
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target("2")
        .load()
        .migrate();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void migratesLegacyBaseAndWorkdayWindowsWithoutChangingRates() throws Exception {
    String base =
        "{\"input\":\"1.25\",\"output\":\"3.5\",\"cacheRead\":\"0.2\","
            + "\"cacheWrite\":\"0\",\"cacheWriteByTtl\":{\"300\":\"1.1\"},"
            + "\"reasoning\":\"4.25\"}";
    insertModel("migratable", legacyWorkdayPricing(base, "01:00", "24:00"));

    String plainPricing =
        "{\"pricing\":{\"base\":{\"input\":\"0\",\"output\":\"0\",\"cacheRead\":\"0\","
            + "\"cacheWrite\":\"0\",\"cacheWriteByTtl\":{},\"reasoning\":\"0\"},"
            + "\"overrides\":[],\"multipliers\":[]}}";
    insertModel("plain", plainPricing);

    migrateLatest();

    String expectedPricing =
        "{\"base\":"
            + base
            + ",\"rules\":["
            + "{\"type\":\"time_multiplier\",\"start\":\"01:00\",\"end\":\"04:00\","
            + "\"timezone\":\"UTC\",\"weekdays\":[1,2,3,4,5],\"factor\":\"0.8\"},"
            + "{\"type\":\"time_multiplier\",\"start\":\"06:00\",\"end\":\"10:00\","
            + "\"timezone\":\"UTC\",\"weekdays\":[1,2,3,4,5],\"factor\":\"0.8\"}],"
            + "\"changes\":[]}";
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "select config -> 'pricing' = '"
                    + expectedPricing
                    + "'::jsonb "
                    + "from model where name = 'migratable'")) {
      assertTrue(result.next());
      assertTrue(result.getBoolean(1), "migration must preserve base rates and split UTC windows");
      assertFalse(result.next());
    }
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "select config -> 'pricing' = '{\"base\":{\"input\":\"0\",\"output\":\"0\","
                    + "\"cacheRead\":\"0\",\"cacheWrite\":\"0\",\"cacheWriteByTtl\":{},"
                    + "\"reasoning\":\"0\"},\"rules\":[],\"changes\":[]}'::jsonb "
                    + "from model where name = 'plain'")) {
      assertTrue(result.next());
      assertTrue(result.getBoolean(1), "empty legacy rules must map to empty typed lists");
      assertFalse(result.next());
    }
  }

  @Test
  void fixtureSeedUsesTypedPricingLists() throws Exception {
    migrateWithSeed("classpath:db/seed/fixture");
    assertSeedPricingUsesTypedLists();
  }

  @Test
  void e2eSeedUsesTypedPricingLists() throws Exception {
    migrateWithSeed("classpath:db/seed/e2e");
    assertSeedPricingUsesTypedLists();
  }

  @Test
  void rejectsUnknownLegacyShapeAtomicallyWithoutEchoingItsJson() throws Exception {
    String unsupported =
        "{\"pricing\":{\"base\":{},\"overrides\":[{\"sensitiveMarker\":\"do-not-echo\"}],"
            + "\"multipliers\":[]}}";
    insertModel(
        "a-safe", "{\"pricing\":{\"base\":{\"input\":\"0\"},\"overrides\":[],\"multipliers\":[]}}");
    insertModel("z-unsupported", unsupported);

    try {
      migrateLatest();
      fail("unsupported pricing must abort V3");
    } catch (RuntimeException exception) {
      Throwable cause = exception;
      while (cause != null) {
        assertFalse(String.valueOf(cause.getMessage()).contains("do-not-echo"));
        cause = cause.getCause();
      }
    }

    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      try (ResultSet result =
          statement.executeQuery(
              "select config = '"
                  + unsupported
                  + "'::jsonb from model where name = 'z-unsupported'")) {
        assertTrue(result.next());
        assertTrue(
            result.getBoolean(1),
            "the rejected migration must leave the original JSONB row intact");
      }
      try (ResultSet result =
          statement.executeQuery(
              "select config -> 'pricing' ? 'overrides' from model where name = 'a-safe'")) {
        assertTrue(result.next());
        assertTrue(result.getBoolean(1), "earlier row changes must roll back with V3");
      }
      try (ResultSet result =
          statement.executeQuery(
              "select version from flyway_schema_history where success "
                  + "order by installed_rank desc limit 1")) {
        assertTrue(result.next());
        assertEquals("2", result.getString(1), "failed V3 must leave the schema at V2");
      }
    }
  }

  @Test
  void rejectsWrappingLeafInsteadOfDroppingPartOfItsOrCondition() throws Exception {
    // A non-wrapping sibling keeps the OR satisfiable, so dropping the wrapping leaf would
    // appear to succeed but silently remove its early-morning rates.
    insertModel("wraps-midnight", legacyWorkdayPricing("{}", "23:00", "04:00"));
    assertThrows(FlywayException.class, PricingMigrationTest::migrateLatest);
  }

  private static String legacyWorkdayPricing(String base, String firstStart, String firstEnd) {
    return """
        {"pricing":{"base":%s,"overrides":[],"multipliers":[{"id":"legacy",
          "factors":{"input":"0.8","output":"0.8","cacheRead":"0.8","cacheWrite":"0.8","reasoning":"0.8"},
          "when":{"time":{"start":"00:00","end":"24:00","timezone":"UTC","weekdays":[1,2,3,4,5]},
            "any":[
              {"all":[{"time":{"start":"%s","end":"%s","timezone":"UTC"}},
                      {"time":{"start":"00:00","end":"04:00","timezone":"UTC"}}]},
              {"all":[{"time":{"start":"06:00","end":"24:00","timezone":"UTC"}},
                      {"time":{"start":"00:00","end":"10:00","timezone":"UTC"}}]}]}}]}}
        """
        .formatted(base, firstStart, firstEnd);
  }

  private static void migrateWithSeed(String seedLocation) {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration", seedLocation)
        .load()
        .migrate();
  }

  private static void assertSeedPricingUsesTypedLists() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "select count(*) from model where kind = 'MODEL' and "
                    + "(config -> 'pricing' is null "
                    + "or jsonb_typeof(config -> 'pricing') is distinct from 'object' "
                    + "or not (config -> 'pricing' ? 'rules') "
                    + "or not (config -> 'pricing' ? 'changes') "
                    + "or config -> 'pricing' ? 'overrides' "
                    + "or config -> 'pricing' ? 'multipliers')")) {
      assertTrue(result.next());
      assertEquals(0, result.getInt(1), "seeds must write only the active pricing contract");
    }
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void migrateLatest() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private static void insertModel(String name, String config) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "insert into provider(name, provider_type, config) values ('p', 'openai', '{}') "
              + "on conflict (name) do nothing");
      statement.execute(
          "insert into model(name, kind, provider_name, model_id, config) values ('"
              + name
              + "', 'MODEL', 'p', 'wire-model', '"
              + config.replace("'", "''")
              + "'::jsonb)");
    }
  }
}
