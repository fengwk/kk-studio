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

import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;

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
import java.util.UUID;

/**
 * 断言 PostgreSQL schema 结构：所有必需的表与列类型都存在，Harness 执行协议恰好是 infra 的七张表， 结构化载荷使用 jsonb，时间列统一使用毫秒精度
 * timestamptz(3)。
 *
 * <p>public schema 的相等性校验是严格的：{@code public} 中 {@code BASE TABLE} 的集合必须与期望列表完全一致。
 */
class PostgresqlSchemaStructureTest extends PostgresSchemaSupport {

  private static final List<String> EXPECTED_TABLES =
      Arrays.asList(
          "agent_definition",
          "agent_model",
          "agent_provider",
          "canvas_command_dedup",
          "canvas_document",
          "canvas_function_resource_pin",
          "canvas_function_run",
          "canvas_group",
          "canvas_node",
          "canvas_resource",
          "chat",
          "chat_session",
          "environment",
          "environment_connection",
          "flyway_schema_history",
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_tool_invocation",
          "harness_work",
          "mcp_server",
          "mcp_tool",
          "plugin_credential",
          "project",
          "project_issue",
          "project_issue_activity",
          "project_issue_agent_thread",
          "project_issue_evidence",
          "project_issue_run",
          "project_issue_stage_budget",
          "project_issue_work",
          "session_blob_ref",
          "skill_package",
          "storage_blob",
          "storage_upload",
          "system_setting");

