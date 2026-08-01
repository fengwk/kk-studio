package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asserts the final PostgreSQL schema structure: every required table and column type is present,
 * the forbidden legacy harness tables are absent, and column types use jsonb/timestamptz/bytea.
 *
 * <p>Public schema equality is strict: the set of {@code BASE TABLE}s in {@code public} must equal
 * the expected list, so a leftover or stub table is caught immediately.
 */
class PostgresqlSchemaStructureTest extends PostgresSchemaSupport {

  private static final List<String> EXPECTED_TABLES =
      Arrays.asList(
          "agent_provider",
          "agent_model",
          "agent_definition",
          "flyway_schema_history",
          "comfyui_workflow_api",
          "canvas_document",
          "canvas_node",
          "canvas_link",
          "canvas_command_dedup",
          "chat",
          "chat_thread",
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_input",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_interaction",
          "harness_retry_policy",
          "harness_realtime_stream_policy",
          "harness_thread_goal",
          "harness_model_usage",
          "harness_artifact",
          "harness_execution_target");

  private static final Set<String> SEQUENCE_BACKED_TABLES =
      Set.of(
          "agent_provider",
          "agent_model",
          "agent_definition",
          "comfyui_workflow_api",
          "canvas_document",
          "canvas_node",
          "canvas_link",
          "chat",
          "harness_entry",
          "harness_session",
          "harness_thread",
          "harness_thread_input",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_interaction",
          "harness_model_usage",
          "harness_artifact");

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  @Test
  void publicSchemaContainsExactlyExpectedTables() throws SQLException {
    Set<String> present = tableNames();
    assertEquals(
        new TreeSet<>(EXPECTED_TABLES),
        present,
        () -> "public schema tables must equal expected set; present=" + present);
  }

  @Test
  void nonHarnessBusinessTablesExposeCompleteColumnContracts() throws SQLException {
    assertColumns(
        "agent_provider",
        "id",
        "name",
        "description",
        "provider_type",
        "base_url",
        "credential",
        "config",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "agent_model",
        "id",
        "provider_id",
        "name",
        "description",
        "config",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "agent_definition",
        "id",
        "name",
        "description",
        "system_prompt",
        "model_id",
        "variant",
        "config",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "comfyui_workflow_api",
        "id",
        "api_name",
        "name",
        "description",
        "workflow",
        "input_bindings",
        "default_selector",
        "enabled",
        "created_at",
        "updated_at",
        "version");
    assertColumns("canvas_document", "id", "title", "revision", "home_viewport", "updated_at");
    assertColumns(
        "canvas_node",
        "id",
        "canvas_id",
        "kind",
        "node_type",
        "name",
        "x",
        "y",
        "width",
        "height",
        "data");
    assertColumns("canvas_link", "id", "canvas_id", "source_node_id", "target_node_id");
    assertColumns("canvas_command_dedup", "canvas_id", "command_id", "request_hash");
    assertColumns(
        "chat",
        "id",
        "title",
        "default_agent_id",
        "default_environment_name",
        "created_at",
        "updated_at",
        "version");
    assertColumns("chat_thread", "chat_id", "thread_id", "created_at");
  }

  @Test
  void harnessSessionExposesOnlyItsMinimalColumnContract() throws SQLException {
    assertColumns("harness_session", "id", "title", "created_at");
  }

  @Test
  void harnessPolicyTablesExposeOnlyTheirMinimalColumnContracts() throws SQLException {
    assertColumns(
        "harness_retry_policy",
        "id",
        "max_retries",
        "backoff_strategy",
        "base_delay_millis",
        "max_delay_millis");
    assertColumns("harness_realtime_stream_policy", "id", "max_length");
  }

  @Test
  void harnessExecutionTargetExposesOnlyItsDurableQueueContract() throws SQLException {
    assertColumns(
        "harness_execution_target",
        "target_kind",
        "target_id",
        "route_key",
        "dispatch_enabled",
        "available_at");
  }

