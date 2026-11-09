package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class PostgresqlHarnessSchemaTest {

  /**
   * Harness runtime 协议的专有表恰好八张，Work 位于共享的 {@code durable_work}；Builtin 模型表不使用 harness_ 前缀，因此 {@code
   * harness_%} 全量查询结果必须精确等于这八张表。
   *
   * <p>{@code harness_thread_join} 是原子源 prompt 接受与 join 契约的持久事实（无独立状态枚举，无 prompt/report 冗余）， {@code
   * harness_thread_stop_receipt} 是 Stop 回执集合的持久事实，均属于 runtime 协议空间。
   */
  private static final List<String> RUNTIME_TABLES =
      List.of(
          "harness_entry",
          "harness_execution_exit",
          "harness_session",
          "harness_thread",
          "harness_thread_command",
          "harness_thread_join",
          "harness_thread_stop_receipt",
          "harness_tool_invocation");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PostgresqlHarnessStoreFixture.reset();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
  }

  @Test
  void schemaContainsExactlyTheHarnessTables() {
    // 精确查询全部 harness_% 表：任何业务表（如 session_blob_ref）不得混入 runtime 协议空间；Work 在共享的 durable_work 表。
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
            "root_thread_id",
            "yolo_enabled",
            "spawn_trigger",
            "constraints",
            "execution_control",
            "input_through_sequence",
            "next_command_sequence",
            "version",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void executionExitTableStoresAbandonmentSeparatelyFromRealExitEvidence() {
    UUID threadId = seedExecutionExitThread();
    List<String> columns =
        jdbc.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'harness_execution_exit'
            order by ordinal_position
            """,
            String.class);
    assertEquals(
        List.of(
            "execution_id",
            "thread_id",
            "invocation_id",
            "attempt",
            "owner_lease_token",
            "kind",
            "parent_execution_id",
            "environment_id",
            "state",
            "registered_at",
            "confirmed_at",
            "abandoned_at"),
        columns);
    List<String> checks =
        jdbc.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public' and table_name = 'harness_execution_exit'
              and constraint_type = 'CHECK'
              and constraint_name like 'ck%'
            order by constraint_name
            """,
            String.class);
    assertEquals(
        List.of(
            "ck_harness_execution_exit_kind",
            "ck_harness_execution_exit_owner_exited",
            "ck_harness_execution_exit_state",
            "ck_harness_execution_exit_time"),
        checks);

    UUID executionId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,"
            + " owner_lease_token, kind, parent_execution_id, state, registered_at, confirmed_at,"
            + " abandoned_at) values (?, ?, ?, 1, '00000000-0000-0000-0000-0000000000a3', 'TOOL', null, 'UNCONFIRMED',"
            + " timestamp with time zone '2026-01-01 00:00:00.123+00', null, null)",
        executionId,
        threadId,
        UUID.randomUUID());
    DataIntegrityViolationException beforeRegistration =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "update harness_execution_exit"
                        + " set confirmed_at = registered_at - interval '1 millisecond'"
                        + " where execution_id = ?",
                    executionId));
    assertTrue(beforeRegistration.getMessage().contains("ck_harness_execution_exit_time"));

    DataIntegrityViolationException unknownKind =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "update harness_execution_exit set kind = 'BOGUS' where execution_id = ?",
                    executionId));
    assertTrue(unknownKind.getMessage().contains("ck_harness_execution_exit_kind"));

    // ABANDONED 只写 abandoned_at；后续 OWNER_EXITED 增加真实确认时间并保留原放弃证据，二者独立。
    jdbc.update(
        "update harness_execution_exit"
            + " set state = 'ABANDONED', confirmed_at = null,"
            + " abandoned_at = registered_at + interval '1 millisecond'"
            + " where execution_id = ?",
        executionId);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_execution_exit"
                + " where execution_id = ? and state = 'ABANDONED'"
                + " and confirmed_at is null and abandoned_at >= registered_at",
            Integer.class,
            executionId));
    jdbc.update(
        "update harness_execution_exit"
            + " set state = 'OWNER_EXITED', confirmed_at = registered_at"
            + " where execution_id = ?",
        executionId);
    assertEquals(
        1,
        jdbc.queryForObject(
            "select count(*) from harness_execution_exit"
                + " where execution_id = ? and state = 'OWNER_EXITED'"
                + " and confirmed_at is not null and abandoned_at is not null",
            Integer.class,
            executionId));

    // 本地所有者退出只属于 DRIVER/TOOL；REMOTE 不能冒领 OWNER_EXITED。
    DataIntegrityViolationException remoteOwnerExited =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_execution_exit (execution_id, thread_id, invocation_id,"
                        + " attempt, owner_lease_token, kind, parent_execution_id, environment_id,"
                        + " state, registered_at, confirmed_at, abandoned_at) values (?, ?, ?, 1,"
                        + " '00000000-0000-0000-0000-0000000000a4', 'REMOTE', ?, null,"
                        + " 'OWNER_EXITED', timestamp with time zone '2026-01-01 00:00:00.123+00',"
                        + " timestamp with time zone '2026-01-01 00:00:00.124+00', null)",
                    UUID.randomUUID(),
                    threadId,
                    UUID.randomUUID(),
                    executionId));
    assertTrue(remoteOwnerExited.getMessage().contains("ck_harness_execution_exit_owner_exited"));
  }

  @Test
  void executionExitAndDurableWorkAcceptDriverKindAndCustomTarget() {
    UUID threadId = seedExecutionExitThread();
    UUID agentExecutionId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();

    // 1. harness_execution_exit 接受 kind = 'DRIVER'
    jdbc.update(
        "insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,"
            + " owner_lease_token, kind, parent_execution_id, state, registered_at, confirmed_at,"
            + " abandoned_at) values (?, ?, ?, 1, '00000000-0000-0000-0000-0000000000a1', 'DRIVER', null, 'UNCONFIRMED',"
            + " timestamp with time zone '2026-01-01 00:00:00.000+00', null, null)",
        agentExecutionId,
        threadId,
        invocationId);

    // 数据库只约束 kind/state 枚举与时间顺序；kind/state 配对由应用维护。
    jdbc.update(
        "update harness_execution_exit set state = 'STUDIO_EXITED', confirmed_at = registered_at"
            + " where execution_id = ?",
        agentExecutionId);

    // 2. REMOTE 可以以 DRIVER 执行代次为 parent_execution
    UUID envId = UUID.randomUUID();
    jdbc.update(
        "insert into environment (id, name, note, registration_token_hash, daemon_config,"
            + " created_at, updated_at)"
            + " values (?, 'env-1', null,"
            + " '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
            + " '{\"studioUrl\":\"https://studio.example.com\",\"bashExecutable\":null,"
            + "\"lsp\":null,\"proxyUrl\":null}'::jsonb, now(), now())",
        envId);
    UUID remoteExecutionId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_execution_exit (execution_id, thread_id, invocation_id, attempt,"
            + " owner_lease_token, kind, parent_execution_id, environment_id, state, registered_at,"
            + " confirmed_at, abandoned_at) values (?, ?, ?, 1, '00000000-0000-0000-0000-0000000000a2', 'REMOTE', ?, ?,"
            + " 'REMOTE_ACKNOWLEDGED', timestamp with time zone '2026-01-01 00:00:00.000+00',"
            + " timestamp with time zone '2026-01-01 00:00:00.100+00', null)",
        remoteExecutionId,
        threadId,
        invocationId,
        agentExecutionId,
        envId);

    // 3. durable_work 接受由模块定义的 target 与 required_environment_id。
    jdbc.update(
        "insert into durable_work (target_type, target_id, available_at, wake_version,"
            + " required_environment_id) values ('CUSTOM_DRIVER', ?, now(), 1, ?)",
        threadId,
        envId);

    // 类型校验只约束格式，不枚举具体 Driver。
    DataIntegrityViolationException invalidType =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into durable_work (target_type, target_id, available_at, wake_version,"
                        + " required_environment_id) values ('custom', ?, now(), 1, ?)",
                    UUID.randomUUID(),
                    envId));
    assertTrue(invalidType.getMessage().contains("ck_durable_work_target_type"));
  }

  private UUID seedExecutionExitThread() {
    UUID sessionId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_session (id, name, created_at)"
            + " values (?, 'session-exit', statement_timestamp())",
        sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, null, 'ROOT', '{\"title\":\"root\"}'::jsonb,"
            + " statement_timestamp())",
        rootEntryId,
        sessionId);
    jdbc.update(
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
            + " 'thread-exit', ?, false, 'MODEL_TOOL',"
            + " '{\"maxOutputTokens\":null,"
            + "\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0,"
            + " statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        rootEntryId,
        threadId);
    return threadId;
  }

  @Test
  void threadJoinTableStoresOnlyItsOwnedDurableState() {
    // 测试意图：验证 harness_thread_join 表列定义严格与契约对齐，无多余状态枚举或 prompt/report 冗余复制。
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
            "agent",
            "delivery",
            "superseded_by_invocation_id",
            "max_turns",
            "reminder_turn",
            "terminal_entry_id",
            "final_answer_entry_id",
            "delivery_command_sequence",
            "created_at",
            "updated_at"),
        columns);
  }

  @Test
  void threadStopReceiptTableStoresRootedReceiptSetOnly() {
    // 测试意图：验证 harness_thread_stop_receipt 只持久化身份与停止边界，root_stop_request_id 仅用于归组。
    List<String> columns =
        jdbc.queryForList(
            """
            select column_name
            from information_schema.columns
            where table_schema = 'public' and table_name = 'harness_thread_stop_receipt'
            order by ordinal_position
            """,
            String.class);
    assertEquals(
        List.of(
            "thread_id",
            "stop_request_id",
            "root_stop_request_id",
            "stopped_turn_end_entry_id",
            "created_at"),
        columns);
    // 回执只持久化身份与停止边界；root_stop_request_id 只是归组键，没有 root Thread 外键。
    List<String> foreignKeys =
        jdbc.queryForList(
            """
            select constraint_name
            from information_schema.table_constraints
            where table_schema = 'public' and table_name = 'harness_thread_stop_receipt'
              and constraint_type = 'FOREIGN KEY'
            order by constraint_name
            """,
            String.class);
    assertEquals(
        List.of("fk_harness_thread_stop_receipt_thread", "fk_harness_thread_stop_receipt_turn_end"),
        foreignKeys);
  }

  @Test
  void everyStructuredDurablePayloadUsesJsonb() {
    // 统计 runtime 协议表和 Builtin 模型表的 jsonb 列。
    List<String> jsonbColumns =
        jdbc.queryForList(
            """
            select table_name || '.' || column_name
            from information_schema.columns
            where table_schema = 'public'
              and data_type = 'jsonb'
              and (table_name like 'harness\\_%' or table_name = 'builtin_model_invocation')
            order by table_name, column_name
            """,
            String.class);
    assertEquals(
        List.of(
            "builtin_model_invocation.error",
            "builtin_model_invocation.failed_attempts",
            "builtin_model_invocation.group_execution",
            "builtin_model_invocation.provider_replay_state",
            "builtin_model_invocation.request_spec",
            "builtin_model_invocation.result",
            "builtin_model_invocation.stream_checkpoint",
            "harness_entry.payload",
            "harness_entry.provider_replay_state",
            "harness_thread.constraints",
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
              and (tablename like 'harness\\_%' or tablename = 'builtin_model_invocation')
              and indexname not like '%_pkey'
            order by indexname
            """,
            String.class);
    assertEquals(
        List.of(
            "idx_harness_entry_parent",
            "idx_harness_entry_session_seq",
            "idx_harness_execution_exit_invocation",
            "idx_harness_execution_exit_parent",
            "idx_harness_execution_exit_thread",
            "idx_harness_execution_exit_thread_unconfirmed",
            "idx_harness_execution_exit_unconfirmed_remote",
            "idx_harness_thread_command_queued",
            "idx_harness_thread_command_stop_request",
            "idx_harness_thread_join_child_pending",
            "idx_harness_thread_parent",
            "idx_harness_thread_root",
            "idx_harness_thread_session",
            "idx_harness_thread_stop_receipt_root_request",
            "idx_harness_tool_invocation_pending",
            "idx_harness_tool_invocation_thread_nonterminal",
            "pk_harness_thread_stop_receipt",
            "uk_builtin_model_invocation_result",
            "uk_builtin_model_invocation_turn",
            "uk_harness_entry_session_id",
            "uk_harness_entry_single_root",
            "uk_harness_thread_command_idempotency",
            "uk_harness_thread_root_name",
            "uk_harness_thread_session",
            "uk_harness_tool_invocation_call_index"),
        indexes);

    String modelResult = indexDefinition("uk_builtin_model_invocation_result");
    String workAvailable = indexDefinition("idx_durable_work_available");
    String workLease = indexDefinition("idx_durable_work_lease_until");
    String threadParent = indexDefinition("idx_harness_thread_parent");
    String threadSession = indexDefinition("idx_harness_thread_session");
    // Entry 列表按存储追加序做 keyset 分页，Session 内读取必须走 (session_id, entry_seq) 索引。
    assertTrue(
        indexDefinition("idx_harness_entry_session_seq").contains("(session_id, entry_seq)"));
    String stopRequest = indexDefinition("idx_harness_thread_command_stop_request");
    String childPending = indexDefinition("idx_harness_thread_join_child_pending");
    String toolNonterminal = indexDefinition("idx_harness_tool_invocation_thread_nonterminal");
    // 索引必须保持既定形状；Work 的 claim 与过期扫描索引覆盖全部 target type。
    assertTrue(modelResult.contains("WHERE (result_entry_id IS NOT NULL)"));
    assertTrue(workAvailable.contains("(available_at, target_type, target_id)"));
    assertTrue(workLease.contains("(lease_until, target_type, target_id)"));
    assertTrue(workLease.contains("WHERE (lease_until IS NOT NULL)"));
    assertTrue(threadParent.contains("(parent_thread_id)"));
    assertTrue(threadSession.contains("(session_id, created_at, id)"));
    String rootName = indexDefinition("uk_harness_thread_root_name");
    assertTrue(rootName.contains("CREATE UNIQUE INDEX"));
    assertTrue(rootName.contains("(session_id, name)"));
    assertTrue(rootName.contains("WHERE (parent_thread_id IS NULL)"));
    // Stop 幂等键索引必须按 stop_request_id 聚合并只覆盖非 null 行。
    assertTrue(stopRequest.contains("(thread_id, stop_request_id, sequence)"));
    assertTrue(stopRequest.contains("WHERE (stop_request_id IS NOT NULL)"));
    // Join 等待匹配索引（新模型按 terminal_entry_id 判定 matched；已 supersede 的 join 不参与）
    assertTrue(childPending.contains("(child_thread_id)"));
    assertTrue(
        childPending.contains(
            "WHERE ((terminal_entry_id IS NULL) AND (superseded_by_invocation_id IS NULL))"));
    // 执行代次只增不删到 Thread 深删：按 thread_id 删除与 Thread 外键检查、以及自引用 parent 外键检查都必须走索引。
    assertTrue(indexDefinition("idx_harness_execution_exit_thread").contains("(thread_id)"));
    String exitParent = indexDefinition("idx_harness_execution_exit_parent");
    assertTrue(exitParent.contains("(parent_execution_id)"));
    assertTrue(exitParent.contains("WHERE (parent_execution_id IS NOT NULL)"));
    // Stop 回执只按 root_stop_request_id 检索；不同树复用同一请求 UUID 由根行 thread_id 隔离。
    String stopReceiptRoot = indexDefinition("idx_harness_thread_stop_receipt_root_request");
    assertTrue(stopReceiptRoot.contains("(root_stop_request_id, thread_id)"));
    assertTrue(
        indexDefinition("idx_harness_execution_exit_invocation")
            .contains("(invocation_id, attempt)"));
    // 未收尾 ToolInvocation 的公共查找路径必须只覆盖全部非终态（含人工输入等待），且按 thread_id 建键。
    assertTrue(toolNonterminal.contains("(thread_id)"));
    assertEquals(
        Set.of("WAITING_APPROVAL", "WAITING_INPUT", "READY", "RUNNING"),
        Pattern.compile("'([^']+)'")
            .matcher(toolNonterminal)
            .results()
            .map(match -> match.group(1))
            .collect(Collectors.toSet()));
    // 等待人工输入/审批的待处理分页必须按 (created_at, id) 稳定排序，且只覆盖两种等待状态。
    String pending = indexDefinition("idx_harness_tool_invocation_pending");
    assertTrue(pending.contains("(created_at, id)"));
    assertTrue(pending.contains("'WAITING_APPROVAL'"));
    assertTrue(pending.contains("'WAITING_INPUT'"));
    for (String nonWaiting : List.of("'READY'", "'RUNNING'")) {
      assertFalse(pending.contains(nonWaiting));
    }
  }

  /** 测试意图：tool 回执只保留 JSON object 形状约束；状态与回执的耦合由应用维护。 */
  @Test
  void harnessToolInputReceiptKeepsOnlyJsonObjectShape() {
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000030");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000031");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000032");
    UUID threadId = UUID.fromString("00000000-0000-0000-0000-000000000033");
    UUID assistantEntryId = UUID.fromString("00000000-0000-0000-0000-000000000034");
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
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?,"
            + " '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
            + " 'thread-demo', ?, false, 'MODEL_TOOL',"
            + " '{\"maxOutputTokens\":null,"
            + "\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0,"
            + " statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        turnStartEntryId,
        threadId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE',"
            + " '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[]}}'::jsonb,"
            + " statement_timestamp())",
        assistantEntryId,
        sessionId,
        turnStartEntryId);

    String callJson = "{\"id\":\"call-1\",\"toolName\":\"ask_user\",\"argumentsJson\":\"{}\"}";
    String bindingJson = "{\"descriptor\":{\"name\":\"ask_user\"}}";
    String resultJson =
        "{\"toolCallId\":\"call-1\",\"contents\":[],\"error\":false,\"detailsJson\":\"{}\"}";

    // 回执列仍是 JSON object 形状；缺字段或非对象不是数据库拒绝范围，只有数组/标量被拒绝。
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            insertToolInvocation(
                UUID.randomUUID(),
                0,
                threadId,
                turnStartEntryId,
                assistantEntryId,
                callJson,
                bindingJson,
                "SUCCEEDED",
                resultJson,
                null,
                "[\"not_an_object\"]"),
        "a non-object input receipt must be rejected");
    UUID invocationId = UUID.randomUUID();
    assertDoesNotThrow(
        () ->
            insertToolInvocation(
                invocationId,
                0,
                threadId,
                turnStartEntryId,
                assistantEntryId,
                callJson,
                bindingJson,
                "SUCCEEDED",
                resultJson,
                null,
                "{\"submissionId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"actor\":\"alice\",\"acceptedAt\":\"2026-09-27T00:00:00Z\"}"));

    // 数据库接受全部当前状态，拒绝不属于状态枚举的值。
    String statusCheck =
        jdbc.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint"
                + " where conname = 'ck_harness_tool_invocation_status'",
            String.class);
    assertEquals(
        Set.of(ToolInvocationStatus.values()).stream()
            .map(ToolInvocationStatus::name)
            .collect(Collectors.toSet()),
        Pattern.compile("'([^']+)'")
            .matcher(statusCheck)
            .results()
            .map(match -> match.group(1))
            .collect(Collectors.toSet()));
    for (ToolInvocationStatus status : ToolInvocationStatus.values()) {
      assertEquals(
          1,
          jdbc.update(
              "update harness_tool_invocation set status = ? where id = ?",
              status.name(),
              invocationId));
    }
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "update harness_tool_invocation set status = 'INVALID' where id = ?",
                invocationId));
  }

  private void insertToolInvocation(
      UUID id,
      int callIndex,
      UUID threadId,
      UUID turnStartEntryId,
      UUID assistantEntryId,
      String callJson,
      String bindingJson,
      String status,
      String resultJson,
      String approvalJson,
      String receiptJson) {
    jdbc.update(
        "insert into harness_tool_invocation (id, thread_id, turn_start_entry_id, assistant_entry_id, call_index, call, binding,"
            + " status, attempt, approval, result, effects, error, created_at, updated_at, input_receipt)"
            + " values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, 0, ?::jsonb, ?::jsonb,"
            + " '{\"customEntries\":[]}'::jsonb, null,"
            + " statement_timestamp(), statement_timestamp(), ?::jsonb)",
        id,
        threadId,
        turnStartEntryId,
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
  void harnessEntryProviderReplayStateKeepsOnlyJsonObjectShape() {
    // provider_replay_state 的适用 Entry 类型由应用维护；数据库只拒绝非 object JSON。
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
    String replayJson =
        "{\"format\":\"openai_responses\",\"affinity\":{},\"sourcePrefixHash\":"
            + "\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"payload\":{}}";

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                    + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\"}}'::jsonb,"
                    + " statement_timestamp(), '[\"not_an_object\"]'::jsonb)",
                UUID.randomUUID(),
                sessionId,
                rootEntryId),
        "a non-object provider replay state must be rejected");
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at, provider_replay_state)"
                    + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\"}}'::jsonb,"
                    + " statement_timestamp(), ?::jsonb)",
                UUID.randomUUID(),
                sessionId,
                rootEntryId,
                replayJson));
  }

  @Test
  void builtinModelInvocationProviderReplayStateKeepsOnlyJsonObjectShape() {
    // 适用状态与 result 的关联由应用维护；数据库只拒绝非 object JSON。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000012");
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
        "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash, name,"
            + " root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?,"
            + " '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',"
            + " 'thread-demo', ?, false, 'USER',"
            + " '{\"maxOutputTokens\":null,"
            + "\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0,"
            + " statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        turnStartEntryId,
        threadId);
    String replayJson =
        "{\"format\":\"openai_responses\",\"affinity\":{},\"sourcePrefixHash\":"
            + "\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"payload\":{}}";

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into builtin_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                    + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                    + " values (?, ?, ?, ?, '{}'::jsonb, 'RUNNING', 0, null, null, null, '[]'::jsonb,"
                    + " statement_timestamp(), statement_timestamp(), '[\"not_object\"]'::jsonb)",
                UUID.randomUUID(),
                threadId,
                turnStartEntryId,
                turnStartEntryId),
        "a non-object provider replay state must be rejected");
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into builtin_model_invocation (id, thread_id, turn_start_entry_id, request_head_entry_id, request_spec,"
                    + " status, attempt, result, error, result_entry_id, failed_attempts, created_at, updated_at, provider_replay_state)"
                    + " values (?, ?, ?, ?, '{}'::jsonb, 'RUNNING', 0, null, null, null, '[]'::jsonb,"
                    + " statement_timestamp(), statement_timestamp(), ?::jsonb)",
                UUID.randomUUID(),
                threadId,
                turnStartEntryId,
                turnStartEntryId,
                replayJson));
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
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
        threadId,
        sessionId,
        rootEntryId,
        threadId);

    // 5. thread name null（not null 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', null, ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId,
                UUID.fromString("00000000-0000-0000-0000-000000000024")),
        "null thread name must be rejected");
    // 6. thread name 全空白串（check 拒绝）
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '   ', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId,
                UUID.fromString("00000000-0000-0000-0000-000000000024")),
        "whitespace-only thread name must be rejected");
    // 7. 正向：带首尾空格的应用值可持久化（btrim check 只防御纯空白串）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '  padded  ', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                UUID.fromString("00000000-0000-0000-0000-000000000024"),
                sessionId,
                rootEntryId,
                UUID.fromString("00000000-0000-0000-0000-000000000024")),
        "padded thread name must be accepted (trim is app-enforced)");
  }

  @Test
  void harnessThreadExecutionControlAndParentConstraintsEnforceLifecycleAndNoSelfParent() {
    // 测试意图：真实 PostgreSQL CHECK 与 FK 约束验证 harness_thread 的持久执行控制（RUNNABLE/STOPPED）、
    // 输入水位必须小于下一条命令序号（ck_harness_thread_input_through）、不可自引用父关系
    // （ck_harness_thread_parent_not_self）以及父 Thread 外键约束（fk_harness_thread_parent）。
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

    // 1. 拒绝非法 execution_control 枚举（如 'RUNNING'）
    DataIntegrityViolationException exControl =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNING', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                    parentThreadId,
                    sessionId,
                    rootEntryId,
                    parentThreadId),
            "ck_harness_thread_execution_control must reject invalid execution control");
    assertTrue(exControl.getMessage().contains("ck_harness_thread_execution_control"));

    // 2. 拒绝 input_through_sequence 不小于 next_command_sequence（如 2 >= 2）
    DataIntegrityViolationException exWatermark =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'thread-demo', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 2, 2, 0, statement_timestamp(), statement_timestamp())",
                    parentThreadId,
                    sessionId,
                    rootEntryId,
                    parentThreadId),
            "ck_harness_thread_input_through must reject watermark >= next_command_sequence");
    assertTrue(exWatermark.getMessage().contains("ck_harness_thread_input_through"));

    // 3. 正常插入合法 parent thread（execution_control='RUNNABLE'，parent_thread_id=null）
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent-thread', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                parentThreadId,
                sessionId,
                rootEntryId,
                parentThreadId));

    // 4. 拒绝 parent_thread_id 指向自身
    DataIntegrityViolationException exSelfParent =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'self-thread', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                    childThreadId,
                    sessionId,
                    childThreadId,
                    rootEntryId,
                    parentThreadId),
            "ck_harness_thread_parent_not_self must reject self-parent");
    assertTrue(exSelfParent.getMessage().contains("ck_harness_thread_parent_not_self"));

    // 5. 拒绝不存在的 parent_thread_id（FK 门禁）
    UUID nonExistentParentId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    DataIntegrityViolationException exFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                        + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child-thread', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                    childThreadId,
                    sessionId,
                    nonExistentParentId,
                    rootEntryId,
                    parentThreadId),
            "fk_harness_thread_parent must reject non-existent parent");
    assertTrue(exFk.getMessage().contains("fk_harness_thread_parent"));

    // 6. 正向验证：合法 child thread 指向 parentThreadId，且 STOPPED 控制可持久化
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
                    + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child-thread', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'STOPPED', 0, 1, 0, statement_timestamp(), statement_timestamp())",
                childThreadId,
                sessionId,
                parentThreadId,
                rootEntryId,
                parentThreadId));
  }

  @Test
  void harnessThreadJoinEntryReferencesUseForeignKeys() {
    // 测试意图：terminal_entry_id / final_answer_entry_id 的外键门禁；两者与 delivery 的状态关联由应用维护。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000040");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000041");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000042");
    UUID terminalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000043");
    UUID finalAnswerEntryId = UUID.fromString("00000000-0000-0000-0000-000000000044");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000045");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000046");

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
        terminalEntryId,
        sessionId,
        turnStartEntryId);
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at)"
            + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"summary\"}]}}'::jsonb, statement_timestamp())",
        finalAnswerEntryId,
        sessionId,
        turnStartEntryId);

    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId,
        parentThreadId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        turnStartEntryId,
        parentThreadId);

    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', statement_timestamp())",
        childThreadId,
        UUID.randomUUID());

    // 2. terminal Entry 不存在 → 违反 fk_harness_thread_join_terminal_entry
    DataIntegrityViolationException exTerminalFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, ?, null, null, statement_timestamp(), statement_timestamp(), 'SETTLE_OWNER_TURN')",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    UUID.fromString("00000000-0000-0000-0000-000000000099")),
            "fk_harness_thread_join_terminal_entry must reject non-existent entry");
    assertTrue(exTerminalFk.getMessage().contains("fk_harness_thread_join_terminal_entry"));

    // 3. final_answer Entry 不存在 → 违反 fk_harness_thread_join_final_answer_entry
    DataIntegrityViolationException exFinalAnswerFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, ?, ?, null, statement_timestamp(), statement_timestamp(), 'SETTLE_OWNER_TURN')",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    terminalEntryId,
                    UUID.fromString("00000000-0000-0000-0000-000000000098")),
            "fk_harness_thread_join_final_answer_entry must reject non-existent entry");
    assertTrue(exFinalAnswerFk.getMessage().contains("fk_harness_thread_join_final_answer_entry"));
  }

  @Test
  void harnessThreadJoinDeliveryCommandUsesForeignKey() {
    // 测试意图：交付命令的外键 fk_harness_thread_join_delivery_command；交付与 matched/parent 的关联由应用维护。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000050");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000051");
    UUID turnStartEntryId = UUID.fromString("00000000-0000-0000-0000-000000000052");
    UUID terminalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000053");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000054");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000055");

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
        terminalEntryId,
        sessionId,
        turnStartEntryId);

    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 2, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId,
        parentThreadId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        turnStartEntryId,
        parentThreadId);

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

    // 3. 不存在的 delivery_command_sequence → 违反 fk_harness_thread_join_delivery_command
    DataIntegrityViolationException exDeliveryFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, ?, null, 99, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId,
                    terminalEntryId),
            "fk_harness_thread_join_delivery_command must reject non-existent command");
    assertTrue(exDeliveryFk.getMessage().contains("fk_harness_thread_join_delivery_command"));

    // 4. 正向验证：已 matched 且父非空、交付命令存在的 join 合法
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                    + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, ?, null, 1, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
                UUID.randomUUID(),
                parentThreadId,
                childThreadId,
                terminalEntryId));
  }

  @Test
  void harnessThreadJoinSourceCommandForeignKey() {
    // 测试意图：真实 PostgreSQL 复合外键验证 fk_harness_thread_join_source_command
    // 只接受 child Thread 上真实存在的源命令，且合法的 pending join（terminal 为空）可写入。
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
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'parent', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId,
        parentThreadId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 'child', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        rootEntryId,
        parentThreadId);

    // 1. 源命令不存在 → 违反 fk_harness_thread_join_source_command
    DataIntegrityViolationException exSourceCmd =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                        + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, null, null, null, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
                    UUID.randomUUID(),
                    parentThreadId,
                    childThreadId),
            "fk_harness_thread_join_source_command must reject non-existent source command");
    assertTrue(exSourceCmd.getMessage().contains("fk_harness_thread_join_source_command"));

    // 插入合法的 child 源命令
    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', statement_timestamp())",
        childThreadId,
        UUID.randomUUID());

    // 2. 正向验证：源命令存在时 pending join（terminal 为空）合法
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, terminal_entry_id, final_answer_entry_id, delivery_command_sequence, created_at, updated_at, delivery)"
                    + " values (?, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', ?, ?, 1, 'subagent', 10, 0, null, null, null, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
                UUID.randomUUID(),
                parentThreadId,
                childThreadId));
  }

  @Test
  void harnessThreadJoinDeliveryEnumAndSupersededForeignKey() {
    // 测试意图：delivery 枚举与 superseded_by 自引用外键；被取代 join 的 matched 状态由应用维护。
    UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000070");
    UUID rootEntryId = UUID.fromString("00000000-0000-0000-0000-000000000071");
    UUID parentThreadId = UUID.fromString("00000000-0000-0000-0000-000000000072");
    UUID childThreadId = UUID.fromString("00000000-0000-0000-0000-000000000073");
    UUID terminalEntryId = UUID.fromString("00000000-0000-0000-0000-000000000074");
    String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
            + " values (?, ?, ?, 'MESSAGE', '{\"message\":{\"role\":\"ASSISTANT\",\"contents\":[{\"type\":\"text\",\"text\":\"done\"}]}}'::jsonb, statement_timestamp())",
        terminalEntryId,
        sessionId,
        rootEntryId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, null, ?, ?, 'parent', ?, false, 'USER', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 1, 0, statement_timestamp(), statement_timestamp())",
        parentThreadId,
        sessionId,
        rootEntryId,
        hash,
        parentThreadId);
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id, creation_request_hash, name, root_thread_id, yolo_enabled, spawn_trigger, constraints, execution_control, input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, 'child', ?, false, 'MODEL_TOOL', '{\"maxOutputTokens\":null,\"allowAutoCompaction\":true}'::jsonb, 'RUNNABLE', 0, 2, 1, statement_timestamp(), statement_timestamp())",
        childThreadId,
        sessionId,
        parentThreadId,
        rootEntryId,
        hash,
        parentThreadId);
    jdbc.update(
        "insert into harness_thread_command (thread_id, sequence, command_type, payload, idempotency_key, request_hash, created_at)"
            + " values (?, 1, 'USER_MESSAGE', '{\"text\":\"hello\"}'::jsonb, ?, ?, statement_timestamp())",
        childThreadId,
        UUID.randomUUID(),
        hash);

    UUID pendingInvocation = UUID.randomUUID();
    // 合法 pending join：带父的 NOTIFY_PARENT。
    jdbc.update(
        "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, created_at, updated_at, delivery)"
            + " values (?, ?, ?, ?, 1, 'subagent', 10, 0, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
        pendingInvocation,
        hash,
        parentThreadId,
        childThreadId);
    assertEquals(
        "NOTIFY_PARENT",
        jdbc.queryForObject(
            "select delivery from harness_thread_join where invocation_id = ?",
            String.class,
            pendingInvocation));

    // 1. 未知 delivery → ck_harness_thread_join_delivery_kind
    DataIntegrityViolationException exDelivery =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, delivery, max_turns, reminder_turn, created_at, updated_at)"
                        + " values (?, ?, ?, ?, 1, 'subagent', 'other', 10, 0, statement_timestamp(), statement_timestamp())",
                    UUID.randomUUID(),
                    hash,
                    parentThreadId,
                    childThreadId),
            "unknown delivery must be rejected");
    assertTrue(exDelivery.getMessage().contains("ck_harness_thread_join_delivery_kind"));

    // 4. superseded_by 指向不存在的 invocation → fk_harness_thread_join_superseded_by
    DataIntegrityViolationException exFk =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, superseded_by_invocation_id, created_at, updated_at, delivery)"
                        + " values (?, ?, ?, ?, 1, 'subagent', 10, 0, ?, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
                    UUID.randomUUID(),
                    hash,
                    parentThreadId,
                    childThreadId,
                    UUID.fromString("00000000-0000-0000-0000-000000000099")),
            "superseded_by must reference a real join");
    assertTrue(exFk.getMessage().contains("fk_harness_thread_join_superseded_by"));

    // 5. 正向验证：新 join 取代旧未完成 join（superseded_by 指向真实存在的 later invocation）
    UUID continuationInvocation = UUID.randomUUID();
    jdbc.update(
        "insert into harness_thread_join (invocation_id, request_hash, parent_thread_id, child_thread_id, source_command_sequence, agent, max_turns, reminder_turn, created_at, updated_at, delivery)"
            + " values (?, ?, ?, ?, 1, 'subagent', 10, 0, statement_timestamp(), statement_timestamp(), 'NOTIFY_PARENT')",
        continuationInvocation,
        hash,
        parentThreadId,
        childThreadId);
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "update harness_thread_join set superseded_by_invocation_id = ? where invocation_id = ?",
                continuationInvocation,
                pendingInvocation));
  }
}
