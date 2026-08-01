package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;

/**
 * Verifies that the dev and e2e seeds are idempotent and the e2e seed never stores a real
 * credential. Re-application must not mutate row content for deterministic columns.
 */
class PostgresqlSchemaSeedTest extends PostgresSchemaSupport {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @BeforeEach
  void setup() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void devSeedIsIdempotentAndStable() throws Exception {
    applyAndAssertDevSeed();
    // Snapshot deterministic columns.
    String devFingerprintBefore = devSeedFingerprint();
    long maxVersionBefore = maxVersionAcrossAgentTables();
    long sequenceBeforeReapply = nextSequenceValue();
    // Re-apply: rows must remain identical (no version bumps, no mutated timestamps).
    applyAndAssertDevSeed();
    assertEquals(devFingerprintBefore, devSeedFingerprint(), "dev seed must be idempotent");
    assertEquals(maxVersionBefore, maxVersionAcrossAgentTables(), "version must stay 0");
    assertTrue(
        nextSequenceValue() > sequenceBeforeReapply,
        "re-applying a seed must never move the global sequence backwards");
  }

  @Test
  void e2eSeedStoresNoCredentialsAndIsIdempotent() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      assertE2eSeedContent(conn);
      assertProfileMigrationRecorded(conn, "e2e seed");
      // Re-run the profile migration and assert that deterministic columns do not change.
      String before = e2eFingerprint();
      long sequenceBeforeReapply = nextSequenceValue();
      applyE2eDatabase(conn);
      assertEquals(before, e2eFingerprint(), "e2e seed must be idempotent");
      assertE2eSeedContent(conn);
      assertProfileMigrationRecorded(conn, "e2e seed");
      assertTrue(
          nextSequenceValue() > sequenceBeforeReapply,
          "re-applying a seed must preserve sequence progress");
    }
    // Only the harness singleton rows use explicit ids; catalog identities are names.
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select nextval('kk_studio_id_seq')")) {
      assertTrue(rs.next());
      long nextId = rs.getLong(1);
      assertTrue(
          nextId > 1L, () -> "sequence must exceed the highest explicit seed id, got " + nextId);
    }
    // Subsequent inserts must not collide with deterministic seed ids.
    assertDoesNotThrow(
        () -> {
          try (Connection conn = newConnection();
              PreparedStatement ps =
                  conn.prepareStatement(
                      "insert into agent_provider (name, provider_type, config) values (?,"
                          + " 'openai', '{}'::jsonb)")) {
            ps.setString(1, "post-seed-provider");
            ps.executeUpdate();
          }
        });
  }

  @Test
  void e2eSeedCatalogAndMiniMaxCredentialTargetAreDeterministic() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      try (Statement st = conn.createStatement()) {
        assertEquals(
            "anthropic:anthropic,deepseek:openai,google:google,minimax:openai_response,"
                + "openai:openai_response,xai:openai_response,zai:openai",
            singleString(
                st,
                "select string_agg(name || ':' || provider_type, ',' order by name)"
                    + " from agent_provider"),
            "the complete E2E seed catalog must retain its deterministic provider names");
        assertEquals(
            "minimax:openai_response",
            singleString(
                st,
                "select name || ':' || provider_type from agent_provider where name = 'minimax'"),
            "the MiniMax credential synchronizer targets the minimax provider name");
      }
      long retryPolicyCount;
      try (Statement st = conn.createStatement();
          ResultSet rs = st.executeQuery("select count(*) from harness_retry_policy")) {
        assertTrue(rs.next());
        retryPolicyCount = rs.getLong(1);
      }
      assertEquals(1L, retryPolicyCount, "exactly one retry_policy row from seed");
      try (Statement st = conn.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "select max_length from harness_realtime_stream_policy where id = 1")) {
        assertTrue(rs.next());
        assertEquals(5_000L, rs.getLong(1), "seed must initialize realtime Stream capacity");
      }
    }
  }

  private static void applyAndAssertDevSeed() throws Exception {
    try (Connection conn = newConnection()) {
      applyDevDatabase(conn);
      assertDevSeedPresent(conn);
      assertProfileMigrationRecorded(conn, "dev seed");
    }
  }

  private static void assertDevSeedPresent(Connection conn) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from agent_provider where name = 'stub'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select credential from agent_provider where name = 'stub'")) {
      assertTrue(rs.next());
      assertEquals("stub-key", rs.getString(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select count(*) from agent_model where name = 'acceptance-stub'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select count(*) from agent_definition where name = 'default-assistant'")) {
      assertTrue(rs.next());
      assertEquals(1L, rs.getLong(1));
    }
    assertSingleLong(
        conn, "select max_length from harness_realtime_stream_policy where id = 1", 5_000L);
  }

  private static void assertE2eSeedContent(Connection conn) throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery("select count(*) from agent_provider where credential is not null")) {
      assertTrue(rs.next());
      assertEquals(
          0L,
          rs.getLong(1),
          "no e2e provider may carry a credential; a real secret must never be checked in");
    }
    assertSingleCount(conn, "agent_provider", 7L);
    assertSingleCount(conn, "agent_model", 19L);
    assertSingleCount(conn, "agent_definition", 1L);
    assertSingleCount(conn, "harness_realtime_stream_policy", 1L);
    assertSingleLong(
        conn, "select max_length from harness_realtime_stream_policy where id = 1", 5_000L);
    try (Statement st = conn.createStatement()) {
      assertEquals(
          "minimax/MiniMax-M2.7:high",
          singleString(
              st,
              "select a.model_provider_name || '/' || a.model_name || ':' || a.variant"
                  + " from agent_definition a"
                  + " join agent_model m on m.provider_name = a.model_provider_name"
                  + " and m.name = a.model_name"
                  + " where a.name = 'default-assistant'"),
          "the default E2E agent must remain bound to minimax/MiniMax-M2.7");
    }
    assertPiModelCatalog(conn);
  }

  private static void assertPiModelCatalog(Connection conn) throws Exception {
    try (InputStream input =
            Objects.requireNonNull(
                PostgresqlSchemaSeedTest.class.getResourceAsStream("pi-model-catalog.json"));
        Statement st = conn.createStatement()) {
      JsonNode expected = OBJECT_MAPPER.readTree(input);
      JsonNode actual =
          OBJECT_MAPPER.readTree(
              singleString(
                  st,
                  "select jsonb_agg(jsonb_build_object("
                      + "'provider', p.name,"
                      + "'name', m.name,"
                      + "'description', m.description,"
                      + "'config', m.config"
                      + ") order by m.provider_name, m.name)::text"
                      + " from agent_model m"
                      + " join agent_provider p on p.name = m.provider_name"));
      assertEquals(
          canonicalModels(expected),
          canonicalModels(actual),
          "E2E models must match the effective Pi 0.82.1 catalog");
    }
  }

  private static JsonNode canonicalModels(JsonNode models) {
    ArrayList<JsonNode> sorted = new ArrayList<>();
    models.forEach(sorted::add);
    sorted.sort(
        Comparator.comparing(
            model -> model.path("provider").asText() + "/" + model.path("name").asText()));
    ArrayNode canonical = OBJECT_MAPPER.createArrayNode();
    sorted.forEach(canonical::add);
    return canonical;
  }

  private static void assertSingleCount(Connection conn, String table, long expected)
      throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + table)) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getLong(1), () -> table + " row count mismatch");
    }
  }

  private static String devSeedFingerprint() throws Exception {
    // Concatenate deterministic column values across the rows the dev seed owns.
    StringBuilder sb = new StringBuilder();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || provider_type || '|' || coalesce(credential,''),"
                  + " ';' order by name) from agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(provider_name || '/' || name || '|' || version,"
                  + " ';' order by provider_name, name) from agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by name)"
                  + " from agent_definition"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(id || '|' || max_length, ';' order by id) from"
                  + " harness_realtime_stream_policy"));
    }
    return sb.toString();
  }

  private static long maxVersionAcrossAgentTables() throws Exception {
    long max = 0L;
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select greatest("
                    + "(select coalesce(max(version), 0) from agent_provider),"
                    + "(select coalesce(max(version), 0) from agent_model),"
                    + "(select coalesce(max(version), 0) from agent_definition))")) {
      assertTrue(rs.next());
      max = rs.getLong(1);
    }
    return max;
  }

  private static long nextSequenceValue() throws Exception {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select nextval('kk_studio_id_seq')")) {
      assertTrue(rs.next());
      return rs.getLong(1);
    }
  }

  private static String e2eFingerprint() throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || provider_type || '|' ||"
                  + " coalesce(base_url, '') || '|' || coalesce(credential, '') || '|' ||"
                  + " version, ';' order by name) from agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(provider_name || '/' || name || '|' || coalesce(description, '')"
                  + " || '|' || config::text || '|' || version,"
                  + " ';' order by provider_name, name) from agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by name)"
                  + " from agent_definition"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(id || '|' || max_retries || '|' || backoff_strategy, ';' order"
                  + " by id) from harness_retry_policy"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(id || '|' || max_length, ';' order by id) from"
                  + " harness_realtime_stream_policy"));
    }
    return sb.toString();
  }

  private static String singleString(Statement st, String sql) throws Exception {
    try (ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next(), "no result for: " + sql);
      String value = rs.getString(1);
      assertNotNull(value, "string aggregate unexpectedly null for " + sql);
      return value;
    }
  }

  private static void assertSingleLong(Connection conn, String sql, long expected)
      throws Exception {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next());
      assertEquals(expected, rs.getLong(1), sql);
    }
  }

  private static void assertProfileMigrationRecorded(Connection conn, String description)
      throws Exception {
    assertSingleLong(
        conn,
        "select count(*) from flyway_schema_history"
            + " where version = '2' and description = '"
            + description
            + "' and success = true",
        1L);
  }
}