  /** 精确的 infra 执行协议七表；业务表（如 session_blob_ref）不得使用 harness_ 前缀。 */
  private static final Set<String> HARNESS_EXECUTION_TABLES =
      Set.of(
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work");

  /** 所有允许使用 harness_ 前缀的基础设施表。 */
  private static final Set<String> HARNESS_PREFIXED_TABLES =
      Set.of(
          "harness_session",
          "harness_entry",
          "harness_thread",
          "harness_thread_command",
          "harness_model_invocation",
          "harness_tool_invocation",
          "harness_work");

  /** Canvas 完全 UUID：所有持久化实体 id 由应用侧生成，schema 不提供任何序列。 */
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
  void harnessPrefixIsRestrictedToDeclaredInfrastructureTables() throws SQLException {
    Set<String> harnessTables = new TreeSet<>();
    for (String table : tableNames()) {
      if (table.startsWith("harness_")) {
        harnessTables.add(table);
      }
    }
    assertEquals(
        new TreeSet<>(HARNESS_PREFIXED_TABLES),
        harnessTables,
        "only declared infrastructure tables may use the harness_ prefix");
  }

  @Test
  void nonHarnessBusinessTablesExposeCompleteColumnContracts() throws SQLException {
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
        "canvas_command_dedup",
        "canvas_id",
        "idempotency_key",
        "request_hash",
        "accepted_revision");
    assertColumns("canvas_document", "id", "title", "revision", "created_at", "updated_at");
    assertColumns(
        "canvas_function_resource_pin",
        "canvas_id",
        "node_id",
        "request_id",
        "role",
        "resource_id");
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
        "created_at",
        "updated_at");
    assertColumns("canvas_group", "id", "canvas_id", "title", "x", "y", "width", "height");
    assertColumns(
        "canvas_node",
        "id",
        "canvas_id",
        "name",
        "name_key",
        "x",
        "y",
        "width",
        "height",
        "group_id",
        "function");
    assertColumns(
        "canvas_resource",
        "id",
        "canvas_id",
        "owner_node_id",
        "resource_index",
        "name",
        "blob_id",
        "text_content",
        "created_at");
    assertColumns(
        "chat",
        "id",
        "title",
        "agent_name",
        "yolo_enabled",
        "created_at",
        "updated_at",
        "version",
        "archived_at");
    assertColumns("chat_session", "session_id", "chat_id", "created_at");
    assertColumns(
        "environment", "id", "name", "registration_token", "created_at", "updated_at", "version");
    assertColumns(
        "environment_connection",
        "environment_id",
        "owner_node_id",
        "lease_token",
        "status",
        "runtime_info",
        "skill_state",
        "recent_events",
        "last_seen_at",
        "lease_until");
    assertColumns(
        "mcp_server",
        "name",
        "url",
        "headers",
        "enabled",
        "timeout_millis",
        "discovery_status",
        "created_at",
        "updated_at",
        "version");
    assertColumns("mcp_tool", "name", "server_name", "source_name", "description", "input_schema");
    assertColumns(
        "plugin_credential",
        "plugin_id",
        "encrypted_payload",
        "region",
        "expires_at",
        "next_refresh_at",
        "status",
        "last_refreshed_at",
        "last_refresh_error",
        "refresh_lease_token",
        "refresh_lease_until",
        "version",
        "create_time",
        "update_time");
    assertColumns(
        "project",
        "id",
        "title",
        "description",
        "workflow",
        "yolo_enabled",
        "next_issue_number",
        "version",
        "archived_at",
        "created_at",
        "updated_at");
    assertColumns(
        "project_issue",
        "id",
        "project_id",
        "number",
        "title",
        "description",
        "state",
        "blocked_from_state",
        "block_reason",
        "pause_reason",
        "pause_detail",
        "next_run_ordinal",
        "next_activity_sequence",
        "version",
        "archived_at",
        "created_at",
        "updated_at");
    assertColumns(
        "project_issue_activity",
        "issue_id",
        "sequence",
        "kind",
        "actor_type",
        "actor_agent_name",
        "run_id",
        "body",
        "data",
        "idempotency_key",
        "request_hash",
        "created_at");
    assertColumns(
        "project_issue_agent_thread", "issue_id", "agent_name", "thread_id", "created_at");
    assertColumns(
        "project_issue_evidence",
        "issue_id",
        "blob_id",
        "actor_agent_name",
        "run_id",
        "name",
        "created_at");
    assertColumns(
        "project_issue_run",
        "id",
        "issue_id",
        "ordinal",
        "state",
        "session_id",
        "thread_id",
        "status",
        "start_entry_id",
        "end_entry_id",
        "final_answer_entry_id",
        "next_state",
        "observed_activity_sequence",
        "remaining_execution_ms",
        "active_since",
        "error",
        "version",
        "started_at",
        "ended_at");
    assertColumns(
        "project_issue_stage_budget",
        "issue_id",
        "state",
        "max_runs",
        "budget_after_ordinal",
        "created_at",
        "updated_at");
    assertColumns(
        "project_issue_work",
        "issue_id",
        "wake_version",
        "due_at",
        "lease_token",
        "lease_until",
        "created_at",
        "updated_at");
    assertColumns("session_blob_ref", "session_id", "blob_id", "created_at");
    assertColumns(
        "skill_package",
        "package_name",
        "description",
        "repository_url",
        "branch",
        "current_commit",
        "observed_head_commit",
        "head_checked_at",
        "head_check_error",
        "skills",
        "version",
        "create_time",
        "update_time");
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
    assertColumns("system_setting", "id", "config", "version", "created_at", "updated_at");
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
        "updated_at",
        "input_receipt");
    assertColumns(
        "harness_work",
        "target_type",
        "target_id",
        "available_at",
        "wake_version",
        "lease_token",
        "lease_until",
        "required_environment_id");
  }

  @Test
  void structuredPayloadsAreExactlyTheDeclaredJsonbColumns() throws SQLException {
    Set<String> jsonbColumns = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name from information_schema.columns"
                    + " where table_schema = 'public' and data_type = 'jsonb'")) {
      while (rs.next()) {
        jsonbColumns.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "agent_definition.config",
            "agent_model.config",
            "agent_provider.config",
            "canvas_function_run.state_json",
            "canvas_node.function",
            "environment_connection.recent_events",
            "environment_connection.runtime_info",
            "environment_connection.skill_state",
            "harness_entry.payload",
            "harness_entry.provider_replay_state",
            "harness_model_invocation.error",
            "harness_model_invocation.failed_attempts",
            "harness_model_invocation.provider_replay_state",
            "harness_model_invocation.request_spec",
            "harness_model_invocation.result",
            "harness_model_invocation.stream_checkpoint",
            "harness_thread_command.payload",
            "harness_tool_invocation.approval",
            "harness_tool_invocation.binding",
            "harness_tool_invocation.call",
            "harness_tool_invocation.effects",
            "harness_tool_invocation.error",
            "harness_tool_invocation.input_receipt",
            "harness_tool_invocation.result",
            "mcp_server.headers",
            "mcp_tool.input_schema",
            "project.workflow",
            "project_issue_activity.data",
            "skill_package.skills",
            "system_setting.config"),
        jsonbColumns,
        "jsonb columns must exactly match the 30 declared structured payloads");

    // 自由文本字段不受列宽限制，使用 text
    assertColumnType("text", "agent_provider", "description");
    assertColumnType("text", "agent_model", "description");
    assertColumnType("text", "agent_definition", "description");
    assertColumnType("text", "mcp_tool", "description");
    assertColumnType("integer", "canvas_function_run", "attempt");
    assertColumnType("character varying", "canvas_function_run", "lease_token");
  }

  @Test
  void noTableStoresByteaFileBlobsExceptEncryptedCredentialPayload() throws SQLException {
    // 文件正文与媒体字节只存在于 S3；public schema 中唯一允许的 bytea 是 plugin_credential 的
    // AES-256-GCM 加密凭据信封，它不是文件正文。
    Set<String> byteaColumns = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name from information_schema.columns"
                    + " where table_schema = 'public' and data_type = 'bytea'")) {
      while (rs.next()) {
        byteaColumns.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of("plugin_credential.encrypted_payload"),
        byteaColumns,
        "only the encrypted credential envelope may use bytea; file and media content lives outside the database");
  }

  @Test
  void temporalColumnsAreMillisecondTimestamptz() throws SQLException {
    Set<String> temporalColumns = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name, data_type, datetime_precision"
                    + " from information_schema.columns"
                    + " where table_schema = 'public'"
                    + " and table_name <> 'flyway_schema_history'"
                    + " and (data_type like '%time%' or data_type like '%date%' or data_type like '%interval%')")) {
      while (rs.next()) {
        String col = rs.getString(1);
        String dataType = rs.getString(2);
        int precision = rs.getInt(3);
        assertEquals(
            "timestamp with time zone",
            dataType,
            () -> "temporal column " + col + " must use timestamp with time zone");
        assertEquals(
            3, precision, () -> "temporal column " + col + " must have millisecond precision (3)");
        temporalColumns.add(col);
      }
    }
    assertEquals(
        Set.of(
            "agent_definition.created_at",
            "agent_definition.updated_at",
            "agent_model.created_at",
            "agent_model.updated_at",
            "agent_provider.created_at",
            "agent_provider.updated_at",
            "canvas_document.created_at",
            "canvas_document.updated_at",
            "canvas_function_run.available_at",
            "canvas_function_run.created_at",
            "canvas_function_run.lease_until",
            "canvas_function_run.updated_at",
            "canvas_resource.created_at",
            "chat.archived_at",
            "chat.created_at",
            "chat.updated_at",
            "chat_session.created_at",
            "environment.created_at",
            "environment.updated_at",
            "environment_connection.last_seen_at",
            "environment_connection.lease_until",
            "harness_entry.created_at",
            "harness_model_invocation.created_at",
            "harness_model_invocation.updated_at",
            "harness_session.created_at",
            "harness_thread.created_at",
            "harness_thread.updated_at",
            "harness_thread_command.cancelled_at",
            "harness_thread_command.created_at",
            "harness_tool_invocation.created_at",
            "harness_tool_invocation.updated_at",
            "harness_work.available_at",
            "harness_work.lease_until",
            "mcp_server.created_at",
            "mcp_server.updated_at",
            "plugin_credential.create_time",
            "plugin_credential.expires_at",
            "plugin_credential.last_refreshed_at",
            "plugin_credential.next_refresh_at",
            "plugin_credential.refresh_lease_until",
            "plugin_credential.update_time",
            "project.archived_at",
            "project.created_at",
            "project.updated_at",
            "project_issue.archived_at",
            "project_issue.created_at",
            "project_issue.updated_at",
            "project_issue_activity.created_at",
            "project_issue_agent_thread.created_at",
            "project_issue_evidence.created_at",
            "project_issue_run.active_since",
            "project_issue_run.ended_at",
            "project_issue_run.started_at",
            "project_issue_stage_budget.created_at",
            "project_issue_stage_budget.updated_at",
            "project_issue_work.created_at",
            "project_issue_work.due_at",
            "project_issue_work.lease_until",
            "project_issue_work.updated_at",
            "session_blob_ref.created_at",
            "skill_package.create_time",
            "skill_package.head_checked_at",
            "skill_package.update_time",
            "storage_blob.created_at",
            "storage_blob.updated_at",
            "storage_upload.cleanup_requested_at",
            "storage_upload.cleanup_until",
            "storage_upload.created_at",
            "storage_upload.expires_at",
            "system_setting.created_at",
            "system_setting.updated_at"),
        temporalColumns,
        "business schema temporal columns must exactly equal the 71 timestamptz(3) columns");

    assertEquals(
        128L,
        singleLong(
            "select character_maximum_length from information_schema.columns"
                + " where table_schema = 'public' and table_name = 'canvas_function_run'"
                + " and column_name = 'lease_token'"));
  }

  @Test
  void usesNativeBooleanForFlags() throws SQLException {
    Set<String> booleanColumns = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name from information_schema.columns"
                    + " where table_schema = 'public'"
                    + " and table_name <> 'flyway_schema_history'"
                    + " and data_type = 'boolean'")) {
      while (rs.next()) {
        booleanColumns.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "chat.yolo_enabled",
            "harness_thread.yolo_enabled",
            "mcp_server.enabled",
            "project.yolo_enabled"),
        booleanColumns,
        "exact set of native boolean columns");
  }

  @Test
  void durableEntityIdentifiersUseDeclaredTypes() throws SQLException {
    for (String table : CANVAS_UUID_ID_TABLES) {
      assertColumnType("uuid", table, "id");
    }
    assertColumnType("text", "canvas_node", "name_key");
    assertColumnType("uuid", "canvas_command_dedup", "canvas_id");
    assertColumnType("uuid", "canvas_function_run", "node_id");
    assertColumnType("uuid", "canvas_function_run", "request_id");
    assertColumnType("uuid", "canvas_function_resource_pin", "canvas_id");
    assertColumnType("uuid", "canvas_function_resource_pin", "node_id");
    assertColumnType("uuid", "canvas_function_resource_pin", "request_id");
    assertColumnType("uuid", "canvas_function_resource_pin", "resource_id");
    for (String table : HARNESS_EXECUTION_TABLES) {
      if (table.equals("harness_work") || table.equals("harness_thread_command")) {
        // harness_work 通过 (target_type, target_id) 标识目标；ThreadCommand 身份为 (thread_id, sequence)。
        continue;
      }
      assertColumnType("uuid", table, "id");
    }
    assertColumnType("uuid", "session_blob_ref", "session_id");
    assertColumnType("uuid", "session_blob_ref", "blob_id");
    assertColumnType("uuid", "chat", "id");
    assertColumnType("uuid", "chat_session", "session_id");
    assertColumnType("uuid", "chat_session", "chat_id");
    assertColumnType("character varying", "mcp_server", "name");
    assertColumnType("jsonb", "mcp_server", "headers");
    assertColumnType("character varying", "mcp_tool", "name");
    assertColumnType("jsonb", "mcp_tool", "input_schema");
    assertColumnType("uuid", "project", "id");
    assertColumnType("uuid", "project_issue", "id");
    assertColumnType("uuid", "project_issue", "project_id");
    assertColumnType("uuid", "project_issue_run", "id");
    assertColumnType("uuid", "project_issue_run", "issue_id");
    assertColumnType("uuid", "project_issue_run", "session_id");
    assertColumnType("uuid", "project_issue_run", "thread_id");
    assertColumnType("uuid", "project_issue_agent_thread", "issue_id");
    assertColumnType("uuid", "project_issue_agent_thread", "thread_id");
    assertColumnType("uuid", "storage_blob", "id");
    assertColumnType("uuid", "storage_upload", "id");
    assertColumnType("uuid", "storage_upload", "candidate_blob_id");
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
  void noColumnCarriesASequenceOrIdentityDefault() throws SQLException {
    // 实体 id 均由应用侧 UUID 生成并显式插入；任何表都不得携带序列默认值或 IDENTITY。
    for (String table : HARNESS_EXECUTION_TABLES) {
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
    // 全局检查：没有列定义为 identity，也没有列默认值包含 nextval(...)
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select table_name || '.' || column_name from information_schema.columns"
                    + " where table_schema = 'public'"
                    + " and (is_identity = 'YES' or column_default like '%nextval%')")) {
      assertFalse(
          rs.next(),
          "public schema must contain no identity columns or nextval(...) sequence defaults");
    }
  }

  @Test
  void noForeignKeyIsDeferred() throws SQLException {
    // 生产 Flyway baseline 架构中不存在延迟外键：所有外键均在语句执行时立即校验
    Set<String> deferred = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname from pg_constraint"
                    + " where contype = 'f' and connamespace = 'public'::regnamespace and condeferrable")) {
      while (rs.next()) {
        deferred.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(),
        deferred,
        "target Flyway baseline has no deferrable foreign keys; all FKs are checked immediately");
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
            "uk_canvas_node_canvas_id",
            "uk_canvas_node_canvas_name_key",
            "uk_canvas_resource_canvas_id",
            "uk_canvas_resource_slot",
            "uk_environment_name",
            "uk_environment_registration_token",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_model_invocation_result",
            "uk_harness_model_invocation_turn",
            "uk_harness_thread_command_idempotency",
            "uk_harness_thread_session",
            "uk_harness_tool_invocation_call_index",
            "uk_mcp_tool_server_source_name",
            "uk_project_activity_run",
            "uk_project_issue_activity_request",
            "uk_project_issue_agent_thread_issue",
            "uk_project_issue_agent_thread_thread",
            "uk_project_issue_run_active",
            "uk_project_issue_run_id_issue",
            "uk_project_issue_run_issue_ordinal",
            "uk_storage_blob_active_hash",
            "uk_storage_upload_candidate"),
        indexes,
        "the final schema must expose only its declared 23 domain unique keys");

    Set<String> foreignKeys = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname from pg_constraint"
                    + " where contype = 'f' and connamespace = 'public'::regnamespace")) {
      while (rs.next()) {
        foreignKeys.add(rs.getString(1));
      }
    }
    assertEquals(
        Set.of(
            "canvas_command_dedup_canvas_id_fkey",
            "canvas_function_run_node_id_fkey",
            "canvas_group_canvas_id_fkey",
            "canvas_node_canvas_id_fkey",
            "canvas_resource_canvas_id_fkey",
            "fk_agent_definition_model",
            "fk_agent_model_provider",
            "fk_canvas_node_group",
            "fk_canvas_pin_node",
            "fk_canvas_pin_resource",
            "fk_canvas_pin_run",
            "fk_canvas_resource_blob",
            "fk_canvas_resource_owner",
            "fk_chat_session_chat",
            "fk_chat_session_session",
            "fk_environment_connection_environment",
            "fk_harness_entry_parent",
            "fk_harness_entry_session",
            "fk_harness_model_invocation_request_head",
            "fk_harness_model_invocation_result",
            "fk_harness_model_invocation_thread",
            "fk_harness_model_invocation_turn_start",
            "fk_harness_thread_command_applied",
            "fk_harness_thread_command_thread",
            "fk_harness_thread_head",
            "fk_harness_thread_session",
            "fk_harness_tool_invocation_assistant",
            "fk_harness_tool_invocation_model",
            "fk_harness_work_environment",
            "fk_mcp_tool_server",
            "fk_project_issue_activity_agent",
            "fk_project_issue_activity_issue",
            "fk_project_issue_activity_run",
            "fk_project_issue_agent_thread_agent",
            "fk_project_issue_agent_thread_issue",
            "fk_project_issue_agent_thread_thread",
            "fk_project_issue_evidence_agent",
            "fk_project_issue_evidence_blob",
            "fk_project_issue_evidence_issue",
            "fk_project_issue_evidence_run",
            "fk_project_issue_run_agent_thread",
            "fk_project_issue_run_end_entry",
            "fk_project_issue_run_final_answer",
            "fk_project_issue_run_stage_budget",
            "fk_project_issue_run_start_entry",
            "fk_project_issue_run_thread_session",
            "fk_project_issue_stage_budget_issue",
            "fk_project_issue_work_issue",
            "fk_session_blob_ref_blob",
            "fk_session_blob_ref_session",
            "fk_storage_upload_blob",
            "project_issue_project_id_fkey"),
        foreignKeys,
        "all 52 declared foreign keys must exist in public schema");
  }

  @Test
  void businessForeignKeyDeleteActionsAreExplicit() throws SQLException {
    Set<String> restrictFks = new TreeSet<>();
    Set<String> cascadeFks = new TreeSet<>();
    Set<String> noActionFks = new TreeSet<>();
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select conname, confdeltype from pg_constraint"
                    + " where contype = 'f' and connamespace = 'public'::regnamespace")) {
      while (rs.next()) {
        String name = rs.getString(1);
        String delType = rs.getString(2);
        switch (delType) {
          case "r" -> restrictFks.add(name);
          case "c" -> cascadeFks.add(name);
          case "a" -> noActionFks.add(name);
          default -> throw new IllegalStateException(
              "unexpected confdeltype " + delType + " for " + name);
        }
      }
    }
    assertEquals(
        Set.of(
            "canvas_command_dedup_canvas_id_fkey",
            "canvas_function_run_node_id_fkey",
            "canvas_group_canvas_id_fkey",
            "canvas_node_canvas_id_fkey",
            "canvas_resource_canvas_id_fkey",
            "fk_canvas_node_group",
            "fk_canvas_pin_node",
            "fk_canvas_pin_resource",
            "fk_canvas_pin_run",
            "fk_canvas_resource_blob",
            "fk_canvas_resource_owner",
            "fk_chat_session_chat",
            "fk_chat_session_session",
            "fk_harness_work_environment",
            "fk_project_issue_activity_agent",
            "fk_project_issue_activity_issue",
            "fk_project_issue_activity_run",
            "fk_project_issue_agent_thread_agent",
            "fk_project_issue_agent_thread_issue",
            "fk_project_issue_agent_thread_thread",
            "fk_project_issue_evidence_agent",
            "fk_project_issue_evidence_blob",
            "fk_project_issue_evidence_issue",
            "fk_project_issue_evidence_run",
            "fk_project_issue_run_agent_thread",
            "fk_project_issue_run_end_entry",
            "fk_project_issue_run_final_answer",
            "fk_project_issue_run_stage_budget",
            "fk_project_issue_run_start_entry",
            "fk_project_issue_run_thread_session",
            "fk_project_issue_stage_budget_issue",
            "fk_project_issue_work_issue",
            "fk_session_blob_ref_blob",
            "fk_session_blob_ref_session",
            "fk_storage_upload_blob",
            "project_issue_project_id_fkey"),
        restrictFks,
        "exact set of 36 RESTRICT foreign keys");

    assertEquals(
        Set.of("fk_environment_connection_environment", "fk_mcp_tool_server"),
        cascadeFks,
        "exact set of 2 CASCADE foreign keys");

    assertEquals(
        Set.of(
            "fk_agent_definition_model",
            "fk_agent_model_provider",
            "fk_harness_entry_parent",
            "fk_harness_entry_session",
            "fk_harness_model_invocation_request_head",
            "fk_harness_model_invocation_result",
            "fk_harness_model_invocation_thread",
            "fk_harness_model_invocation_turn_start",
            "fk_harness_thread_command_applied",
            "fk_harness_thread_command_thread",
            "fk_harness_thread_head",
            "fk_harness_thread_session",
            "fk_harness_tool_invocation_assistant",
            "fk_harness_tool_invocation_model"),
        noActionFks,
        "exact set of 14 NO ACTION foreign keys");
  }

  @Test
  void userTriggersAreExactAndVersionNotifiersNeverMutateVersions() throws SQLException {
    // 精确枚举通知 trigger，避免 schema 留下隐式写入行为。
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
            "trg_canvas_document_revision_notify",
            "trg_canvas_function_work_notify",
            "trg_harness_thread_version_notify",
            "trg_project_issue_work_due",
            "trg_skill_package_changed",
            "trg_system_setting_version_notify"),
        triggers,
        "public triggers must equal the exact set of 6 user triggers");

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

    // canvas revision hint 触发器只做 NOTIFY，永不写版本。
    String canvasDefinition =
        singleString(
            "select pg_get_triggerdef(oid) from pg_trigger"
                + " where tgname = 'trg_canvas_document_revision_notify'");
    assertTrue(
        canvasDefinition.contains("AFTER INSERT OR UPDATE OF revision"),
        () -> "canvas trigger must fire after insert or revision update: " + canvasDefinition);
    String canvasFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'notify_canvas_document_revision'");
    assertTrue(
        canvasFunctionSource.contains("pg_notify"),
        () -> "canvas notify function must call pg_notify");
    assertTrue(
        canvasFunctionSource.contains("canvas_revision"),
        () -> "canvas notify function must use the canvas_revision channel");
    assertTrue(
        canvasFunctionSource.contains("new.id::text || ':' || new.revision::text"),
        () -> "canvas notify payload must be {canvasId}:{revision}: " + canvasFunctionSource);

    // canvas function work 触发器仅在 READY 且 lease_token 为 null 且 immediately claimable 时 NOTIFY
    String canvasWorkFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'notify_canvas_function_work'");
    assertTrue(canvasWorkFunctionSource.contains("pg_notify"));
    assertTrue(canvasWorkFunctionSource.contains("canvas_function_work"));
    assertTrue(canvasWorkFunctionSource.contains("new.status = 'READY'"));
    assertTrue(canvasWorkFunctionSource.contains("new.lease_token is null"));

    // Skill Package 发布提示：channel 必须与 Platform listener 常量一致，payload 只是 package 名。
    String skillPackageFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'skill_package_changed_notify'");
    assertTrue(skillPackageFunctionSource.contains("pg_notify"));
    assertTrue(
        skillPackageFunctionSource.contains(EnvironmentSkillSyncOrchestrator.CHANNEL),
        () ->
            "skill package notify function must use the orchestrator channel: "
                + skillPackageFunctionSource);
    assertTrue(
        skillPackageFunctionSource.contains("new.package_name")
            && skillPackageFunctionSource.contains("old.package_name"),
        () ->
            "skill package notify payload must be the package name: " + skillPackageFunctionSource);

    // project_issue_work due hint 触发器
    String issueWorkFunctionSource =
        singleString(
            "select prosrc from pg_proc"
                + " where pronamespace = 'public'::regnamespace"
                + " and proname = 'notify_project_issue_work_due'");
    assertTrue(issueWorkFunctionSource.contains("pg_notify"));
    assertTrue(issueWorkFunctionSource.contains("project_issue_work_due"));
    assertTrue(issueWorkFunctionSource.contains("new.issue_id::text"));

    // public 函数严格限定为通知函数
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
            "harness_thread_version_notify",
            "notify_canvas_document_revision",
            "notify_canvas_function_work",
            "notify_project_issue_work_due",
            "skill_package_changed_notify",
            "system_setting_version_notify"),
        functions,
        "only declared 6 notification helpers may exist");

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

    // CONNECTING 行必须能保留最近一次 READY 的宿主 metadata：断线不清空 runtime_info
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                  + "values (?, ?, ?, 'CONNECTING', '{}'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
        ps.setObject(1, env2);
        ps.setObject(2, node1);
        ps.setObject(3, token1);
        assertEquals(1, ps.executeUpdate());
      }
    }

    // runtime_info 必须是 jsonb object（数组不合法）
    UUID env3 = uuid(103L);
    try (Connection conn = newConnection()) {
      try (PreparedStatement ps =
          conn.prepareStatement(
              "insert into environment (id, name, registration_token) values (?, 'stage', 'tok3')")) {
        ps.setObject(1, env3);
        ps.executeUpdate();
      }
      assertTransactionConstraintViolation(
          conn,
          "ck_environment_connection_runtime_info",
          () -> {
            try (PreparedStatement ps =
                conn.prepareStatement(
                    "insert into environment_connection (environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until) "
                        + "values (?, ?, ?, 'CONNECTING', '[]'::jsonb, statement_timestamp(), statement_timestamp() + interval '60 seconds')")) {
              ps.setObject(1, env3);
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
          "ck_environment_connection_ready_runtime_info",
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
  void removedLegacyProductObjectsAreAbsent() throws SQLException {
    // 验证废弃的旧设计表、触发器与函数已全部从 public schema 中清除
    List<String> legacyTables =
        List.of(
            "canvas_link",
            "comfyui_workflow_api",
            "human_question",
            "project_issue_agent_session",
            "project_issue_branch",
            "project_issue_dependency",
            "project_state",
            "project_transition",
            "session_owner");
    try (Connection conn = newConnection()) {
      for (String table : legacyTables) {
        try (PreparedStatement ps = conn.prepareStatement("select to_regclass('public.' || ?)")) {
          ps.setString(1, table);
          try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertNull(rs.getString(1), () -> "legacy table " + table + " must not exist");
          }
        }
      }
    }

    Set<String> legacyTriggers =
        Set.of(
            "trg_project_issue_changed_activity",
            "trg_project_issue_changed_agent_session",
            "trg_project_issue_changed_dependency",
            "trg_project_issue_changed_issue",
            "trg_project_issue_changed_project",
            "trg_project_issue_changed_run",
            "trg_project_issue_changed_session_owner",
            "trg_project_issue_changed_thread");
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select tgname from pg_trigger t join pg_class c on c.oid = t.tgrelid"
                    + " where c.relnamespace = 'public'::regnamespace and not t.tgisinternal")) {
      while (rs.next()) {
        String tgname = rs.getString(1);
        assertFalse(
            legacyTriggers.contains(tgname),
            () -> "legacy trigger " + tgname + " must not exist in public schema");
      }
    }

    Set<String> legacyFunctions =
        Set.of(
            "canvas_document_version_notify",
            "canvas_function_work_notify",
            "project_issue_changed_notify");
    try (Connection conn = newConnection();
        Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select proname from pg_proc where pronamespace = 'public'::regnamespace")) {
      while (rs.next()) {
        String proname = rs.getString(1);
        assertFalse(
            legacyFunctions.contains(proname),
            () -> "legacy function " + proname + " must not exist in public schema");
      }
    }
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
