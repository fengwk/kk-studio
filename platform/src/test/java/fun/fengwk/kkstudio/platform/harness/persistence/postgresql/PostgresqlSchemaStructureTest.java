package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
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
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 断言 PostgreSQL schema 结构：所有必需的表与列类型都存在，harness 执行协议恰好是 infra 的七张表（业务表不得使用 harness_ 前缀），且结构化载荷使用
 * jsonb。
 *
 * <p>public schema 的相等性校验是严格的：{@code public} 中 {@code BASE TABLE} 的集合必须与期望列表完全一致。
 */
class PostgresqlSchemaStructureTest extends PostgresSchemaSupport {

  private static final List<String> EXPECTED_TABLES =
      Arrays.asList(
          "agent_provider",
          "agent_model",
          "agent_definition",
          "flyway_schema_history",
          "comfyui_workflow_api",
          "mcp_server",
          "mcp_tool",
          "canvas_document",
          "canvas_group",
          "canvas_node",
          "canvas_link",
          "canvas_function_run",
          "canvas_resource",
          "canvas_command_dedup",
          "canvas_function_resource_pin",
          "canvas_session",
          "chat",
          "chat_session",
          "environment",
          "environment_connection",
          "environment_inventory",
          "environment_operation",
          "environment_skill",
          "environment_skill_source",
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work",
          "session_blob_ref",
          "storage_blob",
          "storage_upload",
          "system_setting");