  @Test
  void executionTargetRouteQueueIndexIncludesParkedRows() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select indexdef from pg_indexes where schemaname = 'public'"
                    + " and indexname = 'idx_harness_execution_target_route_queue'")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "route queue index must exist");
        String indexDefinition = rs.getString(1).toLowerCase();
        assertTrue(
            indexDefinition.contains("(route_key, target_kind, target_id)"),
            () -> "route queue index columns mismatch: " + indexDefinition);
        assertTrue(
            !indexDefinition.contains(" where "),
            () -> "route queue index must be non-partial: " + indexDefinition);
      }
    }
  }

  @Test
  void harnessEntryDoesNotExposeLegacyVersionColumn() throws SQLException {
    Set<String> columns = new TreeSet<>();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'harness_entry'")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          columns.add(rs.getString(1));
        }
      }
    }
    assertTrue(
        !columns.contains("version"),
        () -> "harness_entry must not declare a legacy version column; found=" + columns);
  }

  @Test
  void usesJsonbForStructuredPayloads() throws SQLException {
    assertColumnType("jsonb", "harness_entry", "payload");
    assertColumnType("jsonb", "harness_thread_input", "payload");
    assertColumnType("jsonb", "agent_provider", "config");
    assertColumnType("jsonb", "agent_model", "config");
    assertColumnType("jsonb", "agent_definition", "config");
    assertColumnType("jsonb", "harness_model_invocation", "request");
    assertColumnType("jsonb", "harness_tool_invocation", "descriptor");
    assertColumnType("jsonb", "harness_model_usage", "raw_usage");
  }

  @Test
  void usesTimestamptzForTemporalColumns() throws SQLException {
    assertColumnType("timestamp with time zone", "harness_session", "created_at");
    assertColumnType("timestamp with time zone", "harness_entry", "created_at");
    assertColumnType("timestamp with time zone", "harness_thread", "processor_until");
    assertColumnType("timestamp with time zone", "harness_thread_input", "applied_at");
    assertColumnType("timestamp with time zone", "harness_thread_input", "created_at");
    assertColumnType("timestamp with time zone", "harness_execution_target", "available_at");
  }

  @Test
  void usesByteaForArtifactContent() throws SQLException {
    assertColumnType("bytea", "harness_artifact", "content");
  }

  @Test
  void usesNativeBooleanForFlags() throws SQLException {
    assertColumnType("boolean", "harness_thread", "runnable");
    assertColumnType("boolean", "harness_execution_target", "dispatch_enabled");
    assertColumnType("boolean", "harness_model_usage", "cache_eligible");
  }

  @Test
  void generatedDurableEntityIdsUseBigint() throws SQLException {
    for (String table : SEQUENCE_BACKED_TABLES) {
      assertColumnType("bigint", table, "id");
    }
  }

  @Test
  void globalSequenceAllocatesMonotonicIds() throws SQLException {
    long a = singleLong("select nextval('kk_studio_id_seq')");
    long b = singleLong("select nextval('kk_studio_id_seq')");
    assertTrue(b > a, () -> "sequence must monotonically increase, a=" + a + " b=" + b);
  }

  @Test
  void globalSequenceBacksPrimaryKeyDefaults() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config) values (?, 'openai',"
                    + " '{}'::jsonb)")) {
      ps.setString(1, "sequence-fixture-" + System.nanoTime());
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void generatedEntityIdsUseOnlyTheGlobalSequence() throws SQLException {
    Set<String> sequences = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select sequence_name from information_schema.sequences"
                    + " where sequence_schema = 'public'")) {
      while (rs.next()) {
        sequences.add(rs.getString(1));
      }
    }
    assertEquals(Set.of("kk_studio_id_seq"), sequences, "schema must expose one id sequence");

    Set<String> sequenceBackedTables = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name from information_schema.columns"
                    + " where table_schema = 'public' and column_name = 'id'"
                    + " and column_default = 'nextval(''kk_studio_id_seq''::regclass)'")) {
      while (rs.next()) {
        sequenceBackedTables.add(rs.getString(1));
      }
    }
    assertEquals(
        new TreeSet<>(SEQUENCE_BACKED_TABLES),
        sequenceBackedTables,
        "every generated durable id must use the single PostgreSQL sequence");
  }

  @Test
  void onlyTheThreadHeadForeignKeyIsInitiallyDeferred() throws SQLException {
    Set<String> deferred = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname from pg_constraint"
                    + " where contype = 'f' and condeferrable and condeferred")) {
      while (rs.next()) {
        deferred.add(rs.getString(1));
      }
    }
    // Session no longer points back at a Thread, so the old bootstrap cycle is gone; only the
    // optional Thread head remains deferrable so bootstrap can bind a freshly inserted Entry.
    assertEquals(
        Set.of("fk_harness_thread_head"),
        deferred,
        "only the optional Thread head FK needs deferral once Session and Thread are decoupled");
  }

  @Test
  void domainUniqueKeysAndBusinessForeignKeysAreComplete() throws SQLException {
    Set<String> indexes = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select indexname from pg_indexes where schemaname = 'public'"
                    + " and indexname like 'uk_%'")) {
      while (rs.next()) {
        indexes.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "uk_agent_provider_name",
            "uk_agent_model_provider_name",
            "uk_agent_definition_name",
            "uk_comfyui_workflow_api_api_name",
            "uk_canvas_node_canvas_id",
            "uk_canvas_link",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_thread_input_sequence",
            "uk_harness_thread_input_idempotency",
            "uk_harness_model_invocation_source",
            "uk_harness_model_invocation_thread_id",
            "uk_harness_tool_invocation_source",
            "uk_harness_tool_invocation_session_id",
            "uk_harness_interaction_open",
            "uk_harness_model_usage_assistant_entry"),
        indexes,
        "the final schema must expose only its declared domain unique keys");

    Set<String> foreignKeys = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname from pg_constraint where contype = 'f'"
                    + " and conname in ('fk_agent_model_provider',"
                    + " 'fk_agent_definition_model', 'fk_canvas_node_canvas',"
                    + " 'fk_canvas_command_dedup_canvas',"
                    + " 'fk_canvas_link_source',"
                    + " 'fk_canvas_link_target')")) {
      while (rs.next()) {
        foreignKeys.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "fk_agent_model_provider",
            "fk_agent_definition_model",
            "fk_canvas_node_canvas",
            "fk_canvas_command_dedup_canvas",
            "fk_canvas_link_source",
            "fk_canvas_link_target"),
        foreignKeys,
        "all non-Harness ownership relations must be enforced by PostgreSQL");
  }

  @Test
  void updatedAtIsApplicationManagedNotAuto() throws SQLException {
    // Set explicit created_at/updated_at; later UPDATE the description and ensure updated_at does
    // NOT change unless explicitly rewritten (no MySQL ON UPDATE emulation).
    long id = FIXTURE_IDS.incrementAndGet();
    Timestamp fixedTimestamp = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (id, name, provider_type, config, created_at,"
                    + " updated_at) values (?, 'auto-update-probe', 'openai', '{}'::jsonb, ?, ?)")) {
      ps.setLong(1, id);
      ps.setTimestamp(2, fixedTimestamp);
      ps.setTimestamp(3, fixedTimestamp);
      ps.executeUpdate();
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update agent_provider set description = 'touched' where id = ?")) {
      ps.setLong(1, id);
      assertEquals(1, ps.executeUpdate());
    }
    Timestamp postUpdate;
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select updated_at from agent_provider where id = ?")) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        postUpdate = rs.getTimestamp(1);
      }
    }
    assertEquals(
        fixedTimestamp, postUpdate, "updated_at must remain stable without an explicit rewrite");
  }

  @Test
  void flywayRerunIsANoopAndRecordsBaselineHistory() throws SQLException {
    try (Connection conn = newConnection()) {
      assertDoesNotThrow(() -> applyBaseline(conn));
    }
    assertEquals(
        1L,
        singleLong(
            "select count(*) from flyway_schema_history where version = '1' and success = true"),
        "the baseline migration must be recorded exactly once");
  }

  private static Set<String> tableNames() throws SQLException {
    Set<String> names = new LinkedHashSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name from information_schema.tables"
                    + " where table_schema = 'public' and table_type = 'BASE TABLE'")) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return new TreeSet<>(names);
  }

  private static void assertColumnType(String expected, String table, String column)
      throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select data_type from information_schema.columns"
                    + " where table_schema = 'public' and table_name = ? and column_name = ?")) {
      ps.setString(1, table);
      ps.setString(2, column);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "column " + table + "." + column + " not found");
        assertEquals(
            expected,
            rs.getString(1),
            () -> "column " + table + "." + column + " data_type mismatch");
      }
    }
  }

  private static void assertColumns(String table, String... expected) throws SQLException {
    Set<String> actual = new TreeSet<>();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select column_name from information_schema.columns"
                    + " where table_schema = 'public' and table_name = ?")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          actual.add(rs.getString(1));
        }
      }
    }
    assertEquals(
        new TreeSet<>(Arrays.asList(expected)),
        actual,
        () -> "column contract mismatch for " + table);
  }

  private static long singleLong(String sql) throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next(), "no row for: " + sql);
      return rs.getLong(1);
    }
  }
}
