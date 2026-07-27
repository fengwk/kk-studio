package fun.fengwk.kkstudio.core.harness.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies that the dev and e2e seeds are idempotent and the e2e seed never stores a real
 * credential. Re-application must not mutate row content for deterministic columns.
 */
class PostgresqlSchemaSeedTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws Exception {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applySchema(conn);
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
      applyScript(conn, "data-e2e-postgresql.sql");
      assertE2eSeedContent(conn);
      // Re-apply and assert that nothing in the deterministic columns changed.
      String before = e2eFingerprint();
      long sequenceBeforeReapply = nextSequenceValue();
      applyScript(conn, "data-e2e-postgresql.sql");
      assertEquals(before, e2eFingerprint(), "e2e seed must be idempotent");
      assertE2eSeedContent(conn);
      assertTrue(
          nextSequenceValue() > sequenceBeforeReapply,
          "re-applying a seed must preserve sequence progress");
    }
    // Sequence must advance past the largest explicit seed id (12 from agent_model,
    // also includes retry_policy and realtime_stream_policy id=1).
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("select nextval('kk_studio_id_seq')")) {
      assertTrue(rs.next());
      long nextId = rs.getLong(1);
      assertTrue(nextId > 12L, () -> "sequence must exceed the highest e2e seed id, got " + nextId);
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
  void e2eProviderIdsMatchCredentialInjectionContract() throws Exception {
    try (Connection conn = newConnection()) {
      applyScript(conn, "data-e2e-postgresql.sql");
      try (Statement st = conn.createStatement()) {
        assertEquals(
            "1:minimax,2:openai,3:xai,4:deepseek,5:google",
            singleString(
                st, "select string_agg(id || ':' || name, ',' order by id) from agent_provider"),
            "scripts/e2e/lib.sh addresses these deterministic provider ids");
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
      applyScript(conn, "data-dev-postgresql.sql");
      assertDevSeedPresent(conn);
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
    assertSingleCount(conn, "agent_provider", 5L);
    assertSingleCount(conn, "agent_model", 12L);
    assertSingleCount(conn, "agent_definition", 1L);
    assertSingleCount(conn, "harness_realtime_stream_policy", 1L);
    assertSingleLong(
        conn, "select max_length from harness_realtime_stream_policy where id = 1", 5_000L);
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
                  + " ';' order by id) from agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st, "select string_agg(name || '|' || version, ';' order by id) from agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by id)"
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
              "select string_agg(id || '|' || name || '|' || coalesce(base_url, '') || '|' ||"
                  + " coalesce(credential, '') || '|' || version, ';' order by id) from"
                  + " agent_provider"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(id || '|' || name || '|' || version, ';' order by id) from"
                  + " agent_model"));
      sb.append('|');
      sb.append(
          singleString(
              st,
              "select string_agg(name || '|' || coalesce(system_prompt, ''), ';' order by id)"
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
}