  /** 精确的 infra 执行协议七表；业务表（如 session_blob_ref）不得使用 harness_ 前缀。 */
  private static final Set<String> HARNESS_TABLES =
      Set.of(
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work");

  /** Canvas 与 Chat/Comfy 一样完全 UUID：所有持久化实体 id 由应用侧生成，schema 不提供任何序列。 */
  private static final Set<String> CANVAS_UUID_ID_TABLES =
      Set.of("canvas_document", "canvas_group", "canvas_node", "canvas_resource");

  private static final UUID EXPLICIT_CONNECTION_GENERATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

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
  void harnessSchemaContainsExactlyTheRuntimeTables() throws SQLException {
    Set<String> harnessTables = new TreeSet<>();
    for (String table : tableNames()) {
      if (table.startsWith("harness_")) {
        harnessTables.add(table);
      }
    }
    assertEquals(
        new TreeSet<>(HARNESS_TABLES),
        harnessTables,
        "only the infra execution tables may use the harness_ prefix");
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
        "connection_generation_id",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "agent_model",
        "provider_name",
        "name",
        "model_id",
        "description",
        "config",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "agent_definition",
        "name",
        "description",
        "system_prompt",
        "model_provider_name",
        "model_name",
        "variant",
        "environment_id",
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
    assertColumns(
        "mcp_server",
        "id",
        "name",
        "connection_type",
        "environment_id",
        "connection_config",
        "timeout_millis",
        "discovery_status",
        "discovered_version",
        "enabled",
        "created_at",
        "updated_at",
        "version");
    assertColumns(
        "mcp_tool",
        "id",
        "mcp_server_id",
        "source_name",
        "model_name",
        "description",
        "input_schema",
        "schema_revision",
        "available");
    assertColumns("canvas_document", "id", "title", "version", "created_at", "updated_at");
    assertColumns("canvas_group", "id", "canvas_id", "title", "x", "y", "width", "height");
    assertColumns(
        "canvas_node",
        "id",
        "canvas_id",
        "name",
        "x",
        "y",
        "width",
        "height",
        "group_id",
        "model_key",
        "function_config_json");
    assertColumns("canvas_link", "canvas_id", "source_node_id", "target_node_id");
    assertColumns(
        "canvas_function_run",
        "node_id",
        "request_id",
        "status",
        "attempt",
        "available_at",
        "lease_token",
        "lease_until",
        "state_json",
        "error",
        "updated_at",
        "created_at");
    assertColumns(
        "canvas_resource",
        "id",
        "canvas_id",
        "owner_node_id",
        "resource_index",
        "blob_id",
        "name",
        "text_content",
        "created_at");
    assertColumns("canvas_command_dedup", "canvas_id", "idempotency_key", "request_hash");
    assertColumns(
        "canvas_function_resource_pin",
        "canvas_id",
        "node_id",
        "request_id",
        "role",
        "resource_id");
    assertColumns(
        "chat", "id", "title", "agent_name", "yolo_enabled", "created_at", "updated_at", "version");
    assertColumns("chat_session", "session_id", "chat_id", "created_at");
    assertColumns("canvas_session", "session_id", "canvas_id", "created_at");
    assertColumns(
        "storage_blob",
        "id",
        "sha256",
        "size_bytes",
        "media_type",
        "width",
        "height",
        "duration_ms",
        "ref_count",
        "state",
        "created_at",
        "updated_at");
    assertColumns(
        "storage_upload",
        "id",
        "candidate_blob_id",
        "blob_id",
        "filename",
        "declared_media_type",
        "declared_size",
        "declared_sha256",
        "expires_at",
        "cleanup_requested_at",
        "cleanup_token",
        "cleanup_until",
        "created_at");
    assertColumns(
        "environment", "id", "name", "registration_token", "created_at", "updated_at", "version");
    assertColumns(
        "environment_connection",
        "environment_id",
        "owner_node_id",
        "lease_token",
        "status",
        "runtime_info",
        "last_seen_at",
        "lease_until");
    assertColumns(
        "environment_inventory",
        "environment_id",
        "source_set_version",
        "applied_source_set_version",
        "capabilities_version",
        "operating_system",
        "time_zone",
        "note",
        "root_path",
        "owner_node_id",
        "lease_token",
        "reported_at",
        "created_at",
        "updated_at");
    assertColumns(
        "environment_skill_source",
        "source_id",
        "environment_id",
        "source_type",
        "path",
        "default_source",
        "git_url",
        "git_ref",
        "scan_path",
        "version",
        "status",
        "applied_version",
        "applied_revision",
        "diagnostics",
        "last_error_code",
        "last_error_message",
        "last_applied_at",
        "created_at",
        "updated_at");
    assertColumns(
        "environment_skill",
        "environment_id",
        "source_id",
        "name",
        "source_version",
        "description",
        "base_directory",
        "content_revision",
        "discovered_at");
    assertColumns(
        "environment_operation",
        "id",
        "environment_id",
        "resource_type",
        "resource_id",
        "operation_type",
        "status",
        "resource_version",
        "arguments",
        "parameter_summary",
        "deadline_at",
        "owner_node_id",
        "lease_token",
        "started_at",
        "finished_at",
        "result_summary",
        "failure_code",
        "failure_message",
        "created_at",
        "updated_at");
  }

  @Test
  void harnessExecutionTablesExposeExactColumnContracts() throws SQLException {
    assertColumns("harness_session", "id", "name", "created_at");
    assertColumns(
        "harness_entry",
        "id",
        "session_id",
        "parent_entry_id",
        "entry_type",
        "payload",
        "created_at",
        "provider_replay_state");
    assertColumns(
        "harness_thread",
        "id",
        "session_id",
        "head_entry_id",
        "creation_request_hash",
        "name",
        "yolo_enabled",
        "next_command_sequence",
        "version",
        "created_at",
        "updated_at");
    assertColumns(
        "harness_thread_command",
        "thread_id",
        "sequence",
        "command_type",
        "payload",
        "idempotency_key",
        "request_hash",
        "applied_turn_start_entry_id",
        "stop_request_id",
        "cancelled_at",
        "created_at");
    assertColumns(
        "harness_model_invocation",
        "id",
        "thread_id",
        "turn_start_entry_id",
        "request_head_entry_id",
        "request_spec",
        "status",
        "attempt",
        "stream_checkpoint",
        "result",
        "error",
        "result_entry_id",
        "failed_attempts",
        "created_at",
        "updated_at",
        "provider_replay_state");
    assertColumns(
        "harness_tool_invocation",
        "id",
        "model_invocation_id",
        "assistant_entry_id",
        "call_index",
        "call",
        "binding",
        "status",
        "attempt",
        "approval",
        "result",
        "effects",
        "error",
        "created_at",
        "updated_at");
  }

  @Test
  void usesJsonbForStructuredPayloads() throws SQLException {
    assertColumnType("jsonb", "harness_entry", "payload");
    assertColumnType("jsonb", "harness_entry", "provider_replay_state");
    assertColumnType("jsonb", "harness_thread_command", "payload");
    assertColumnType("jsonb", "harness_model_invocation", "request_spec");
    assertColumnType("jsonb", "harness_model_invocation", "stream_checkpoint");
    assertColumnType("jsonb", "harness_model_invocation", "result");
    assertColumnType("jsonb", "harness_model_invocation", "error");
    assertColumnType("jsonb", "harness_model_invocation", "failed_attempts");
    assertColumnType("jsonb", "harness_model_invocation", "provider_replay_state");
    assertColumnType("jsonb", "harness_tool_invocation", "call");
    assertColumnType("jsonb", "harness_tool_invocation", "binding");
    assertColumnType("jsonb", "harness_tool_invocation", "approval");
    assertColumnType("jsonb", "harness_tool_invocation", "result");
    assertColumnType("jsonb", "harness_tool_invocation", "effects");
    assertColumnType("jsonb", "harness_tool_invocation", "error");
    assertColumnType("jsonb", "agent_provider", "config");
    assertColumnType("jsonb", "agent_model", "config");
    assertColumnType("jsonb", "agent_definition", "config");
    assertColumnType("jsonb", "canvas_node", "function_config_json");
    assertColumnType("jsonb", "canvas_function_run", "state_json");
    assertColumnType("jsonb", "environment_connection", "runtime_info");
    assertColumnType("jsonb", "environment_skill_source", "diagnostics");
    assertColumnType("jsonb", "environment_operation", "arguments");
    assertColumnType("jsonb", "environment_operation", "parameter_summary");
    assertColumnType("jsonb", "environment_operation", "result_summary");
    // V4 表不得存放任何文件正文：Skill 正文只存在于 Daemon 的不可变 blob 中。
    assertColumnType("character", "environment_skill", "content_revision");
    assertColumnType("integer", "canvas_function_run", "attempt");
    assertColumnType("character varying", "canvas_function_run", "lease_token");
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
    assertColumnType("timestamp with time zone", "canvas_document", "created_at");
    assertColumnType("timestamp with time zone", "canvas_document", "updated_at");
    assertColumnType("timestamp with time zone", "canvas_resource", "created_at");
    assertColumnType("timestamp with time zone", "chat_session", "created_at");
    assertColumnType("timestamp with time zone", "canvas_session", "created_at");
    assertColumnType("timestamp with time zone", "canvas_function_run", "updated_at");
    assertColumnType("timestamp with time zone", "canvas_function_run", "available_at");
    assertColumnType("timestamp with time zone", "canvas_function_run", "lease_until");
    assertColumnType("timestamp with time zone", "canvas_function_run", "created_at");
    assertEquals(
        128L,
        singleLong(
            "select character_maximum_length from information_schema.columns"
                + " where table_schema = 'public' and table_name = 'canvas_function_run'"
                + " and column_name = 'lease_token'"));
    assertColumnType("timestamp with time zone", "storage_blob", "created_at");
    assertColumnType("timestamp with time zone", "storage_blob", "updated_at");
    assertColumnType("timestamp with time zone", "storage_upload", "expires_at");
    assertColumnType("timestamp with time zone", "storage_upload", "cleanup_requested_at");
    assertColumnType("timestamp with time zone", "storage_upload", "created_at");
    assertColumnType("timestamp with time zone", "environment", "created_at");
    assertColumnType("timestamp with time zone", "environment", "updated_at");
    assertColumnType("timestamp with time zone", "environment_connection", "last_seen_at");
    assertColumnType("timestamp with time zone", "environment_connection", "lease_until");
    assertColumnType("timestamp with time zone", "environment_inventory", "reported_at");
    assertColumnType("timestamp with time zone", "environment_inventory", "created_at");
    assertColumnType("timestamp with time zone", "environment_inventory", "updated_at");
    assertColumnType("timestamp with time zone", "environment_skill_source", "last_applied_at");
    assertColumnType("timestamp with time zone", "environment_skill_source", "created_at");
    assertColumnType("timestamp with time zone", "environment_skill_source", "updated_at");
    assertColumnType("timestamp with time zone", "environment_skill", "discovered_at");
    assertColumnType("timestamp with time zone", "environment_operation", "deadline_at");
    assertColumnType("timestamp with time zone", "environment_operation", "started_at");
    assertColumnType("timestamp with time zone", "environment_operation", "finished_at");
    assertColumnType("timestamp with time zone", "environment_operation", "created_at");
    assertColumnType("timestamp with time zone", "environment_operation", "updated_at");
  }

  @Test
  void usesNativeBooleanForFlags() throws SQLException {
    assertColumnType("boolean", "harness_thread", "yolo_enabled");
    assertColumnType("boolean", "chat", "yolo_enabled");
    assertColumnType("boolean", "environment_skill_source", "default_source");
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
        "lease_until",
        "required_environment_id");

    assertColumnType("uuid", "harness_work", "required_environment_id");

    // lease_token 与 lease_until 必须同时被设置或清空。
    assertThrows(SQLException.class, () -> insertWork("THREAD", uuid(920_001L), "token", null));
    assertThrows(
        SQLException.class, () -> insertWork("THREAD", uuid(920_002L), null, "current_timestamp"));
    // lease_token 不得为空。
    assertThrows(
        SQLException.class, () -> insertWork("THREAD", uuid(920_003L), "   ", "current_timestamp"));
    // target_type 与 wake_version 是持久化队列标识；(target_type, target_id) 主键拒绝重复目标。
    assertThrows(SQLException.class, () -> insertWork("SESSION", uuid(920_004L), null, null));
    assertThrows(SQLException.class, () -> insertWork("THREAD", uuid(920_005L), null, null, 0L));
    try (Connection conn = newConnection()) {
      insertWork(conn, "TOOL", uuid(920_006L), "worker", "current_timestamp");
      assertThrows(SQLException.class, () -> insertWork(conn, "TOOL", uuid(920_006L), null, null));
    }
    // 完全合法的 leased 行可插入。
    try (Connection conn = newConnection()) {
      insertWork(conn, "TOOL", uuid(920_007L), "worker", "current_timestamp");
    }

    // required_environment_id 仅允许 target_type='TOOL' 时非空
    UUID envId = uuid(888_001L);
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment (id, name, registration_token) values (?, 'work-env', 'tok-work')")) {
        ps.setObject(1, envId);
        ps.executeUpdate();
      }
      assertTransactionConstraintViolation(
          conn,
          "ck_harness_work_required_environment",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into harness_work (target_type, target_id, available_at, wake_version, required_environment_id) values ('THREAD', ?, current_timestamp, 1, ?)")) {
              ps.setObject(1, uuid(920_008L));
              ps.setObject(2, envId);
              ps.executeUpdate();
            }
          });
      // TOOL target_type 携带合法 required_environment_id 成功插入
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into harness_work (target_type, target_id, available_at, wake_version, required_environment_id) values ('TOOL', ?, current_timestamp, 1, ?)")) {
        ps.setObject(1, uuid(920_010L));
        ps.setObject(2, envId);
        ps.executeUpdate();
      }
    }
  }

  /** system_setting 单行契约：唯一 id=1、config 必须为 object、version 非负，且 baseline 默认行与版本 CAS 语义正确。 */
  @Test
  void systemSettingsTableIsSingletonAndValidated() throws SQLException {
    assertColumns("system_setting", "id", "config", "version", "created_at", "updated_at");
    assertColumnType("jsonb", "system_setting", "config");
    assertColumnType("timestamp with time zone", "system_setting", "created_at");
    assertColumnType("timestamp with time zone", "system_setting", "updated_at");

    // baseline 默认行：恰好一行 id=1、version=0，config 是完整聚合对象。
    assertEquals(1L, singleLong("select count(*) from system_setting"));
    assertEquals(1L, singleLong("select id from system_setting"));
    assertEquals(0L, singleLong("select version from system_setting"));
    assertTrue(
        singleString("select config from system_setting").contains("\"aiRuntime\""),
        "default config must contain the aiRuntime section");

    // id=1 之外的任何行都被 schema check 拒绝。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_system_setting_id", () -> insertSystemSetting(conn, 2L, "{}", 0L));
    }
    // config 必须是 JSON object：删除默认行并插入数组版本，两者在同一事务内一并回滚，保证默认行仍存在。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_system_setting_config_object",
          () -> {
            deleteDefaultRow(conn);
            insertSystemSetting(conn, 1L, "[]", 0L);
          });
    }
    // version 必须非负：同样与删除同事务回滚。
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_system_setting_version_nonneg",
          () -> {
            deleteDefaultRow(conn);
            insertSystemSetting(conn, 1L, "{}", -1L);
          });
    }
    // 约束用例的事务回滚后默认行必须仍在，CAS 语义：只有 version 匹配才更新并 +1。
    assertEquals(1L, singleLong("select count(*) from system_setting"));
    try (Connection conn = newConnection()) {
      assertEquals(1, updateSystemSetting(conn, 0L));
      assertEquals(0, updateSystemSetting(conn, 0L));
    }
    assertEquals(1L, singleLong("select version from system_setting"));
  }

  @Test
  void liveEnvironmentTableConstraintsAreEnforced() throws SQLException {
    UUID env1 = uuid(101L);
    UUID node1 = uuid(201L);
    UUID token1 = uuid(301L);

    // 正常 CONNECTING 行可插入
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment (id, name, registration_token) values (?, 'dev', 'tok1')")) {
        ps.setObject(1, env1);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                  + "values (?, ?, ?, 'CONNECTING', null, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
        ps.setObject(1, env1);
        ps.setObject(2, node1);
        ps.setObject(3, token1);
        assertEquals(1, ps.executeUpdate());
      }
    }

    // 拒绝非法 status
    UUID env2 = uuid(102L);
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment (id, name, registration_token) values (?, 'prod', 'tok2')")) {
        ps.setObject(1, env2);
        ps.executeUpdate();
      }
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_connection_status",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                        + "values (?, ?, ?, 'INVALID', null, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
              ps.setObject(1, env2);
              ps.setObject(2, node1);
              ps.setObject(3, token1);
              ps.executeUpdate();
            }
          });
    }

    // CONNECTING 状态下 runtime_info 必须为 null
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_connection_runtime_info",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                        + "values (?, ?, ?, 'CONNECTING', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
              ps.setObject(1, env2);
              ps.setObject(2, node1);
              ps.setObject(3, token1);
              ps.executeUpdate();
            }
          });
    }

    // READY 状态下 runtime_info 必须非 null object
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_connection_runtime_info",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                        + "values (?, ?, ?, 'READY', null, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
              ps.setObject(1, env2);
              ps.setObject(2, node1);
              ps.setObject(3, token1);
              ps.executeUpdate();
            }
          });
    }

    // lease_until 必须晚于 last_seen_at
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_connection_lease",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                        + "values (?, ?, ?, 'CONNECTING', null, statement_timestamp(), statement_timestamp() - interval '1 second')")) {
              ps.setObject(1, env2);
              ps.setObject(2, node1);
              ps.setObject(3, token1);
              ps.executeUpdate();
            }
          });
    }
  }

  private static void insertSystemSetting(Connection conn, long id, String configJson, long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into system_setting (id, config, version) values (?, ?::jsonb, ?)")) {
      ps.setLong(1, id);
      ps.setString(2, configJson);
      ps.setLong(3, version);
      ps.executeUpdate();
    }
  }

  private static void deleteDefaultRow(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("delete from system_setting where id = 1");
    }
  }

  private static int updateSystemSetting(Connection conn, long expectedVersion)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "update system_setting set config = '{}'::jsonb, version = version + 1"
                + " where id = 1 and version = ?")) {
      ps.setLong(1, expectedVersion);
      return ps.executeUpdate();
    }
  }

  private static UUID uuid(long value) {
    return new UUID(0L, value);
  }

  private static void insertWork(
      String targetType, UUID targetId, String leaseToken, String leaseUntilExpression)
      throws SQLException {
    try (Connection conn = newConnection()) {
      insertWork(conn, targetType, targetId, leaseToken, leaseUntilExpression, 1L);
    }
  }

  private static void insertWork(
      String targetType,
      UUID targetId,
      String leaseToken,
      String leaseUntilExpression,
      long wakeVersion)
      throws SQLException {
    try (Connection conn = newConnection()) {
      insertWork(conn, targetType, targetId, leaseToken, leaseUntilExpression, wakeVersion);
    }
  }

  private static void insertWork(
      Connection conn, String targetType, UUID targetId, String leaseToken, String leaseUntil)
      throws SQLException {
    insertWork(conn, targetType, targetId, leaseToken, leaseUntil, 1L);
  }

  private static void insertWork(
      Connection conn,
      String targetType,
      UUID targetId,
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
      ps.setObject(2, targetId);
      ps.setLong(3, wakeVersion);
      int index = 4;
      if (leaseToken != null) {
        ps.setString(index++, leaseToken);
      }
      ps.executeUpdate();
    }
  }

  @Test
  void versionNotifyTriggersAreExactAndNeverMutateVersions() throws SQLException {
    // 整个 public schema 中仅有四个用户 trigger：三个 version hint 与 Canvas Function work hint。
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
        Set.of(
            "trg_system_setting_version_notify",
            "trg_harness_thread_version_notify",
            "trg_canvas_document_version_notify",
            "trg_canvas_function_work_notify"),
        triggers,
        "public triggers must equal the exact set of user triggers");

    String systemSettingsDefinition =
        singleString(
            "select pg_get_triggerdef(oid) from pg_trigger"
                + " where tgname = 'trg_system_setting_version_notify'");
    assertTrue(
        systemSettingsDefinition.contains("AFTER INSERT OR UPDATE OF version"),
        () ->
            "system settings trigger must fire after insert or version update: "
                + systemSettingsDefinition);
    assertTrue(
        systemSettingsDefinition.contains("system_setting_version_notify"),
        () ->
            "system settings trigger must invoke the notify function: " + systemSettingsDefinition);

    String definition =
        singleString(
            "select pg_get_triggerdef(oid) from pg_trigger"
                + " where tgname = 'trg_harness_thread_version_notify'");
    assertTrue(
        definition.contains("AFTER INSERT OR UPDATE OF version"),
        () -> "trigger must fire after insert or version update: " + definition);
    assertTrue(
        definition.contains("harness_thread_version_notify"),
        () -> "trigger must invoke the notify function: " + definition);

    // notify 函数本身是唯一允许执行 notify 的地方；其函数体从不修改
    // version（只读取 NEW/OLD 并发出 pg_notify）。
    String functionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'harness_thread_version_notify'");
    assertTrue(functionSource.contains("pg_notify"), () -> "notify function must call pg_notify");
    assertTrue(
        functionSource.contains("harness_thread_version"),
        () -> "notify function must use the harness_thread_version channel");
    assertTrue(
        functionSource.contains("new.id::text || ':' || new.version::text"),
        () -> "thread notify payload must be the strict threadId:version text: " + functionSource);
    assertTrue(
        !functionSource.contains("version := ") && !functionSource.contains("version = version"),
        () -> "notify function must never mutate version: " + functionSource);

    // system settings version hint 触发器只发送 NEW.version，不替应用自增或改写版本。
    String systemSettingsFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'system_setting_version_notify'");
    assertTrue(
        systemSettingsFunctionSource.contains("pg_notify"),
        () -> "system settings notify function must call pg_notify");
    assertTrue(
        systemSettingsFunctionSource.contains("system_settings_changed"),
        () -> "system settings notify function must use the system_settings_changed channel");
    assertTrue(
        systemSettingsFunctionSource.contains("new.version::text"),
        () ->
            "system settings notify payload must be NEW.version text: "
                + systemSettingsFunctionSource);
    assertTrue(
        !systemSettingsFunctionSource.contains("version := ")
            && !systemSettingsFunctionSource.contains("version = version"),
        () ->
            "system settings notify function must never mutate version: "
                + systemSettingsFunctionSource);

    // canvas version hint 触发器同样只做 NOTIFY，永不写版本。
    String canvasDefinition =
        singleString(
            "select pg_get_triggerdef(oid) from pg_trigger"
                + " where tgname = 'trg_canvas_document_version_notify'");
    assertTrue(
        canvasDefinition.contains("AFTER INSERT OR UPDATE OF version"),
        () -> "canvas trigger must fire after insert or version update: " + canvasDefinition);
    String canvasFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'canvas_document_version_notify'");
    assertTrue(
        canvasFunctionSource.contains("pg_notify"),
        () -> "canvas notify function must call pg_notify");
    assertTrue(
        canvasFunctionSource.contains("canvas_version"),
        () -> "canvas notify function must use the canvas_version channel");

    String canvasWorkFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'canvas_function_work_notify'");
    assertTrue(canvasWorkFunctionSource.contains("pg_notify"));
    assertTrue(canvasWorkFunctionSource.contains("canvas_function_work"));
    assertTrue(canvasWorkFunctionSource.contains("new.status = 'READY'"));

    // 除四个 notify 辅助函数外不存在其它 public 函数：version 自增函数已移除。
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
        Set.of(
            "system_setting_version_notify",
            "harness_thread_version_notify",
            "canvas_document_version_notify",
            "canvas_function_work_notify"),
        functions,
        "the database must never mutate version; only the notify helpers may exist");

    // 行为：trigger 在 INSERT 与 version 写入时触发，但绝不修改存储的
    // version 值；非 version 的应用层更新则完全不会动到 version。
    UUID threadId = uuid(700L);
    UUID sessionId = uuid(701L);
    UUID rootEntryId = uuid(702L);
    try (Connection conn = newConnection()) {
      insertThreadRow(conn, threadId, sessionId, rootEntryId);
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_thread set version = 7 where id = ?")) {
      ps.setObject(1, threadId);
      assertEquals(1, ps.executeUpdate());
    }
    assertEquals(
        7L,
        singleLong("select version from harness_thread where id = '" + threadId + "'"),
        "version must stay exactly what the application wrote");
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_thread set yolo_enabled = true where id = ?")) {
      ps.setObject(1, threadId);
      assertEquals(1, ps.executeUpdate());
    }
    assertEquals(
        7L,
        singleLong("select version from harness_thread where id = '" + threadId + "'"),
        "a non-version update must not touch version");
  }

  /** 插入一个最小合法 Thread 行：Session -> ROOT Entry -> Thread。 */
  private static void insertThreadRow(Connection conn, UUID threadId, UUID sessionId, UUID entryId)
      throws SQLException {
    try (PreparedStatement session =
            conn.prepareStatement(
                "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)");
        PreparedStatement entry =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
                    + " values (?, ?, 'ROOT', '{}'::jsonb, current_timestamp)");
        PreparedStatement thread =
            conn.prepareStatement(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
                    + " name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '"
                    + "0".repeat(64)
                    + "', 'schema-structure-test-thread', false, 1, 0, current_timestamp, current_timestamp)")) {
      session.setObject(1, sessionId);
      session.setObject(2, "schema-structure-test-session");
      assertEquals(1, session.executeUpdate());
      entry.setObject(1, entryId);
      entry.setObject(2, sessionId);
      assertEquals(1, entry.executeUpdate());
      thread.setObject(1, threadId);
      thread.setObject(2, sessionId);
      thread.setObject(3, entryId);
      assertEquals(1, thread.executeUpdate());
    }
  }

  @Test
  void generatedDurableEntityIdsUseUuidAndNoBusinessSequence() throws SQLException {
    for (String table : CANVAS_UUID_ID_TABLES) {
      assertColumnType("uuid", table, "id");
    }
    assertColumnType("uuid", "canvas_function_run", "node_id");
    assertColumnType("uuid", "canvas_function_run", "request_id");
    assertColumnType("uuid", "canvas_link", "source_node_id");
    assertColumnType("uuid", "canvas_link", "target_node_id");
    assertColumnType("uuid", "canvas_function_resource_pin", "resource_id");
    for (String table : HARNESS_TABLES) {
      if (table.equals("harness_work") || table.equals("harness_thread_command")) {
        // harness_work 通过 (target_type, target_id) 标识目标；ThreadCommand 身份为 (thread_id, sequence)。
        continue;
      }
      assertColumnType("uuid", table, "id");
    }
    // Session blob 引用是复合主键 (session_id, blob_id) 的纯关联表，无代理 id。
    assertColumnType("uuid", "session_blob_ref", "session_id");
    assertColumnType("uuid", "session_blob_ref", "blob_id");
    assertColumnType("uuid", "chat", "id");
    assertColumnType("uuid", "mcp_server", "id");
    assertColumnType("uuid", "mcp_server", "environment_id");
    assertColumnType("jsonb", "mcp_server", "connection_config");
    assertColumnType("uuid", "mcp_tool", "id");
    assertColumnType("uuid", "mcp_tool", "mcp_server_id");
    assertColumnType("jsonb", "mcp_tool", "input_schema");
    assertColumnType("uuid", "comfyui_workflow_api", "id");
    assertColumnType("uuid", "chat_session", "session_id");
    assertColumnType("uuid", "chat_session", "chat_id");
    assertColumnType("uuid", "canvas_session", "session_id");
    assertColumnType("uuid", "canvas_session", "canvas_id");
    assertColumnType("uuid", "environment_inventory", "environment_id");
    assertColumnType("uuid", "environment_skill_source", "source_id");
    assertColumnType("uuid", "environment_skill_source", "environment_id");
    assertColumnType("uuid", "environment_skill", "environment_id");
    assertColumnType("uuid", "environment_skill", "source_id");
    assertColumnType("uuid", "environment_operation", "id");
    assertColumnType("uuid", "environment_operation", "environment_id");
    assertColumnType("uuid", "environment_operation", "resource_id");
  }

  @Test
  void agentProviderConnectionGenerationIdStructureInvariants() throws SQLException {
    // 验证 agent_provider.connection_generation_id 必须为 uuid、NOT NULL、且无任何数据库端 DEFAULT 表达式
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select data_type, is_nullable, column_default from information_schema.columns"
                    + " where table_schema = 'public' and table_name = 'agent_provider'"
                    + " and column_name = 'connection_generation_id'")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "agent_provider.connection_generation_id column must exist");
        assertEquals("uuid", rs.getString("data_type"), "data_type must be uuid");
        assertEquals(
            "NO", rs.getString("is_nullable"), "connection_generation_id must be NOT NULL");
        assertNull(rs.getString("column_default"), "connection_generation_id must have NO DEFAULT");
      }
    }
  }

  @Test
  void publicSchemaExposesNoSequences() throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select sequence_name from information_schema.sequences"
                    + " where sequence_schema = 'public'")) {
      assertFalse(rs.next(), "Canvas 与 Harness 实体 id 全部由应用侧 Supplier<UUID> 生成，schema 不得提供任何序列");
    }
  }

  @Test
  void harnessSchemaExposesNoSequences() throws SQLException {
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select sequence_name from information_schema.sequences"
                    + " where sequence_schema = 'public' and sequence_name like 'harness\\_%'")) {
      assertFalse(
          rs.next(), "Harness 实体 id 全部由注入的 Supplier<UUID> 生成（生产：UUID::randomUUID），schema 不得提供任何序列");
    }
  }

  @Test
  void catalogNamePrimaryKeysDoNotConsumeGeneratedIds() throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config, connection_generation_id) values (?, 'openai',"
                    + " '{}'::jsonb, ?)")) {
      ps.setString(1, "sequence-fixture-" + System.nanoTime());
      ps.setObject(2, EXPLICIT_CONNECTION_GENERATION_ID);
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
  void noColumnCarriesASequenceDefault() throws SQLException {
    // Canvas / Harness / Chat / Comfy 实体 id 均由应用侧 UUID 生成并显式插入；
    // 任何表都不得携带序列默认值。ThreadCommand 无代理主键（身份为 (thread_id, sequence)）。
    for (String table : HARNESS_TABLES) {
      if (table.equals("harness_work") || table.equals("harness_thread_command")) {
        // harness_work 通过 (target_type, target_id) 标识目标；ThreadCommand 无代理主键。
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
    for (String table : CANVAS_UUID_ID_TABLES) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "select column_default from information_schema.columns"
                      + " where table_schema = 'public' and table_name = ? and column_name = 'id'")) {
        ps.setString(1, table);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), () -> "canvas table " + table + " must declare an id column");
          assertNull(
              rs.getString(1),
              () -> "canvas table " + table + " id must not carry a column default");
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
        "version and head binding are runtime-owned: no FK needs deferral in the new protocol");
  }

  @Test
  void catalogRowsAreHardDeletedAndNamesAreReusable() throws SQLException {
    // 硬删除契约：DELETE 物理移除行，主键释放后同名立即可重建。
    String name = "hard-delete-probe-" + FIXTURE_IDS.incrementAndGet();
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config, connection_generation_id) values (?,"
                    + " 'openai', '{}'::jsonb, ?)")) {
      ps.setString(1, name);
      ps.setObject(2, EXPLICIT_CONNECTION_GENERATION_ID);
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("delete from agent_provider where name = ? and version = 0")) {
      ps.setString(1, name);
      assertEquals(1, ps.executeUpdate(), "hard delete must remove exactly the expected row");
    }
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("select count(*) from agent_provider where name = ?")) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(0L, rs.getLong(1), "deleted row must be physically gone");
      }
    }
    // 同名重建：硬删除后主键已经释放。
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config, connection_generation_id) values (?,"
                    + " 'openai', '{}'::jsonb, ?)")) {
      ps.setString(1, name);
      ps.setObject(2, EXPLICIT_CONNECTION_GENERATION_ID);
      assertEquals(1, ps.executeUpdate(), "same-name re-create must succeed after hard delete");
    }
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
            "uk_canvas_function_run_request",
            "uk_canvas_group_canvas",
            "uk_canvas_node_canvas",
            "uk_canvas_resource_canvas",
            "uk_canvas_resource_owner_index",
            "uk_environment_name",
            "uk_environment_registration_token",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_thread_command_idempotency",
            "uk_harness_model_invocation_turn",
            "uk_harness_model_invocation_result",
            "uk_harness_tool_invocation_call_index",
            "uk_storage_blob_active_hash",
            "uk_storage_upload_candidate",
            "uk_mcp_server_name",
            "uk_mcp_tool_model_name",
            "uk_mcp_tool_server_source_name",
            "uk_environment_skill_source_environment",
            "uk_environment_skill_source_default",
            "uk_environment_skill_environment_name",
            "uk_environment_operation_active"),
        indexes,
        "the final schema must expose only its declared domain unique keys");

    Set<String> foreignKeys = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname from pg_constraint where contype = 'f'"
                    + " and conname in ('fk_agent_model_provider',"
                    + " 'fk_agent_definition_model', 'fk_agent_definition_environment',"
                    + " 'fk_environment_connection_environment',"
                    + " 'fk_harness_work_environment',"
                    + " 'fk_canvas_group_canvas',"
                    + " 'fk_canvas_node_canvas', 'fk_canvas_node_group',"
                    + " 'fk_canvas_resource_canvas', 'fk_canvas_resource_owner',"
                    + " 'fk_canvas_resource_blob', 'fk_canvas_command_dedup_canvas',"
                    + " 'fk_canvas_link_source',"
                    + " 'fk_canvas_link_target',"
                    + " 'fk_canvas_function_run_node',"
                    + " 'fk_canvas_function_resource_pin_node',"
                    + " 'fk_chat_session_chat', 'fk_chat_session_session',"
                    + " 'fk_canvas_session_canvas', 'fk_canvas_session_session', 'fk_mcp_tool_server',"
                    + " 'fk_mcp_server_environment',"
                    + " 'fk_environment_inventory_environment',"
                    + " 'fk_environment_skill_source_environment',"
                    + " 'fk_environment_skill_source',"
                    + " 'fk_environment_operation_environment')")) {
      while (rs.next()) {
        foreignKeys.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "fk_agent_model_provider",
            "fk_agent_definition_model",
            "fk_agent_definition_environment",
            "fk_environment_connection_environment",
            "fk_harness_work_environment",
            "fk_canvas_group_canvas",
            "fk_canvas_node_canvas",
            "fk_canvas_node_group",
            "fk_canvas_resource_canvas",
            "fk_canvas_resource_owner",
            "fk_canvas_resource_blob",
            "fk_canvas_command_dedup_canvas",
            "fk_canvas_link_source",
            "fk_canvas_link_target",
            "fk_canvas_function_run_node",
            "fk_canvas_function_resource_pin_node",
            "fk_chat_session_chat",
            "fk_chat_session_session",
            "fk_canvas_session_canvas",
            "fk_canvas_session_session",
            "fk_mcp_tool_server",
            "fk_mcp_server_environment",
            "fk_environment_inventory_environment",
            "fk_environment_skill_source_environment",
            "fk_environment_skill_source",
            "fk_environment_operation_environment"),
        foreignKeys,
        "all non-Harness ownership relations must be enforced by PostgreSQL");
  }

  @Test
  void sessionBlobRefExposesCompositeKeyAndRestrictForeignKeys() throws SQLException {
    // Session blob 引用是纯关联表：复合主键 (session_id, blob_id) 精确存在，两个 FK 都是 RESTRICT。
    Set<String> primaryKeys = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select a.attname from pg_index i"
                    + " join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
                    + " where i.indrelid = 'session_blob_ref'::regclass and i.indisprimary")) {
      while (rs.next()) {
        primaryKeys.add(rs.getString(1));
      }
    }
    assertEquals(Set.of("blob_id", "session_id"), primaryKeys);
    assertEquals(
        "RESTRICT",
        singleString(
            "select case confdeltype when 'r' then 'RESTRICT' when 'a' then 'NO ACTION'"
                + " when 'c' then 'CASCADE' when 'n' then 'SET NULL' else 'SET DEFAULT' end"
                + " from pg_constraint where conname = 'fk_session_blob_ref_session'"));
    assertEquals(
        "RESTRICT",
        singleString(
            "select case confdeltype when 'r' then 'RESTRICT' when 'a' then 'NO ACTION'"
                + " when 'c' then 'CASCADE' when 'n' then 'SET NULL' else 'SET DEFAULT' end"
                + " from pg_constraint where conname = 'fk_session_blob_ref_blob'"));

    // 反向枚举索引 (blob_id, session_id) 精确存在，深删除与对账可以按 blob 反查 Session。
    String blobIndex =
        singleString(
            "select indexdef from pg_indexes"
                + " where schemaname = 'public' and indexname = 'idx_session_blob_ref_blob'");
    assertTrue(
        blobIndex.contains("(blob_id, session_id)"),
        () -> "blob reverse-lookup index must cover (blob_id, session_id): " + blobIndex);
  }

  /**
   * V4 Skill 表的结构契约：复合主键/复合 FK、级联删除、每环境至多一个缺省来源， 以及 environment_operation.resource_id 故意不建
   * FK（资源删除后历史必须保留）。
   */
  @Test
  void skillSourceTablesExposeCompositeKeysAndDeliberateCascades() throws SQLException {
    // environment_skill 的身份是 (source_id, name)，Environment 内名称唯一。
    Set<String> skillPrimaryKeys = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select a.attname from pg_index i"
                    + " join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
                    + " where i.indrelid = 'environment_skill'::regclass and i.indisprimary")) {
      while (rs.next()) {
        skillPrimaryKeys.add(rs.getString(1));
      }
    }
    assertEquals(Set.of("source_id", "name"), skillPrimaryKeys);

    // 复合 FK 指向来源配置，并且确实级联删除。
    assertEquals(
        "CASCADE",
        singleString(
            "select case confdeltype when 'r' then 'RESTRICT' when 'a' then 'NO ACTION'"
                + " when 'c' then 'CASCADE' when 'n' then 'SET NULL' else 'SET DEFAULT' end"
                + " from pg_constraint where conname = 'fk_environment_skill_source'"));
    // FK 的本地列必须正好是 (environment_id, source_id)：只指向 environment 无法保证来源同属该 Environment。
    assertEquals(
        "environment_id,source_id",
        singleString(
            "select string_agg(attribute.attname, ',' order by key.ordinality)"
                + " from pg_constraint as constraint_"
                + " cross join lateral unnest(constraint_.conkey) with ordinality"
                + " as key(attnum, ordinality)"
                + " join pg_attribute as attribute"
                + " on attribute.attrelid = constraint_.conrelid and attribute.attnum = key.attnum"
                + " where constraint_.conname = 'fk_environment_skill_source'"));
    assertEquals(
        "environment_id,source_id",
        singleString(
            "select string_agg(attribute.attname, ',' order by key.ordinality)"
                + " from pg_constraint as constraint_"
                + " cross join lateral unnest(constraint_.confkey) with ordinality"
                + " as key(attnum, ordinality)"
                + " join pg_attribute as attribute"
                + " on attribute.attrelid = constraint_.confrelid and attribute.attnum = key.attnum"
                + " where constraint_.conname = 'fk_environment_skill_source'"));

    // 每个 Environment 至多一个缺省来源：partial unique index 精确存在。
    String defaultIndex =
        singleString(
            "select indexdef from pg_indexes"
                + " where schemaname = 'public' and indexname = 'uk_environment_skill_source_default'");
    assertTrue(
        defaultIndex.contains("(environment_id)") && defaultIndex.contains("WHERE default_source"),
        () -> "default source uniqueness must be a partial unique index: " + defaultIndex);

    // 未终结操作唯一性：按 (environment_id, resource_type, resource_id) 且只约束 PENDING/RUNNING。
    String activeIndex =
        singleString(
            "select indexdef from pg_indexes"
                + " where schemaname = 'public' and indexname = 'uk_environment_operation_active'");
    assertTrue(
        activeIndex.contains("(environment_id, resource_type, resource_id)")
            && activeIndex.contains("'PENDING'")
            && activeIndex.contains("'RUNNING'"),
        () -> "active operation uniqueness must be partial over PENDING/RUNNING: " + activeIndex);
    String claimIndex =
        singleString(
            "select indexdef from pg_indexes"
                + " where schemaname = 'public' and indexname = 'idx_environment_operation_claim'");
    assertTrue(
        claimIndex.contains("(status, deadline_at, environment_id)")
            && claimIndex.contains("WHERE"),
        () -> "claim index must be partial over PENDING: " + claimIndex);

    // operation.resource_id 故意没有外键：删除资源不得抹掉操作历史。
    assertEquals(
        0L,
        singleLong(
            "select count(*) from pg_constraint as constraint_"
                + " cross join lateral unnest(constraint_.conkey) as key(attnum)"
                + " join pg_attribute as attribute"
                + " on attribute.attrelid = constraint_.conrelid and attribute.attnum = key.attnum"
                + " where constraint_.contype = 'f'"
                + " and constraint_.conrelid = 'environment_operation'::regclass"
                + " and attribute.attname = 'resource_id'"));

    // 级联删除行为：删除来源行会移除其持久 Skill inventory，但不影响 Environment 本身。
    UUID environmentId = uuid(970_001L);
    UUID sourceId = uuid(970_002L);
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into environment (id, name, registration_token) values (?, ?, ?)")) {
      ps.setObject(1, environmentId);
      ps.setString(2, "skill-cascade-env-" + FIXTURE_IDS.incrementAndGet());
      ps.setString(3, "skill-cascade-token");
      assertEquals(1, ps.executeUpdate());
    }
    try (Connection conn = newConnection()) {
      insertSkillSource(conn, sourceId, environmentId, "path", "/skills", false, 1L, "READY", 1L);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment_skill (environment_id, source_id, name, source_version,"
                  + " description, base_directory, content_revision, discovered_at)"
                  + " values (?, ?, 'dev', 1, 'Dev rules', '/skills/dev', ?, current_timestamp)")) {
        ps.setObject(1, environmentId);
        ps.setObject(2, sourceId);
        ps.setString(3, "a".repeat(64));
        assertEquals(1, ps.executeUpdate());
      }
    }
    assertEquals(
        1L,
        singleLong("select count(*) from environment_skill where source_id = '" + sourceId + "'"));
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("delete from environment_skill_source where source_id = ?")) {
      ps.setObject(1, sourceId);
      assertEquals(1, ps.executeUpdate(), "source deletion must be allowed");
    }
    assertEquals(
        0L,
        singleLong("select count(*) from environment_skill where source_id = '" + sourceId + "'"),
        "deleting a source must cascade its latest durable skills");
  }

  /**
   * 插入一行合法的来源配置；供 V4 结构测试复用。
   *
   * <p>被应用三元组用数据库时间填充：{@code created_at} 也是数据库时间，用 JVM 时钟可能比它早若干微秒并撞上 {@code
   * ck_environment_skill_source_time_order}。
   */
  private static void insertSkillSource(
      Connection conn,
      UUID sourceId,
      UUID environmentId,
      String sourceType,
      String path,
      boolean defaultSource,
      long version,
      String status,
      Long appliedVersion)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into environment_skill_source (source_id, environment_id, source_type, path,"
                + " default_source, version, status, applied_version, applied_revision,"
                + " last_applied_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " case when ?::bigint is null then null else current_timestamp end)")) {
      ps.setObject(1, sourceId);
      ps.setObject(2, environmentId);
      ps.setString(3, sourceType);
      ps.setString(4, path);
      ps.setBoolean(5, defaultSource);
      ps.setLong(6, version);
      ps.setString(7, status);
      if (appliedVersion == null) {
        ps.setNull(8, Types.BIGINT);
        ps.setNull(9, Types.VARCHAR);
      } else {
        ps.setLong(8, appliedVersion);
        ps.setString(9, "a".repeat(40));
      }
      if (appliedVersion == null) {
        ps.setNull(10, Types.BIGINT);
      } else {
        ps.setLong(10, appliedVersion);
      }
      ps.executeUpdate();
    }
  }

  @Test
  void updatedAtIsApplicationManagedNotAuto() throws SQLException {
    // 显式设置 created_at/updated_at；随后 UPDATE description，并确认 updated_at
    // 不会自动改变（除非显式重写，无 MySQL ON UPDATE 模拟）。
    String name = "auto-update-probe-" + FIXTURE_IDS.incrementAndGet();
    Timestamp fixedTimestamp = Timestamp.from(Instant.parse("2024-01-01T00:00:00Z"));
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "insert into agent_provider (name, provider_type, config, connection_generation_id, created_at,"
                    + " updated_at) values (?, 'openai', '{}'::jsonb, ?, ?, ?)")) {
      ps.setString(1, name);
      ps.setObject(2, EXPLICIT_CONNECTION_GENERATION_ID);
      ps.setTimestamp(3, fixedTimestamp);
      ps.setTimestamp(4, fixedTimestamp);
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
