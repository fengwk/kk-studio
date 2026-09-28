package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
   * Harness runtime 协议恰好八张表；业务表不使用 harness_ 前缀，因此 {@code harness_%} 全量查询结果必须精确等于该八表。
   *
   * <p>{@code harness_thread_join} 是原子源 prompt 接受与 join 契约的持久事实（无独立状态枚举，无 prompt/report 冗余）， 属于
   * runtime 协议空间。
   */
  private static final List<String> RUNTIME_TABLES =
      List.of(
          "harness_entry",
          "harness_model_invocation",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_thread_join",
          "harness_tool_invocation",
          "harness_work");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  @Test
  void schemaContainsExactlyTheEightHarnessTables() {
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
            "parent_thread_id",
            "head_entry_id",
            "creation_request_hash",
            "name",
            "yolo_enabled",
            "status",
            "next_command_sequence",
            "version",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void threadJoinTableStoresOnlyItsOwnedDurableState() {
    // 测试意图：验证 harness_thread_join 表列定义严格与契约对齐，无历史多余状态枚举或 prompt/report 冗余复制。
    List<String> columns =
        jdbc.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'harness_thread_join'
            order by ordinal_position
            """,
            String.class);
    assertEquals(
        List.of(
            "invocation_id",
            "request_hash",
            "parent_thread_id",
            "child_thread_id",
            "source_command_sequence",
            "after_version",
            "agent",
            "max_turns",
            "reminder_turn",
            "matched_idle_version",
            "result_head_entry_id",
            "delivery_command_sequence",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void everyStructuredDurablePayloadUsesJsonb() {
    // 只统计 runtime 协议表的 jsonb 列：session_blob_ref 无结构化载荷，不应出现。
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
            "harness_tool_invocation.input_receipt",
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
            "idx_harness_thread_join_child_pending",
            "idx_harness_thread_join_parent_pending",
            "idx_harness_thread_parent",
            "idx_harness_thread_session",
            "idx_harness_tool_invocation_model_nonterminal",
            "idx_harness_tool_invocation_pending",
            "idx_harness_work_available",
            "idx_harness_work_lease_until",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_model_invocation_result",
            "uk_harness_model_invocation_turn",
            "uk_harness_thread_command_idempotency",
            "uk_harness_thread_session",
            "uk_harness_tool_invocation_call_index"),
        indexes);

    String modelResult = indexDefinition("uk_harness_model_invocation_result");
    String workAvailable = indexDefinition("idx_harness_work_available");
    String workLease = indexDefinition("idx_harness_work_lease_until");
    String threadParent = indexDefinition("idx_harness_thread_parent");
    String threadSession = indexDefinition("idx_harness_thread_session");
    String stopRequest = indexDefinition("idx_harness_thread_command_stop_request");
    String childPending = indexDefinition("idx_harness_thread_join_child_pending");
    String parentPending = indexDefinition("idx_harness_thread_join_parent_pending");
    String toolNonterminal = indexDefinition("idx_harness_tool_invocation_model_nonterminal");
    assertTrue(modelResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(workAvailable.contains("(available_at, target_type, target_id)"));
    assertTrue(workLease.contains("(lease_until, target_type, target_id)"));
    assertTrue(workLease.contains("WHERE (lease_until IS NOT NULL)"));
    assertTrue(threadParent.contains("(parent_thread_id)"));
    assertTrue(threadSession.contains("(session_id, created_at, id)"));
    // Stop 幂等键索引必须按 stop_request_id 聚合并只覆盖非 null 行。
    assertTrue(stopRequest.contains("(thread_id, stop_request_id, sequence)"));
    assertTrue(stopRequest.contains("WHERE (stop_request_id IS NOT NULL)"));
    // Join 等待匹配与等待投递索引
    assertTrue(childPending.contains("(child_thread_id)"));
    assertTrue(childPending.contains("WHERE (matched_idle_version IS NULL)"));
    assertTrue(parentPending.contains("(parent_thread_id)"));
    assertTrue(parentPending.contains("WHERE (delivery_command_sequence IS NULL)"));
    // 未收尾 ToolInvocation 的按 ModelInvocation 查找路径必须只覆盖全部非终态（含人工输入等待），且按 model_invocation_id 建键。
    assertTrue(toolNonterminal.contains("(model_invocation_id)"));
    for (String nonterminal :
        List.of("WAITING_APPROVAL", "WAITING_INPUT", "READY", "DISPATCHING", "RUNNING")) {
      assertTrue(toolNonterminal.contains("'" + nonterminal + "'"));
    }
    for (String terminal : List.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN")) {
      assertFalse(toolNonterminal.contains("'" + terminal + "'"));
    }
    // 等待人工输入/审批的待处理分页必须按 (created_at, id) 稳定排序，且只覆盖两种等待状态。
    String pending = indexDefinition("idx_harness_tool_invocation_pending");
    assertTrue(pending.contains("(created_at, id)"));
    assertTrue(pending.contains("'WAITING_APPROVAL'"));
    assertTrue(pending.contains("'WAITING_INPUT'"));
    for (String nonWaiting : List.of("'READY'", "'DISPATCHING'", "'RUNNING'")) {
      assertFalse(pending.contains(nonWaiting));
    }
  }

  /**
   * 测试意图：真实 PostgreSQL 约束 ck_harness_tool_waiting_input / ck_harness_tool_input_receipt 物理门禁验证。
   *
   * <p>台账层的安全边界：等待人工输入必须有 binding 且无 result/error；回答回执只能与 SUCCEEDED + result 同存，且必须是携带非空
   * submissionId/actor/acceptedAt 的 JSON object。问答与审批互不代替在物理层表现为回执与 approval 是两列。
   */
  @Test
  void harnessToolInputReceiptConstraintCouplesReceiptToAnsweredSuccessOnly() {
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000030");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000031");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000032");
    UUID threadId = UUID.fromString("00000000-0000-0000-0000-000000000033");
    UUID assistantEntryId = UUID.fromString("00000000-0000-0000-0000-000000000034");
    UUID modelId = UUID.fromString("00000000-0000-0000-0000-000000000035");
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
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 'IDLE', 1, 0, statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        turnStartEntryId);
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
            + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at)"
            + " values (?, ?, ?, ?, '{}'::jsonb, 'READY', 0, null, null, null, '[]'::jsonb, statement_timestamp(), statement_timestamp())",
        modelId,
        threadId,
        turnStartEntryId,
        turnStartEntryId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[]}}'::jsonb, statement_timestamp())",
        assistantEntryId,
        sessionId,
        turnStartEntryId);

    String bindingJson = "{\"descriptor\":{\"name\":\"ask_user\"}}";
    String callJson = "{\"id\":\"call-1\",\"toolName\":\"ask_user\",\"argumentsJson\":\"{}\"}";
    String resultJson =
        "{\"toolCallId\":\"call-1\",\"contents\":[],\"error\":false,\"detailsJson\":\"{}\"}";
    String receiptJson =
        "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\","
            + "\"acceptedAt\":\"2026-09-27T00:00:00Z\"}";

    // 1. WAITING_INPUT 必须携带 binding（ck_harness_tool_waiting_input）
    DataIntegrityViolationException exNoBinding =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                insertToolInvocation(
                    UUID.randomUUID(),
                    0,
                    modelId,
                    assistantEntryId,
                    callJson,
                    null,
                    "WAITING_INPUT",
                    null,
                    null,
                    null),
            "WAITING_INPUT without binding must be rejected");
    assertTrue(
        exNoBinding.getMessage().contains("ck_harness_tool_waiting_input"),
        "expected violation of ck_harness_tool_waiting_input but got: " + exNoBinding.getMessage());
    // 2. WAITING_INPUT 不得携带 result（等待尚未产生业务结果）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            insertToolInvocation(
                UUID.randomUUID(),
                0,
                modelId,
                assistantEntryId,
                callJson,
                bindingJson,
                "WAITING_INPUT",
                resultJson,
                null,
                null),
        "WAITING_INPUT with result must be rejected");
    // 3. 正向：可回答的等待形状
    assertDoesNotThrow(
        () ->
            insertToolInvocation(
                UUID.randomUUID(),
                0,
                modelId,
                assistantEntryId,
                callJson,
                bindingJson,
                "WAITING_INPUT",
                null,
                null,
                null));
    // 4. 回执不得出现在非 SUCCEEDED 行（ck_harness_tool_input_receipt）
    DataIntegrityViolationException exReadyReceipt =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                insertToolInvocation(
                    UUID.randomUUID(),
                    1,
                    modelId,
                    assistantEntryId,
                    callJson,
                    bindingJson,
                    "READY",
                    null,
                    null,
                    receiptJson),
            "a receipt on READY must be rejected");
    assertTrue(
        exReadyReceipt.getMessage().contains("ck_harness_tool_input_receipt"),
        "expected violation of ck_harness_tool_input_receipt but got: "
            + exReadyReceipt.getMessage());
    // 5. SUCCEEDED 缺 result 时不得携带回执
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            insertToolInvocation(
                UUID.randomUUID(),
                1,
                modelId,
                assistantEntryId,
                callJson,
                bindingJson,
                "SUCCEEDED",
                null,
                null,
                receiptJson),
        "SUCCEEDED without result must be rejected");
    // 6. 回执字段必须齐备且非空白
    for (String incomplete :
        List.of(
            "{\"actor\":\"alice\",\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
            "{\"submissionId\":\"   \",\"actor\":\"alice\",\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
            "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"\","
                + "\"acceptedAt\":\"2026-09-27T00:00:00Z\"}",
            "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\",\"actor\":\"alice\"}",
            "[\"not_an_object\"]")) {
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              insertToolInvocation(
                  UUID.randomUUID(),
                  1,
                  modelId,
                  assistantEntryId,
                  callJson,
                  bindingJson,
                  "SUCCEEDED",
                  resultJson,
                  null,
                  incomplete),
          "incomplete receipt must be rejected: " + incomplete);
    }
    // 7. 正向：已回答成功的形状（结果 + 回执同存，无 approval）
    assertDoesNotThrow(
        () ->
            insertToolInvocation(
                UUID.randomUUID(),
                1,
                modelId,
                assistantEntryId,
                callJson,
                bindingJson,
                "SUCCEEDED",
                resultJson,
                null,
                receiptJson));
  }

  private void insertToolInvocation(
      UUID id,
      int callIndex,
      UUID modelId,
      UUID assistantEntryId,
      String callJson,
      String bindingJson,
      String status,
      String resultJson,
      String approvalJson,
      String receiptJson) {
    jdbc.update(
        "insert into harness_tool_invocation (id, model_invocation_id, assistant_entry_id, call_index, call, binding,"
            + " status, attempt, approval, result, effects, error, created_at, updated_at, input_receipt)"
            + " values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 0, ?::jsonb, ?::jsonb,"
            // 非 SUCCEEDED 行的 effects 必须是空批（ck_harness_tool_invocation_effects_status）。
            + " '{\"version\":1,\"customEntries\":[]}'::jsonb, null,"
            + " statement_timestamp(), statement_timestamp(), ?::jsonb)",
        id,
        modelId,
        assistantEntryId,
        callIndex,
        callJson,
        bindingJson,
        status,
        approvalJson,
        resultJson,
        receiptJson);
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
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
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
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        rootEntryId);

    // 5. thread name null（not null 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', null, false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "null thread name must be rejected");
    // 6. thread name 全空白串（check 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '   ', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "whitespace-only thread name must be rejected");
    // 7. 正向：带首尾空格的应用值可持久化（btrim check 只防御纯空白串）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '  padded  ', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId),
        "padded thread name must be accepted (trim is app-enforced)");
  }

  @Test
  void harnessThreadStatusAndParentConstraintsEnforceLifecycleAndNoSelfParent() {
    // 测试意图：真实 PostgreSQL CHECK 与 FK 约束验证 harness_thread 的生命周期状态（IDLE/ACTIVE/WAITING_CHILDREN）、
    // 不可自引用父关系（ck_harness_thread_parent_not_self）以及父 Thread 外键约束（fk_harness_thread_parent）。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000030");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000031");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000032");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000033");

    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);

    // 1. 拒绝非法 status 枚举（如 'RUNNING'）
    DataIntegrityViolationException exStatus =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', false, 'RUNNING', 1, 0, statement_timestamp(), statement_timestamp())",
                    parentThreadId,
                    sessionId,
                    rootEntryId),
            "ck_harness_thread_status must reject invalid status");
    assertTrue(exStatus.getMessage().contains("ck_harness_thread_status"));

    // 2. 正常插入合法 parent thread（status='ACTIVE'，parent_thread_id=null）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent-thread', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
                parentThreadId,
                sessionId,
                rootEntryId));

    // 3. 拒绝 parent_thread_id 指向自身
    DataIntegrityViolationException exSelfParent =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'self-thread', false, 'IDLE', 1, 0, statement_timestamp(), statement_timestamp())",
                    childThreadId,
                    sessionId,
                    childThreadId,
                    rootEntryId),
            "ck_harness_thread_parent_not_self must reject self-parent");
    assertTrue(exSelfParent.getMessage().contains("ck_harness_thread_parent_not_self"));

    // 4. 拒绝不存在的 parent_thread_id（FK 门禁）
    UUID nonExistentParentId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    DataIntegrityViolationException exFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child-thread', false, 'IDLE', 1, 0, statement_timestamp(), statement_timestamp())",
                    childThreadId,
                    sessionId,
                    nonExistentParentId,
                    rootEntryId),
            "fk_harness_thread_parent must reject non-existent parent");
    assertTrue(exFk.getMessage().contains("fk_harness_thread_parent"));

    // 5. 正向验证：合法 child thread 指向 parentThreadId
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child-thread', false, 'WAITING_CHILDREN', 1, 0, statement_timestamp(), statement_timestamp())",
                childThreadId,
                sessionId,
                parentThreadId,
                rootEntryId));
  }

  @Test
  void harnessThreadJoinReceiptPairConstraintEnforcesBothNullOrBothNonNull() {
    // 测试意图：真实 PostgreSQL CHECK 约束 ck_harness_thread_join_receipt_pair 物理门禁验证。
    // 规定 matched_idle_version 与 result_head_entry_id 必须同时为 NULL 或同时为非 NULL。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000040");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000041");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000042");
    UUID resultEntryId = UUID.fromString("00000000-0000-0000-0000-000000000043");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000044");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000045");
    UUID invocationId = UUID.fromString("00000000-0000-0000-0000-000000000046");

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
            + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"done\"}]}}'::jsonb, statement_timestamp())",
        resultEntryId,
        sessionId,
        turnStartEntryId);

    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', false, 'IDLE', 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        turnStartEntryId);

    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, applied_turn_start_entry_id, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, statement_timestamp())",
        childThreadId,
        UUID.randomUUID(),
        turnStartEntryId);

    // 1. 拒绝 matched_idle_version 非 null 但 result_head_entry_id 为 null
    DataIntegrityViolationException ex1 =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, 2, null, null, statement_timestamp(), statement_timestamp())",
                    invocationId,
                    parentThreadId,
                    childThreadId),
            "ck_harness_thread_join_receipt_pair must reject matched_idle_version without result_head_entry_id");
    assertTrue(ex1.getMessage().contains("ck_harness_thread_join_receipt_pair"));

    // 2. 拒绝 matched_idle_version 为 null 但 result_head_entry_id 非 null
    DataIntegrityViolationException ex2 =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, null, ?, null, statement_timestamp(), statement_timestamp())",
                    invocationId,
                    parentThreadId,
                    childThreadId,
                    resultEntryId),
            "ck_harness_thread_join_receipt_pair must reject result_head_entry_id without matched_idle_version");
    assertTrue(ex2.getMessage().contains("ck_harness_thread_join_receipt_pair"));

    // 3. 正向验证：同为 null（待匹配 join）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                    + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, null, null, null, statement_timestamp(), statement_timestamp())",
                invocationId,
                parentThreadId,
                childThreadId));

    // 4. 正向验证：同为非 null（已匹配 receipt）
    UUID matchedInvocationId = UUID.fromString("00000000-0000-0000-0000-000000000047");
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                    + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, 2, ?, null, statement_timestamp(), statement_timestamp())",
                matchedInvocationId,
                parentThreadId,
                childThreadId,
                resultEntryId));
  }

  @Test
  void harnessThreadJoinMatchedOrderAndDeliveryConstraints() {
    // 测试意图：真实 PostgreSQL CHECK 与 FK 约束验证 harness_thread_join 的 matched > after
    // 规则（ck_harness_thread_join_matched_order）
    // 以及 delivery 必须已 matched 且 parent 非空规则（ck_harness_thread_join_delivery）与级联复合 FK。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000050");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000051");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000052");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000053");

    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', false, 'ACTIVE', 2, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', false, 'IDLE', 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        rootEntryId);

    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', statement_timestamp())",
        childThreadId,
        UUID.randomUUID());
    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'CUSTOM_MESSAGE', '{\"text\":\"delivered\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', statement_timestamp())",
        parentThreadId,
        UUID.randomUUID());

    // 1. 拒绝 matched_idle_version <= after_version（如 matched=2, after=2）
    DataIntegrityViolationException exMatchedOrder =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 2, 'subagent', 10, 0, 2, ?, null, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    rootEntryId),
            "ck_harness_thread_join_matched_order must reject matched <= after");
    assertTrue(exMatchedOrder.getMessage().contains("ck_harness_thread_join_matched_order"));

    // 2. 拒绝未 matched 时设置 delivery_command_sequence
    DataIntegrityViolationException exDeliveryUnmatched =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, null, null, 1, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId),
            "ck_harness_thread_join_delivery must reject delivery when unmatched");
    assertTrue(exDeliveryUnmatched.getMessage().contains("ck_harness_thread_join_delivery"));

    // 3. 拒绝 parent_thread_id 为空（root one-shot）时设置 delivery_command_sequence
    DataIntegrityViolationException exDeliveryRoot =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', null, ?, 1, 1, 'subagent', 10, 0, 3, ?, 1, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    childThreadId,
                    rootEntryId),
            "ck_harness_thread_join_delivery must reject delivery when parent_thread_id is null");
    assertTrue(exDeliveryRoot.getMessage().contains("ck_harness_thread_join_delivery"));

    // 4. 拒绝不存在的 delivery_command_sequence（复合外键 fk_harness_thread_join_delivery_command 门禁）
    DataIntegrityViolationException exDeliveryFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, 3, ?, 99, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    rootEntryId),
            "fk_harness_thread_join_delivery_command must reject non-existent command");
    assertTrue(exDeliveryFk.getMessage().contains("fk_harness_thread_join_delivery_command"));

    // 5. 正向验证：合法的 matched > after 且包含已投递 delivery command
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                    + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, 3, ?, 1, statement_timestamp(), statement_timestamp())",
                UUID.randomUUID(),
                parentThreadId,
                childThreadId,
                rootEntryId));
  }

  @Test
  void harnessThreadJoinSourceCommandAndResultHeadForeignKeys() {
    // 测试意图：真实 PostgreSQL FK 门禁验证 harness_thread_join
    // 的复合源命令外键（fk_harness_thread_join_source_command）
    // 以及结果 head Entry 外键（fk_harness_thread_join_result_head）。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000060");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000061");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000062");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000063");

    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, 'session-demo', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb, statement_timestamp())",
        rootEntryId,
        sessionId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', false, 'ACTIVE', 1, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, yolo_enabled, status, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', false, 'IDLE', 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        rootEntryId);

    // 1. 拒绝源命令不存在（fk_harness_thread_join_source_command 门禁）
    DataIntegrityViolationException exSourceCmd =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, null, null, null, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId),
            "fk_harness_thread_join_source_command must reject non-existent source command");
    assertTrue(exSourceCmd.getMessage().contains("fk_harness_thread_join_source_command"));

    // 插入合法的 child command
    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', statement_timestamp())",
        childThreadId,
        UUID.randomUUID());

    // 2. 拒绝结果 head Entry 不存在（fk_harness_thread_join_result_head 门禁）
    UUID nonExistentEntryId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    DataIntegrityViolationException exResultHead =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, after_version, agent, max_turns, reminder_turn, matched_idle_version, result_head_entry_id, delivery_command_sequence, created_at, updated_at)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 1, 'subagent', 10, 0, 2, ?, null, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    nonExistentEntryId),
            "fk_harness_thread_join_result_head must reject non-existent entry");
    assertTrue(exResultHead.getMessage().contains("fk_harness_thread_join_result_head"));
  }
}
