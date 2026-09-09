package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

class PostgresqlHarnessSchemaTest {

  /**
   * Harness runtime 协议恰好七张表；业务表不使用 harness_ 前缀（V1 中唯一的应用层 Session blob 引用表为 session_blob_ref）， 因此
   * {@code harness_%} 全量查询结果必须精确等于该七表。
   */
  private static final List<String> RUNTIME_TABLES =
      List.of(
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_tool_invocation",
          "harness_work");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  @Test
  void schemaContainsExactlyTheSevenRuntimeTables() {
    // 精确查询全部 harness_% 表：任何业务表（如 session_blob_ref）不得混入 runtime 协议空间。
    List<String> tables =
        jdbc.queryForList(
            """
            select table_name
            from information_schema.tables
            where table_schema = 'public'
              and table_type = 'BASE TABLE'
              and table_name like 'harness\\_%'
            order by table_name
            """,
            String.class);
    assertEquals(RUNTIME_TABLES, tables);
  }

  @Test
  void threadTableStoresOnlyItsOwnedDurableState() {
    List<String> columns =
        jdbc.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'harness_thread'
            order by ordinal_position
            """,
            String.class);
    assertEquals(
        List.of(
            "id",
            "session_id",
            "head_entry_id",
            "creation_request_hash",
            "name",
            "yolo_enabled",
            "next_command_sequence",
            "version",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void everyStructuredDurablePayloadUsesJsonb() {
    // 只统计七张 runtime 表的 jsonb 列：session_blob_ref 无结构化载荷，不应出现。
    List<String> jsonbColumns =
        jdbc.queryForList(
            """
            select table_name || '.' || column_name
            from information_schema.columns
            where table_schema = 'public'
              and data_type = 'jsonb'
              and table_name like 'harness\\_%'
            order by table_name, column_name
            """,
            String.class);
    assertEquals(
        List.of(
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
            "harness_tool_invocation.result"),
        jsonbColumns);
  }

  @Test
  void schemaDefinesExactlyTheRequiredNamedIndexes() {
    List<String> indexes =
        jdbc.queryForList(
            """
            select indexname
            from pg_indexes
            where schemaname = 'public'
              and tablename like 'harness\\_%'
              and indexname not like '%_pkey'
            order by indexname
            """,
            String.class);
    assertEquals(
        List.of(
            "idx_harness_entry_parent",
            "idx_harness_thread_command_queued",
            "idx_harness_thread_command_stop_request",
            "idx_harness_thread_session",
            "idx_harness_work_available",
            "idx_harness_work_lease_until",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_model_invocation_result",
            "uk_harness_model_invocation_turn",
            "uk_harness_thread_command_idempotency",
            "uk_harness_tool_invocation_call_index"),
        indexes);

    String modelResult = indexDefinition("uk_harness_model_invocation_result");
    String workAvailable = indexDefinition("idx_harness_work_available");
    String workLease = indexDefinition("idx_harness_work_lease_until");
    String threadSession = indexDefinition("idx_harness_thread_session");
    String stopRequest = indexDefinition("idx_harness_thread_command_stop_request");
    assertTrue(modelResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(workAvailable.contains("(available_at, target_type, target_id)"));
    assertTrue(workLease.contains("(lease_until, target_type, target_id)"));
    assertTrue(workLease.contains("WHERE (lease_until IS NOT NULL)"));
    assertTrue(threadSession.contains("(session_id, created_at, id)"));
    // Stop 幂等键索引必须按 stop_request_id 聚合并只覆盖非 null 行。
    assertTrue(stopRequest.contains("(thread_id, stop_request_id, sequence)"));
    assertTrue(stopRequest.contains("WHERE (stop_request_id IS NOT NULL)"));
  }

  private String indexDefinition(String indexName) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
        String.class,
        indexName);
  }

  @Test
  void harnessEntryProviderReplayStateConstraintEnforcesAssistantMessageAndJsonObject() {
    // 测试意图：真实 PostgreSQL CHECK 约束 ck_harness_entry_provider_replay_state 物理门禁验证。
    // 规定 provider_replay_state 仅允许 ASSISTANT MESSAGE，且必须是 JSON object。
    // 验证至少拒绝 USER MESSAGE、非 MESSAGE Entry、JSON 非 object；且合法的 ASSISTANT MESSAGE object 允许写入。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);

    String validReplayJson =
        "{\"format\":\"openai_responses\",\"affinity\":{},\"sourcePrefixHash\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"payload\":{}}";

    // 1. 拒绝 USER MESSAGE 携带 provider_replay_state
    DataIntegrityViolationException exUser =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                        + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hi\"}]}}'::jsonb,"
                        + " statement_timestamp(), ?::jsonb)",
                    UUID.randomUUID(),
                    sessionId,
                    rootEntryId,
                    validReplayJson),
            "ck_harness_entry_provider_replay_state must reject USER MESSAGE");
    assertTrue(
        exUser.getMessage().contains("ck_harness_entry_provider_replay_state"),
        "expected violation of ck_harness_entry_provider_replay_state but got: "
            + exUser.getMessage());

    // 2. 拒绝非 MESSAGE Entry（如 TURN_START）携带 provider_replay_state
    DataIntegrityViolationException exNonMessage =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                        + " values (?, ?, ?, 'TURN_START', '{\"reason\":\"INPUT\"}'::jsonb,"
                        + " statement_timestamp(), ?::jsonb)",
                    UUID.randomUUID(),
                    sessionId,
                    rootEntryId,
                    validReplayJson),
            "ck_harness_entry_provider_replay_state must reject non-MESSAGE entry");
    assertTrue(
        exNonMessage.getMessage().contains("ck_harness_entry_provider_replay_state"),
        "expected violation of ck_harness_entry_provider_replay_state but got: "
            + exNonMessage.getMessage());

    // 3. 拒绝 JSON 非 object（如 array）
    DataIntegrityViolationException exNonObjectArray =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                        + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'::jsonb,"
                        + " statement_timestamp(), '[\"not_an_object\"]'::jsonb)",
                    UUID.randomUUID(),
                    sessionId,
                    rootEntryId),
            "ck_harness_entry_provider_replay_state must reject non-object JSON array");
    assertTrue(
        exNonObjectArray.getMessage().contains("ck_harness_entry_provider_replay_state"),
        "expected violation of ck_harness_entry_provider_replay_state but got: "
            + exNonObjectArray.getMessage());

    // 4. 拒绝 JSON 非 object（如 scalar string）
    DataIntegrityViolationException exNonObjectScalar =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                        + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'::jsonb,"
                        + " statement_timestamp(), '\"string_scalar\"'::jsonb)",
                    UUID.randomUUID(),
                    sessionId,
                    rootEntryId),
            "ck_harness_entry_provider_replay_state must reject scalar JSON");
    assertTrue(
        exNonObjectScalar.getMessage().contains("ck_harness_entry_provider_replay_state"),
        "expected violation of ck_harness_entry_provider_replay_state but got: "
            + exNonObjectScalar.getMessage());

    // 5. 正向验证：合法的 ASSISTANT MESSAGE + JSON object 正常持久化
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                    + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'::jsonb,"
                    + " statement_timestamp(), ?::jsonb)",
                UUID.randomUUID(),
                sessionId,
                rootEntryId,
                validReplayJson),
        "valid ASSISTANT MESSAGE with object replay state must succeed");
  }

  @Test
  void harnessModelInvocationProviderReplayStateConstraintEnforcesSucceededResultAndJsonObject() {
    // 测试意图：真实 PostgreSQL CHECK 约束 ck_harness_model_invocation_provider_replay_state 物理门禁验证。
    // 规定 provider_replay_state 仅允许 SUCCEEDED + result 非 null + result_entry_id null，且必须是 JSON
    // object。
    // 验证至少分别拒绝 RUNNING、SUCCEEDED/result null、SUCCEEDED/result_entry_id 非 null、JSON 非 object；
    // 且合法的 SUCCEEDED 未物化结果 invocation 允许写入。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000012");
    UUID assistantEntryId = UUID.fromString("00000000-0000-0000-0000-000000000013");
    UUID threadId = UUID.fromString("00000000-0000-0000-0000-000000000014");

    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'TURN_START', '{\"reason\":\"INPUT\"}'::jsonb, statement_timestamp())",
        turnStartEntryId,
        sessionId,
        rootEntryId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}]}}'::jsonb, statement_timestamp())",
        assistantEntryId,
        sessionId,
        turnStartEntryId);
    jdbc.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 1, 0, statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        turnStartEntryId);

    String validReplayJson =
        "{\"format\":\"openai_responses\",\"affinity\":{},\"sourcePrefixHash\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"payload\":{}}";
    String validResultJson = "{\"id\":\"resp-1\",\"text\":\"ok\",\"stopReason\":\"COMPLETE\"}";

    // 1. 拒绝 RUNNING 状态携带 provider_replay_state
    DataIntegrityViolationException exRunning =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                        + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                        + " values (?, ?, ?, ?, '{}'::jsonb, 'RUNNING', 0, null, null, null, '[]'::jsonb, statement_timestamp(), statement_timestamp(), ?::jsonb)",
                    UUID.randomUUID(),
                    threadId,
                    turnStartEntryId,
                    turnStartEntryId,
                    validReplayJson),
            "ck_harness_model_invocation_provider_replay_state must reject RUNNING");
    assertTrue(
        exRunning.getMessage().contains("ck_harness_model_invocation_provider_replay_state"),
        "expected violation of ck_harness_model_invocation_provider_replay_state but got: "
            + exRunning.getMessage());

    // 2. 拒绝 SUCCEEDED 但 result 为 null 携带 provider_replay_state
    DataIntegrityViolationException exResultNull =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                        + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                        + " values (?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', 0, null, null, null, '[]'::jsonb, statement_timestamp(), statement_timestamp(), ?::jsonb)",
                    UUID.randomUUID(),
                    threadId,
                    turnStartEntryId,
                    turnStartEntryId,
                    validReplayJson),
            "ck_harness_model_invocation_provider_replay_state must reject SUCCEEDED with null result");
    assertTrue(
        exResultNull.getMessage().contains("ck_harness_model_invocation_provider_replay_state"),
        "expected violation of ck_harness_model_invocation_provider_replay_state but got: "
            + exResultNull.getMessage());

    // 3. 拒绝 SUCCEEDED 但 result_entry_id 非 null（已物化落地）携带 provider_replay_state
    DataIntegrityViolationException exResultEntryNotNull =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                        + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                        + " values (?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', 0, ?::jsonb, null, ?, '[]'::jsonb, statement_timestamp(), statement_timestamp(), ?::jsonb)",
                    UUID.randomUUID(),
                    threadId,
                    turnStartEntryId,
                    turnStartEntryId,
                    validResultJson,
                    assistantEntryId,
                    validReplayJson),
            "ck_harness_model_invocation_provider_replay_state must reject attached result_entry_id");
    assertTrue(
        exResultEntryNotNull
            .getMessage()
            .contains("ck_harness_model_invocation_provider_replay_state"),
        "expected violation of ck_harness_model_invocation_provider_replay_state but got: "
            + exResultEntryNotNull.getMessage());

    // 4. 拒绝 JSON 非 object（如 array）
    DataIntegrityViolationException exNonObject =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                        + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                        + " values (?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', 0, ?::jsonb, null, null, '[]'::jsonb, statement_timestamp(), statement_timestamp(), '[\"not_object\"]'::jsonb)",
                    UUID.randomUUID(),
                    threadId,
                    turnStartEntryId,
                    turnStartEntryId,
                    validResultJson),
            "ck_harness_model_invocation_provider_replay_state must reject non-object JSON");
    assertTrue(
        exNonObject.getMessage().contains("ck_harness_model_invocation_provider_replay_state"),
        "expected violation of ck_harness_model_invocation_provider_replay_state but got: "
            + exNonObject.getMessage());

    // 5. 正向验证：SUCCEEDED + result 非 null + result_entry_id 为 null + JSON object 正常插入
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                    + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                    + " values (?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', 0, ?::jsonb, null, null, '[]'::jsonb, statement_timestamp(), statement_timestamp(), ?::jsonb)",
                UUID.randomUUID(),
                threadId,
                turnStartEntryId,
                turnStartEntryId,
                validResultJson,
                validReplayJson),
        "valid SUCCEEDED model invocation with replay state must succeed");
  }

  @Test
  void harnessSessionAndThreadNameColumnsEnforceNonBlankNames() {
    // 测试意图：真实 PostgreSQL 约束 ck_harness_session_name / ck_harness_thread_name 物理门禁验证。
    // 最小 schema 只防御最粗的空白串：btrim(name) <> ''；null 由 not null 拒绝。
    // 首尾空格规范化由应用写路径保证，数据库不拒绝带首尾空格的值。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000020");
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);

    // 1. session name null（not null 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_session (id, name, created_at) values (?, null, statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000021")),
        "null session name must be rejected");
    // 2. session name ''（check 拒绝）
    DataIntegrityViolationException exBlankSession =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_session (id, name, created_at) values (?, '', statement_timestamp())",
                    UUID.fromString("00000000-0000-0000-0000-000000000021")),
            "blank session name must be rejected");
    assertTrue(exBlankSession.getMessage().contains("ck_harness_session_name"));
    // 3. session name 全空白串（check 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_session (id, name, created_at) values (?, '   ', statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000021")),
        "whitespace-only session name must be rejected");
    // 4. 正向：合法值（含带首尾空格的应用值）可持久化；btrim 语义不拒绝 '  padded  '
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_session (id, name, created_at) values (?, 'hello world', statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000021")));
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_session (id, name, created_at) values (?, '  padded  ', statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000025")));

    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000022");
    UUID threadId = UUID.fromString("00000000-0000-0000-0000-000000000023");
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);
    jdbc.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 1, 0, statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        rootEntryId);

    // 5. thread name null（not null 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', null, false, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "null thread name must be rejected");
    // 6. thread name 全空白串（check 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '   ', false, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "whitespace-only thread name must be rejected");
    // 7. 正向：带首尾空格的应用值可持久化（btrim check 只防御纯空白串）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '  padded  ', false, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "padded thread name must be accepted (trim is app-enforced)");
  }
}
