package fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * the harness execution protocol is exactly the seven runtime-spring tables, the forbidden legacy
 * harness tables are absent, and structured payloads use jsonb (never bytea).
 *
 * <p>Public schema equality is strict: the set of {@code BASE TABLE}s in {@code public} must equal
 * the expected list, so a leftover or stub table is caught immediately.
 */
class PostgresqlSchemaStructureTest extends PostgresSchemaSupport {

  private static final List<String> EXPECTED_TABLES =
      Arrays.asList(
          "agent_provider",
          "agent_provider_revision",
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
          "agent_thread_goal",
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work");

  /** The exact runtime-spring execution protocol; only these tables may use the harness_ prefix. */
  private static final Set<String> HARNESS_TABLES =
      Set.of(
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work");

  private static final Set<String> FORBIDDEN_LEGACY_TABLES =
      Set.of(
          "harness_thread_input",
          "harness_interaction",
          "harness_model_usage",
          "harness_execution_activation",
          "harness_execution_target",
          "harness_artifact",
          "harness_retry_policy",
          "harness_realtime_stream_policy",
          "harness_environment_queue",
          "harness_thread_goal");

  /** Business tables whose durable ids default from {@code kk_studio_id_seq}. */
  private static final Set<String> BUSINESS_SEQUENCE_BACKED_TABLES =
      Set.of("comfyui_workflow_api", "canvas_document", "canvas_node", "canvas_link", "chat");

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
  void harnessSchemaContainsExactlyTheSevenRuntimeTables() throws SQLException {
    Set<String> harnessTables = new TreeSet<>();
    for (String table : tableNames()) {
      if (table.startsWith("harness_")) {
        harnessTables.add(table);
      }
    }
    assertEquals(
        new TreeSet<>(HARNESS_TABLES),
        harnessTables,
        "exactly the seven runtime-spring execution tables may use the harness_ prefix");
  }

  @Test
  void forbiddenLegacyHarnessTablesAreAbsent() throws SQLException {
    for (String table : FORBIDDEN_LEGACY_TABLES) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "select 1 from information_schema.tables"
                      + " where table_schema = 'public' and table_name = ?")) {
        ps.setString(1, table);
        try (ResultSet rs = ps.executeQuery()) {
          assertFalse(rs.next(), () -> "legacy harness table must be removed: " + table);
        }
      }
    }
  }

  @Test
  void nonHarnessBusinessTablesExposeCompleteColumnContracts() throws SQLException {
    assertColumns(
        "agent_provider",
        "name",
        "description",
        "provider_type",
        "base_url",
        "credential",
        "config",
        "created_at",
        "updated_at",
        "version",
        "deleted_at");
    assertColumns(
        "agent_provider_revision",
        "provider_name",
        "provider_version",
        "provider_type",
        "base_url",
        "credential",
        "config",
        "created_at");
    assertColumns(
        "agent_model",
        "provider_name",
        "name",
        "description",
        "config",
        "created_at",
        "updated_at",
        "version",
        "deleted_at");
    assertColumns(
        "agent_definition",
        "name",
        "description",
        "system_prompt",
        "model_provider_name",
        "model_name",
        "variant",
        "config",
        "created_at",
        "updated_at",
        "version",
        "deleted_at");
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
        "chat", "id", "title", "agent_name", "yolo_enabled", "created_at", "updated_at", "version");
    assertColumns("chat_thread", "chat_id", "thread_id", "created_at");
    assertColumns(
        "agent_thread_goal",
        "thread_id",
        "objective",
        "token_budget",
        "status",
        "reason",
        "created_at",
        "updated_at");
  }

  @Test
  void harnessExecutionTablesExposeExactColumnContracts() throws SQLException {
    assertColumns("harness_session", "id", "title", "created_at");
    assertColumns(
        "harness_entry",
        "id",
        "session_id",
        "parent_entry_id",
        "entry_type",
        "payload",
        "created_at");
    assertColumns(
        "harness_thread",
        "id",
        "head_entry_id",
        "yolo_enabled",
        "next_command_sequence",
        "revision",
        "created_at",
        "updated_at");
    assertColumns(
        "harness_thread_command",
        "id",
        "thread_id",
        "sequence",
        "command_type",
        "payload",
        "client_command_id",
        "consumed_turn_start_entry_id",
        "cancelled_at",
        "created_at");
    assertColumns(
        "harness_model_invocation",
        "id",
        "thread_id",
        "turn_start_entry_id",
        "basis_head_entry_id",
        "request",
        "status",
        "attempt",
        "stream_checkpoint",
        "result",
        "error",
        "result_entry_id",
        "created_at",
        "updated_at");
    assertColumns(
        "harness_tool_invocation",
        "id",
        "model_invocation_id",
        "assistant_entry_id",
        "ordinal",
        "request",
        "status",
        "attempt",
        "approval",
        "result",
        "error",
        "result_entry_id",
        "created_at",
        "updated_at");
  }

  @Test
  void harnessThreadNoLongerExposesLegacyExecutionColumns() throws SQLException {
    for (String legacy :
        new String[] {
          "environment_name",
          "input_sequence",
          "runnable",
          "execution_epoch",
          "processor_token",
          "processor_until"
        }) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "select 1 from information_schema.columns"
                      + " where table_schema = 'public' and table_name = 'harness_thread'"
                      + " and column_name = ?")) {
        ps.setString(1, legacy);
        try (ResultSet rs = ps.executeQuery()) {
          assertFalse(rs.next(), () -> "harness_thread must not expose legacy column " + legacy);
        }
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
    assertColumnType("jsonb", "harness_thread_command", "payload");
    assertColumnType("jsonb", "harness_model_invocation", "request");
    assertColumnType("jsonb", "harness_model_invocation", "stream_checkpoint");
    assertColumnType("jsonb", "harness_model_invocation", "result");
    assertColumnType("jsonb", "harness_model_invocation", "error");
    assertColumnType("jsonb", "harness_tool_invocation", "request");
    assertColumnType("jsonb", "harness_tool_invocation", "approval");
    assertColumnType("jsonb", "harness_tool_invocation", "result");
    assertColumnType("jsonb", "harness_tool_invocation", "error");
    assertColumnType("jsonb", "agent_provider", "config");
    assertColumnType("jsonb", "agent_provider_revision", "config");
    assertColumnType("jsonb", "agent_model", "config");
    assertColumnType("jsonb", "agent_definition", "config");
  }

  @Test
  void noTableStoresByteaFileBlobs() throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name from information_schema.columns"
                    + " where table_schema = 'public' and data_type = 'bytea'")) {
      assertFalse(
          rs.next(),
          "no public table may store bytea file blobs; artifact content lives outside the database");
    }
  }

  @Test
  void usesTimestamptzForTemporalColumns() throws SQLException {
    assertColumnType("timestamp with time zone", "harness_session", "created_at");
    assertColumnType("timestamp with time zone", "harness_entry", "created_at");
    assertColumnType("timestamp with time zone", "harness_thread", "created_at");
    assertColumnType("timestamp with time zone", "harness_thread", "updated_at");
    assertColumnType("timestamp with time zone", "harness_thread_command", "created_at");
    assertColumnType("timestamp with time zone", "harness_model_invocation", "created_at");
    assertColumnType("timestamp with time zone", "harness_model_invocation", "updated_at");
    assertColumnType("timestamp with time zone", "harness_tool_invocation", "created_at");
    assertColumnType("timestamp with time zone", "harness_tool_invocation", "updated_at");
    assertColumnType("timestamp with time zone", "harness_work", "available_at");
    assertColumnType("timestamp with time zone", "harness_work", "lease_until");
    assertColumnType("timestamp with time zone", "agent_provider_revision", "created_at");
  }

  @Test
  void usesNativeBooleanForFlags() throws SQLException {
    assertColumnType("boolean", "harness_thread", "yolo_enabled");
    assertColumnType("boolean", "chat", "yolo_enabled");
  }

  @Test
  void harnessWorkExposesOnlyTheTargetLeaseContract() throws SQLException {
    assertColumns(
        "harness_work",
        "target_type",
        "target_id",
        "available_at",
        "wake_version",
        "lease_token",
        "lease_until");

    // lease_token and lease_until must be set or cleared together.
    assertThrows(SQLException.class, () -> insertWork("THREAD", 920_001L, "token", null));
    assertThrows(
        SQLException.class, () -> insertWork("THREAD", 920_002L, null, "current_timestamp"));
    // lease_token must be non-blank.
    assertThrows(
        SQLException.class, () -> insertWork("THREAD", 920_003L, "   ", "current_timestamp"));
    // target_type and positive id/wake_version are the durable queue identity.
    assertThrows(SQLException.class, () -> insertWork("SESSION", 920_004L, null, null));
    assertThrows(SQLException.class, () -> insertWork("THREAD", 0L, null, null));
    assertThrows(SQLException.class, () -> insertWork("THREAD", 920_005L, null, null, 0L));
    // A fully valid leased row inserts.
    try (Connection conn = newConnection()) {
      insertWork(conn, "TOOL", 920_006L, "worker", "current_timestamp");
    }
  }

  private static void insertWork(
      String targetType, long targetId, String leaseToken, String leaseUntilExpression)
      throws SQLException {
    try (Connection conn = newConnection()) {
      insertWork(conn, targetType, targetId, leaseToken, leaseUntilExpression, 1L);
    }
  }

  private static void insertWork(
      String targetType,
      long targetId,
      String leaseToken,
      String leaseUntilExpression,
      long wakeVersion)
      throws SQLException {
    try (Connection conn = newConnection()) {
      insertWork(conn, targetType, targetId, leaseToken, leaseUntilExpression, wakeVersion);
    }
  }

  private static void insertWork(
      Connection conn, String targetType, long targetId, String leaseToken, String leaseUntil)
      throws SQLException {
    insertWork(conn, targetType, targetId, leaseToken, leaseUntil, 1L);
  }

  private static void insertWork(
      Connection conn,
      String targetType,
      long targetId,
      String leaseToken,
      String leaseUntil,
      long wakeVersion)
      throws SQLException {
    String leaseTokenExpression = leaseToken == null ? "null" : "?";
    String leaseUntilExpression = leaseUntil == null ? "null" : leaseUntil;
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into harness_work (target_type, target_id, available_at, wake_version,"
                + " lease_token, lease_until) values (?, ?, current_timestamp, ?, "
                + leaseTokenExpression
                + ", "
                + leaseUntilExpression
                + ")")) {
      ps.setString(1, targetType);
      ps.setLong(2, targetId);
      ps.setLong(3, wakeVersion);
      int index = 4;
      if (leaseToken != null) {
        ps.setString(index++, leaseToken);
      }
      ps.executeUpdate();
    }
  }

  @Test
  void revisionNotifyTriggerIsTheOnlyHarnessTriggerAndNeverMutatesRevision() throws SQLException {
    // The only user trigger in the whole public schema is the hint-only revision notify.
    Set<String> triggers = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid"
                    + " where c.relnamespace = 'public'::regnamespace and not t.tgisinternal")) {
      while (rs.next()) {
        triggers.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of("trg_harness_thread_revision_notify"),
        triggers,
        "no legacy trigger (revision bump, activation notify, child revision) may remain");

    String definition =
        singleString(
            "select pg_get_triggerdef(oid) from pg_trigger"
                + " where tgname = 'trg_harness_thread_revision_notify'");
    assertTrue(
        definition.contains("AFTER INSERT OR UPDATE OF revision"),
        () -> "trigger must fire after insert or revision update: " + definition);
    assertTrue(
        definition.contains("harness_thread_revision_notify"),
        () -> "trigger must invoke the notify function: " + definition);

    // The trigger function itself is the only place allowed to notify; its body never mutates
    // revision (it only reads NEW/OLD and emits pg_notify).
    String functionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'harness_thread_revision_notify'");
    assertTrue(functionSource.contains("pg_notify"), () -> "notify function must call pg_notify");
    assertTrue(
        functionSource.contains("harness_thread_revision"),
        () -> "notify function must use the harness_thread_revision channel");
    assertTrue(
        !functionSource.contains("revision := ") && !functionSource.contains("revision = revision"),
        () -> "notify function must never mutate revision: " + functionSource);

    // No public functions other than the notify helper: revision bump/child-bump functions are
    // gone.
    Set<String> functions = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select proname from pg_proc"
                    + " where pronamespace = 'public'::regnamespace order by proname")) {
      while (rs.next()) {
        functions.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of("harness_thread_revision_notify"),
        functions,
        "the database must never mutate revision; only the notify helper may exist");

    // Behavior: the trigger fires on INSERT and on a revision write, but never bumps the stored
    // value; non-revision application updates leave revision untouched.
    ThreadFixture thread = ThreadFixture.insertFresh();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_thread set revision = 7 where id = ?")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }
    assertEquals(
        7L,
        singleLong("select revision from harness_thread where id = " + thread.threadId),
        "revision must stay exactly what the application wrote");
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_thread set yolo_enabled = true where id = ?")) {
      ps.setLong(1, thread.threadId);
      assertEquals(1, ps.executeUpdate());
    }
    assertEquals(
        7L,
        singleLong("select revision from harness_thread where id = " + thread.threadId),
        "a non-revision update must not touch revision");
  }

  @Test
  void generatedDurableEntityIdsUseBigint() throws SQLException {
    for (String table : BUSINESS_SEQUENCE_BACKED_TABLES) {
      assertColumnType("bigint", table, "id");
    }
    for (String table : HARNESS_TABLES) {
      if (table.equals("harness_work")) {
        // harness_work identifies targets by (target_type, target_id), not by a generated id.
        continue;
      }
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
  void harnessRuntimeSequenceAllocatesMonotonicIds() throws SQLException {
    long a = singleLong("select nextval('harness_runtime_id_seq')");
    long b = singleLong("select nextval('harness_runtime_id_seq')");
    assertTrue(
        a > 0 && b > a,
        () -> "harness_runtime_id_seq must allocate positive monotonic ids, a=" + a + " b=" + b);
  }

  @Test
  void catalogNamePrimaryKeysDoNotConsumeGeneratedIds() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config) values (?, 'openai',"
                    + " '{}'::jsonb)")) {
      ps.setString(1, "sequence-fixture-" + System.nanoTime());
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select column_default from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'agent_provider'"
                    + " and column_name = 'name'")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getString(1));
      }
    }
  }

  @Test
  void generatedEntityIdsUseOnlyTheDeclaredSequences() throws SQLException {
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
    assertEquals(
        Set.of("kk_studio_id_seq", "harness_runtime_id_seq"),
        sequences,
        "schema must expose exactly the business and harness runtime sequences");

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
        new TreeSet<>(BUSINESS_SEQUENCE_BACKED_TABLES),
        sequenceBackedTables,
        "only business durable ids default from kk_studio_id_seq");

    // Harness ids are allocated by HarnessRuntime from harness_runtime_id_seq and inserted
    // explicitly; none of the execution tables may carry a column default.
    for (String table : HARNESS_TABLES) {
      if (table.equals("harness_work")) {
        // harness_work identifies targets by (target_type, target_id), not by a generated id.
        continue;
      }
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "select column_default from information_schema.columns"
                      + " where table_schema = 'public' and table_name = ? and column_name = 'id'")) {
        ps.setString(1, table);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), () -> "harness table " + table + " must declare an id column");
          assertNull(
              rs.getString(1),
              () -> "harness table " + table + " id must not carry a column default");
        }
      }
    }
  }

  @Test
  void noForeignKeyIsDeferred() throws SQLException {
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
    assertEquals(
        Set.of(),
        deferred,
        "revision and head binding are runtime-owned: no FK needs deferral in the new protocol");
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
            "uk_comfyui_workflow_api_api_name",
            "uk_canvas_node_canvas_id",
            "uk_canvas_link",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_thread_command_sequence",
            "uk_harness_thread_command_client",
            "uk_harness_model_invocation_turn",
            "uk_harness_model_invocation_result",
            "uk_harness_tool_invocation_ordinal",
            "uk_harness_tool_invocation_result"),
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
    String name = "auto-update-probe-" + FIXTURE_IDS.incrementAndGet();
    Timestamp fixedTimestamp = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config, created_at,"
                    + " updated_at) values (?, 'openai', '{}'::jsonb, ?, ?)")) {
      ps.setString(1, name);
      ps.setTimestamp(2, fixedTimestamp);
      ps.setTimestamp(3, fixedTimestamp);
      ps.executeUpdate();
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update agent_provider set description = 'touched' where name = ?")) {
      ps.setString(1, name);
      assertEquals(1, ps.executeUpdate());
    }
    Timestamp postUpdate;
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select updated_at from agent_provider where name = ?")) {
      ps.setString(1, name);
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

  private static String singleString(String sql) throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertTrue(rs.next(), "no row for: " + sql);
      return rs.getString(1);
    }
  }
}
