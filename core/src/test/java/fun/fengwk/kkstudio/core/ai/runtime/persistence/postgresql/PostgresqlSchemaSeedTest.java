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

/** 验证 dev 与 e2e seed 都具备幂等性，并且 e2e seed 永不存储真实凭据。重新执行不能改变确定性列的行内容。 */
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
    // 快照确定性列。
    String devFingerprintBefore = devSeedFingerprint();
    long maxVersionBefore = maxVersionAcrossAgentTables();
    // 重新执行：行内容必须保持一致（无版本变化、无时间戳变化）。
    applyAndAssertDevSeed();
    assertEquals(devFingerprintBefore, devSeedFingerprint(), "dev seed must be idempotent");
    assertEquals(maxVersionBefore, maxVersionAcrossAgentTables(), "version must stay 0");
  }

  @Test
  void e2eSeedStoresNoCredentialsAndIsIdempotent() throws Exception {
    try (Connection conn = newConnection()) {
      applyE2eDatabase(conn);
      assertE2eSeedContent(conn);
      assertProfileMigrationRecorded(conn, "e2e seed");
      // 重新运行 profile migration，并断言确定性列不发生变化。
      String before = e2eFingerprint();
      applyE2eDatabase(conn);
      assertEquals(before, e2eFingerprint(), "e2e seed must be idempotent");
      assertE2eSeedContent(conn);
      assertProfileMigrationRecorded(conn, "e2e seed");
    }
    // Catalog 标识就是名称；任何 seed 行都不会消耗业务序列。
    // 后续插入必须不会与确定性 seed id 冲突。
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
    // 把 dev seed 拥有的所有行的确定性列值拼接在一起。
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
