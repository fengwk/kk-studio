package fun.fengwk.kkstudio.platform.harness.persistence.postgresql;

import static fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport.assertTransactionConstraintViolation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 PostgreSQL 生产 baseline schema 是否完全表达非 Harness 的业务数据与约束规则。 */
class PostgresqlBusinessSchemaTest extends PostgresSchemaSupport {

  @BeforeEach
  void setup() throws SQLException {
    try (Connection conn = newConnection()) {
      resetDatabase(conn);
      applyBaseline(conn);
    }
  }

  /** 验证 Canvas 节点与资源严格约束在所属 Canvas 聚合内： 跨 Canvas 的节点 group 引用、资源 owner 引用以及悬空 blob 引用均被拒绝。 */
  @Test
  void canvasNodeAndResourceStayWithinTheirOwningCanvas() throws SQLException {
    UUID firstCanvasId = uuid();
    UUID secondCanvasId = uuid();
    UUID firstGroupId = uuid();
    UUID secondGroupId = uuid();
    UUID firstNodeId = uuid();
    UUID secondNodeId = uuid();
    UUID blobId = uuid();

    try (Connection conn = newConnection()) {
      insertCanvas(conn, firstCanvasId);
      insertCanvas(conn, secondCanvasId);
      insertCanvasGroup(conn, firstGroupId, firstCanvasId, "g1");
      insertCanvasGroup(conn, secondGroupId, secondCanvasId, "g2");
      insertNode(conn, firstNodeId, firstCanvasId);
      insertNode(conn, secondNodeId, secondCanvasId);
      insertStorageBlob(conn, blobId);
    }

    // 探针 1：节点所属 group 跨 Canvas 被 fk_canvas_node_group 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_node_group",
          () -> insertNodeRow(conn, uuid(), firstCanvasId, "n-bad-group", 100, 100, secondGroupId));
    }

    // 探针 2：资源所属 node 跨 Canvas 被 fk_canvas_resource_owner 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_resource_owner",
          () ->
              insertResourceText(
                  conn, uuid(), firstCanvasId, secondNodeId, 0, "res-bad-owner", "txt"));
    }

    // 探针 3：资源引用的 blob 不存在被 fk_canvas_resource_blob 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_resource_blob",
          () ->
              insertResourceBlob(
                  conn, uuid(), firstCanvasId, firstNodeId, 0, "res-bad-blob", uuid()));
    }

    // 健全性验证：合规同属节点与资源成功插入
    try (Connection conn = newConnection()) {
      insertNodeRow(conn, uuid(), firstCanvasId, "n-good", 100, 100, firstGroupId);
      insertResourceText(conn, uuid(), firstCanvasId, firstNodeId, 0, "res-good-text", "hello");
      insertResourceBlob(conn, uuid(), firstCanvasId, firstNodeId, 1, "res-good-blob", blobId);
    }
  }

  /**
   * 验证 Canvas 核心实体的输入与形状校验： 拒绝空白文档标题、空白节点名、非法几何尺寸、非法 function 配置、 内容冲突/缺失的资源、owner/index 存缺不配的资源、非法
   * dedup hash 与负 revision。
   */
  @Test
  void canvasSchemaConstraintsRejectBadInputs() throws SQLException {
    UUID canvasId = uuid();
    UUID nodeId = uuid();
    UUID blobId = uuid();

    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, nodeId, canvasId);
      insertStorageBlob(conn, blobId);
    }

    // 1. Document：空白标题被拒绝
    for (String invalidTitle : new String[] {"", "   "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn, "canvas_document_title_check", () -> insertCanvasRow(conn, uuid(), invalidTitle));
      }
    }

    // 2. Node：空白名称或首尾空白被拒绝
    for (String invalidName : new String[] {"", " ", " node", "node "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "canvas_node_name_check",
            () -> insertNodeRow(conn, uuid(), canvasId, invalidName, 100, 100, null));
      }
    }

    // 3. Node：零宽度或零高度被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, uuid(), canvasId, "node-geom-1", 0, 100, null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_geometry",
          () -> insertNodeRow(conn, uuid(), canvasId, "node-geom-2", 100, 0, null));
    }

    // 4. Node："function" 非 object 或无 name 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_function",
          () -> insertNodeWithFunction(conn, uuid(), canvasId, "node-func-1", "[]"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_node_function",
          () -> insertNodeWithFunction(conn, uuid(), canvasId, "node-func-2", "{\"args\": {}}"));
    }

    // 5. Resource：blob_id 与 text_content 俱空或俱存被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_resource_content",
          () -> insertResourceRaw(conn, uuid(), canvasId, null, null, "res-both-null", null, null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_resource_content",
          () ->
              insertResourceRaw(conn, uuid(), canvasId, null, null, "res-both-set", blobId, "txt"));
    }

    // 6. Resource：owner_node_id 与 resource_index 存缺不配被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_resource_owner_pair",
          () ->
              insertResourceRaw(
                  conn, uuid(), canvasId, nodeId, null, "res-owner-only", null, "txt"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_resource_owner_pair",
          () -> insertResourceRaw(conn, uuid(), canvasId, null, 0, "res-index-only", null, "txt"));
    }

    // 7. Dedup：request_hash 长度不足或非 64 字节十六进制被拒绝
    for (String invalidHash : new String[] {"0".repeat(63), "x".repeat(64), " "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "canvas_command_dedup_request_hash_check",
            () -> insertDedup(conn, canvasId, invalidHash, 0L));
      }
    }

    // 8. Dedup：负 revision 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_command_dedup_revision",
          () -> insertDedup(conn, canvasId, "0".repeat(64), -1L));
    }

    // 健全性验证：合规数据均可正常写入
    try (Connection conn = newConnection()) {
      insertDedup(conn, canvasId, "f".repeat(64), 0L);
      insertNodeWithFunction(
          conn, uuid(), canvasId, "node-good-func", "{\"name\": \"exec\", \"args\": {}}");
      insertResourceText(conn, uuid(), canvasId, nodeId, 0, "res-good-text", "content");
      insertResourceBlob(conn, uuid(), canvasId, null, null, "res-good-unowned-blob", blobId);
    }
  }

  /**
   * 验证 Canvas Function Run 单租约与状态形态契约： 严格要求 status 白名单、lease 成对校验、status/lease/available_at 形态匹配、
   * pin 角色白名单以及 pin 对已有 run 的外键依赖。
   */
  @Test
  void canvasFunctionRunKeepsOneLeaseAndStatusShape() throws SQLException {
    UUID canvasId = uuid();
    UUID nodeId = uuid();
    UUID resourceId = uuid();

    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertNode(conn, nodeId, canvasId);
      insertResourceText(conn, resourceId, canvasId, nodeId, 0, "res-for-pin", "txt");
    }

    // 1. Run：非法 status 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "canvas_function_run_status_check",
          () -> insertFunctionRun(conn, nodeId, uuid(), "INVALID", "{}", null, null, null));
    }

    // 2. Run：lease_token 与 lease_until 成对校验
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_function_run_lease_pair",
          () ->
              insertFunctionRun(
                  conn, nodeId, uuid(), "RUNNING", "{}", null, "lease-token-only", null));
    }

    // 3. Run：status/lease/available_at 形状校验（READY 必须有 available_at 且无 lease）
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_canvas_function_run_status_shape",
          () -> insertFunctionRun(conn, nodeId, uuid(), "READY", "{}", null, null, null));
    }

    // 健全性验证：合规 READY Run 成功写入
    UUID requestId = uuid();
    try (Connection conn = newConnection()) {
      insertFunctionRunReady(conn, nodeId, requestId);
    }

    // 4. Pin：role 白名单拒绝非法取值
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "canvas_function_resource_pin_role_check",
          () ->
              insertFunctionResourcePin(
                  conn, canvasId, nodeId, requestId, "INVALID_ROLE", resourceId));
    }

    // 5. Pin：引用不存在的 run 被 fk_canvas_pin_run 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_canvas_pin_run",
          () -> insertFunctionResourcePin(conn, canvasId, nodeId, uuid(), "INPUT", resourceId));
    }

    // 健全性验证：合规 pin 插入成功
    try (Connection conn = newConnection()) {
      insertFunctionResourcePin(conn, canvasId, nodeId, requestId, "INPUT", resourceId);
    }
  }

  /**
   * 验证 Canvas 依赖图按显式依赖顺序删除： 节点持有资源、节点持有 run、文档持有节点时均 RESTRICT 拒绝直接删除； 严格遵循 pins -> runs ->
   * resources -> nodes -> groups -> dedup -> document 顺序可完全清空。
   */
  @Test
  void canvasDeletionIsRestrictedUntilDependencyOrderedCleanup() throws SQLException {
    UUID canvasId = uuid();
    UUID groupId = uuid();
    UUID nodeId = uuid();
    UUID resourceId = uuid();
    UUID requestId = uuid();

    try (Connection conn = newConnection()) {
      insertCanvas(conn, canvasId);
      insertCanvasGroup(conn, groupId, canvasId, "group-1");
      insertNodeRow(conn, nodeId, canvasId, "node-1", 100, 100, groupId);
      insertResourceText(conn, resourceId, canvasId, nodeId, 0, "res-1", "data");
      insertFunctionRunReady(conn, nodeId, requestId);
      insertFunctionResourcePin(conn, canvasId, nodeId, requestId, "INPUT", resourceId);
      insertDedup(conn, canvasId, "a".repeat(64), 0L);
    }

    // 探针 1：节点持有 owned resource 时 RESTRICT 拒绝删除节点
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_canvas_resource_owner", () -> deleteNode(conn, nodeId, canvasId));
    }

    // 探针 2：节点存在 run 时 RESTRICT 拒绝删除节点（即使无 resource）
    UUID secondNodeId = uuid();
    UUID secondRequestId = uuid();
    try (Connection conn = newConnection()) {
      insertNode(conn, secondNodeId, canvasId);
      insertFunctionRunReady(conn, secondNodeId, secondRequestId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "canvas_function_run_node_id_fkey", () -> deleteNode(conn, secondNodeId, canvasId));
    }
    try (Connection conn = newConnection()) {
      deleteFunctionRun(conn, secondNodeId);
      assertEquals(1, deleteNode(conn, secondNodeId, canvasId));
    }

    // 探针 3：Canvas 仍持有 group 时 RESTRICT 拒绝删除文档（group 边先于 node 边报告）
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "canvas_group_canvas_id_fkey", () -> deleteCanvas(conn, canvasId));
    }

    // 探针 4：只有节点、没有 group 的 Canvas 仍被 node 边 RESTRICT 拒绝
    UUID nodeOnlyCanvasId = uuid();
    UUID nodeOnlyNodeId = uuid();
    try (Connection conn = newConnection()) {
      insertCanvas(conn, nodeOnlyCanvasId);
      insertNode(conn, nodeOnlyNodeId, nodeOnlyCanvasId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "canvas_node_canvas_id_fkey", () -> deleteCanvas(conn, nodeOnlyCanvasId));
    }
    try (Connection conn = newConnection()) {
      assertEquals(1, deleteNode(conn, nodeOnlyNodeId, nodeOnlyCanvasId));
      assertEquals(1, deleteCanvas(conn, nodeOnlyCanvasId));
    }

    // 按照既定依赖顺序执行清理：
    // pins -> runs -> resources -> nodes -> groups -> dedup -> document
    try (Connection conn = newConnection()) {
      assertEquals(1, deletePinsByCanvas(conn, canvasId));
      assertEquals(1, deleteFunctionRun(conn, nodeId));
      assertEquals(1, deleteResourcesByCanvas(conn, canvasId));
      assertEquals(1, deleteNode(conn, nodeId, canvasId));
      assertEquals(1, deleteGroupsByCanvas(conn, canvasId));
      assertEquals(1, deleteDedupByCanvas(conn, canvasId));
      assertEquals(1, deleteCanvas(conn, canvasId));

      assertEquals(
          0L, queryLong(conn, "select count(*) from canvas_document where id = ?", canvasId));
      assertEquals(
          0L, queryLong(conn, "select count(*) from canvas_node where canvas_id = ?", canvasId));
    }
  }

  /**
   * 验证 Project 工作流与 Issue 状态契约：
   * project_title_check、project_workflow_check、project_next_issue_number_check、
   * project_issue_state_check、BLOCKED 状态成对校验 (project_issue_check)、 暂停原因成对校验 (project_issue_check1)
   * 以及 issue 业务编号唯一性。
   */
  @Test
  void projectWorkflowAndIssueStateContractsAreEnforced() throws SQLException {
    UUID projectId = uuid();

    // 1. Project 标题校验：空白或首尾空格拒绝（check 用 btrim，因此只断言空格类空白）
    for (String invalid : new String[] {"", " ", " project ", "project  "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_title_check",
            () -> insertProject(conn, uuid(), invalid, "{\"states\": []}", 1L));
      }
    }

    // 2. Project 工作流配置校验：必须为含有 states 数组的 object
    for (String badWorkflow : new String[] {"[]", "{}", "{\"states\": \"invalid\"}"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_workflow_check",
            () -> insertProject(conn, uuid(), "proj", badWorkflow, 1L));
      }
    }

    // 3. Project 序号分配器：next_issue_number < 1 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_next_issue_number_check",
          () -> insertProject(conn, uuid(), "proj", "{\"states\": []}", 0L));
    }

    try (Connection conn = newConnection()) {
      insertProject(conn, projectId, "proj-1", "{\"states\": []}", 1L);
    }

    // 4. Issue state 命名约束：必须大写开头字母下划线
    for (String badState : new String[] {"open", "1_START", "INVALID-STATE", ""}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_issue_state_check",
            () ->
                insertIssue(
                    conn, uuid(), projectId, 1L, "issue", badState, null, null, null, null));
      }
    }

    // 5. Issue BLOCKED 校验 (project_issue_check)：
    // - BLOCKED 必须提供 blocked_from_state 与 block_reason
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_check",
          () ->
              insertIssue(
                  conn, uuid(), projectId, 1L, "issue", "BLOCKED", null, "reason", null, null));
    }
    // - 非 BLOCKED 不允许保留 block_reason
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_check",
          () ->
              insertIssue(
                  conn, uuid(), projectId, 1L, "issue", "OPEN", null, "reason", null, null));
    }
    // - BLOCKED 的 blocked_from_state 不得为 BLOCKED 或 DONE
    for (String forbiddenFrom : new String[] {"BLOCKED", "DONE"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_issue_check",
            () ->
                insertIssue(
                    conn,
                    uuid(),
                    projectId,
                    1L,
                    "issue",
                    "BLOCKED",
                    forbiddenFrom,
                    "reason",
                    null,
                    null));
      }
    }

    // 6. Issue pause 校验 (project_issue_check1)：pause_reason 与 pause_detail 成对且合规
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_check1",
          () ->
              insertIssue(conn, uuid(), projectId, 1L, "issue", "OPEN", null, null, "USER", null));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_check1",
          () ->
              insertIssue(
                  conn, uuid(), projectId, 1L, "issue", "OPEN", null, null, null, "detail"));
    }

    // 7. Issue 业务编号唯一性：(project_id, number) 冲突拒绝
    try (Connection conn = newConnection()) {
      insertIssue(conn, uuid(), projectId, 100L, "issue-100", "OPEN", null, null, null, null);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_project_id_number_key",
          () ->
              insertIssue(
                  conn, uuid(), projectId, 100L, "issue-100-dup", "OPEN", null, null, null, null));
    }

    // 健全性验证：合规普通 Issue、合规 BLOCKED Issue 与合规 PAUSED Issue 均成功写入
    try (Connection conn = newConnection()) {
      insertIssue(
          conn,
          uuid(),
          projectId,
          101L,
          "issue-101",
          "BLOCKED",
          "PLANNING",
          "waiting on external team",
          null,
          null);
      insertIssue(
          conn,
          uuid(),
          projectId,
          102L,
          "issue-102",
          "PLANNING",
          null,
          null,
          "USER",
          "user requested pause");
    }
  }

  /**
   * 验证 Issue 阶段预算与 Run 关联约束： 预算归属、保留态禁止建预算、阶段预算额度、Run 对预算/AgentThread/ThreadSession 的多重外键、 活跃 Run
   * 唯一索引、Run 时钟与终态形态校验。
   */
  @Test
  void issueStageBudgetAndRunRelationsAreEnforced() throws SQLException {
    String providerName = "prov-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "mod-" + FIXTURE_IDS.incrementAndGet();
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();

    UUID sessionId = uuid();
    UUID entryId = uuid();
    UUID threadId = uuid();
    UUID projectId = uuid();
    UUID issueId = uuid();

    try (Connection conn = newConnection()) {
      insertProvider(conn, providerName);
      insertModel(conn, providerName, modelName);
      insertDefinition(conn, agentName, providerName, modelName);
      insertThread(conn, threadId, sessionId, entryId);
      insertProject(conn, projectId, "proj-budget", "{\"states\": []}", 1L);
      insertIssue(conn, issueId, projectId, 1L, "issue-budget", "PLANNING", null, null, null, null);
    }

    // 1. 预算必须指向真实 Issue
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_project_issue_stage_budget_issue",
          () -> insertIssueStageBudget(conn, uuid(), "PLANNING", 5));
    }

    // 2. 保留态 INIT/BLOCKED/DONE 不得建阶段预算
    for (String reservedState : new String[] {"INIT", "BLOCKED", "DONE"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "ck_project_issue_stage_budget_work_stage",
            () -> insertIssueStageBudget(conn, issueId, reservedState, 5));
      }
    }

    // 3. max_runs 必须大于 0
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_project_issue_stage_budget_max_runs",
          () -> insertIssueStageBudget(conn, issueId, "PLANNING", 0));
    }

    // 正向建立合法预算并绑定 AgentThread
    try (Connection conn = newConnection()) {
      insertIssueStageBudget(conn, issueId, "PLANNING", 5);
      insertIssueAgentThread(conn, issueId, agentName, threadId);
    }

    // 4. Run 必须在已配置预算的 (issue_id, state) 下创建
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_project_issue_run_stage_budget",
          () ->
              insertIssueRun(
                  conn, uuid(), issueId, 1L, "CODING", sessionId, threadId, "RUNNING", entryId,
                  1000L, true, false));
    }

    // 5. Run 必须关联已绑定的 (issue_id, thread_id)：未绑定的 Thread 即使属于同一 Session 也被拒绝
    UUID unboundSessionId = uuid();
    UUID unboundEntryId = uuid();
    UUID unboundThreadId = uuid();
    try (Connection conn = newConnection()) {
      insertThread(conn, unboundThreadId, unboundSessionId, unboundEntryId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_project_issue_run_agent_thread",
          () ->
              insertIssueRun(
                  conn,
                  uuid(),
                  issueId,
                  1L,
                  "PLANNING",
                  unboundSessionId,
                  unboundThreadId,
                  "RUNNING",
                  unboundEntryId,
                  1000L,
                  true,
                  false));
    }

    // 6. Run 的 session_id 必须与 Thread 实际归属一致 (fk_project_issue_run_thread_session)
    UUID otherSessionId = uuid();
    UUID otherEntryId = uuid();
    try (Connection conn = newConnection()) {
      insertSessionAndEntry(conn, otherSessionId, otherEntryId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_project_issue_run_thread_session",
          () ->
              insertIssueRun(
                  conn,
                  uuid(),
                  issueId,
                  1L,
                  "PLANNING",
                  otherSessionId,
                  threadId,
                  "RUNNING",
                  otherEntryId,
                  1000L,
                  true,
                  false));
    }

    // 7. 正向插入第一个 RUNNING Run
    UUID runId1 = uuid();
    try (Connection conn = newConnection()) {
      insertIssueRun(
          conn,
          runId1,
          issueId,
          1L,
          "PLANNING",
          sessionId,
          threadId,
          "RUNNING",
          entryId,
          1000L,
          true,
          false);
    }

    // 8. 同一 Issue 仅允许一个 RUNNING/WAITING Run (uk_project_issue_run_active)
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_project_issue_run_active",
          () ->
              insertIssueRun(
                  conn,
                  uuid(),
                  issueId,
                  2L,
                  "PLANNING",
                  sessionId,
                  threadId,
                  "WAITING",
                  entryId,
                  1000L,
                  false,
                  false));
    }

    // 9. 时钟约束：RUNNING 状态缺少 active_since 被拒绝 (ck_project_issue_run_clock)
    // 第二个 Issue 使用自己绑定的 Session/Thread：(issue_id, thread_id) 与 (session_id, thread_id) 都必须自洽。
    UUID secondIssueId = uuid();
    UUID secondSessionId = uuid();
    UUID secondEntryId = uuid();
    UUID secondThreadId = uuid();
    try (Connection conn = newConnection()) {
      insertIssue(
          conn, secondIssueId, projectId, 2L, "issue-2", "PLANNING", null, null, null, null);
      insertIssueStageBudget(conn, secondIssueId, "PLANNING", 5);
      insertThread(conn, secondThreadId, secondSessionId, secondEntryId);
      insertIssueAgentThread(conn, secondIssueId, agentName, secondThreadId);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_project_issue_run_clock",
          () ->
              insertIssueRun(
                  conn,
                  uuid(),
                  secondIssueId,
                  1L,
                  "PLANNING",
                  secondSessionId,
                  secondThreadId,
                  "RUNNING",
                  secondEntryId,
                  1000L,
                  false,
                  false));
    }

    // 10. 终态形态约束：COMPLETED 状态缺少 ended_at/end_entry_id 被拒绝 (ck_project_issue_run_terminal_shape)
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_project_issue_run_terminal_shape",
          () ->
              insertIssueRunRaw(
                  conn,
                  uuid(),
                  secondIssueId,
                  1L,
                  "PLANNING",
                  secondSessionId,
                  secondThreadId,
                  "COMPLETED",
                  secondEntryId,
                  null,
                  0L,
                  null,
                  null,
                  null));
    }

    // 健全性验证：合规 COMPLETED Run 成功插入
    Instant now = Instant.now();
    try (Connection conn = newConnection()) {
      insertIssueRunRaw(
          conn,
          uuid(),
          secondIssueId,
          1L,
          "PLANNING",
          secondSessionId,
          secondThreadId,
          "COMPLETED",
          secondEntryId,
          secondEntryId,
          0L,
          null,
          now,
          now);
    }
  }

  /**
   * 验证 Issue Activity 与 Evidence 事实契约： actor_type 白名单 (project_issue_activity_actor_type_check)、
   * actor_type 与 actor_agent_name 配对 (project_issue_activity_check)、 kind 与 body/data/run_id 匹配形状
   * (project_issue_activity_check1)、 幂等键唯一性 (uk_project_issue_activity_request)、 Evidence 作者与名称格式校验
   * (ck_project_issue_evidence_author, project_issue_evidence_name_check)。
   */
  @Test
  void issueActivityAndEvidenceContractsAreEnforced() throws SQLException {
    String providerName = "prov-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "mod-" + FIXTURE_IDS.incrementAndGet();
    String agentName = "agent-" + FIXTURE_IDS.incrementAndGet();

    UUID sessionId = uuid();
    UUID entryId = uuid();
    UUID threadId = uuid();
    UUID projectId = uuid();
    UUID issueId = uuid();
    UUID runId = uuid();
    UUID blobId = uuid();

    try (Connection conn = newConnection()) {
      insertProvider(conn, providerName);
      insertModel(conn, providerName, modelName);
      insertDefinition(conn, agentName, providerName, modelName);
      insertThread(conn, threadId, sessionId, entryId);
      insertProject(conn, projectId, "proj-act", "{\"states\": []}", 1L);
      insertIssue(conn, issueId, projectId, 1L, "issue-act", "PLANNING", null, null, null, null);
      insertIssueStageBudget(conn, issueId, "PLANNING", 5);
      insertIssueAgentThread(conn, issueId, agentName, threadId);
      insertIssueRun(
          conn,
          runId,
          issueId,
          1L,
          "PLANNING",
          sessionId,
          threadId,
          "RUNNING",
          entryId,
          1000L,
          true,
          false);
      insertStorageBlob(conn, blobId);
    }

    // 1. Activity actor_type 白名单校验
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_activity_actor_type_check",
          () ->
              insertActivity(
                  conn, issueId, 1L, "COMMENT", "UNKNOWN", null, null, "hello", "{}", "idemp-1"));
    }

    // 2. Activity actor 配对校验 (project_issue_activity_check)
    // - HUMAN 携带 actor_agent_name 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_activity_check",
          () ->
              insertActivity(
                  conn,
                  issueId,
                  1L,
                  "COMMENT",
                  "HUMAN",
                  agentName,
                  null,
                  "hello",
                  "{}",
                  "idemp-bad-human"));
    }
    // - AGENT 未提供 actor_agent_name 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_activity_check",
          () ->
              insertActivity(
                  conn,
                  issueId,
                  1L,
                  "COMMENT",
                  "AGENT",
                  null,
                  null,
                  "hello",
                  "{}",
                  "idemp-bad-agent"));
    }

    // 3. Activity kind×shape 校验 (project_issue_activity_check1)
    // - COMMENT 缺少非空 body 被拒绝
    for (String badBody : new String[] {"", "   "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_issue_activity_check1",
            () ->
                insertActivity(
                    conn,
                    issueId,
                    1L,
                    "COMMENT",
                    "HUMAN",
                    null,
                    null,
                    badBody,
                    "{}",
                    "idemp-bad-comment"));
      }
    }
    // - RUN 缺少 run_id 或不是 SYSTEM 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_activity_check1",
          () ->
              insertActivity(
                  conn, issueId, 1L, "RUN", "SYSTEM", null, null, null, "{}", "idemp-bad-run"));
    }
    // - SPEC_CHANGE 携带 body 被拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_activity_check1",
          () ->
              insertActivity(
                  conn,
                  issueId,
                  1L,
                  "SPEC_CHANGE",
                  "HUMAN",
                  null,
                  null,
                  "non-null body",
                  "{}",
                  "idemp-bad-spec"));
    }

    // 4. Activity 同 Issue 幂等键冲突拒绝 (uk_project_issue_activity_request)
    try (Connection conn = newConnection()) {
      insertActivity(
          conn, issueId, 1L, "COMMENT", "HUMAN", null, null, "comment-1", "{}", "idemp-unique");
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "uk_project_issue_activity_request",
          () ->
              insertActivity(
                  conn,
                  issueId,
                  2L,
                  "COMMENT",
                  "HUMAN",
                  null,
                  null,
                  "comment-2",
                  "{}",
                  "idemp-unique"));
    }

    // 5. Evidence：关联 run_id 时必须指定 actor_agent_name (ck_project_issue_evidence_author)
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_project_issue_evidence_author",
          () -> insertEvidence(conn, issueId, blobId, null, runId, "evidence.txt"));
    }

    // 6. Evidence：展示名空白或首尾空白拒绝 (project_issue_evidence_name_check)
    for (String badName : new String[] {"", "   ", " name", "name "}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "project_issue_evidence_name_check",
            () -> insertEvidence(conn, issueId, blobId, agentName, runId, badName));
      }
    }

    // 健全性验证：合规 Activity 与 Evidence 成功插入
    try (Connection conn = newConnection()) {
      insertActivity(
          conn, issueId, 2L, "RUN", "SYSTEM", null, runId, null, "{}", "idemp-run-valid");
      insertEvidence(conn, issueId, blobId, agentName, runId, "valid-evidence.txt");
    }
  }

  /** 验证 Issue 工作邮箱调度契约： 外键严格依赖所属 Issue、租约 token 与截止时间成对校验、以及 wake_version > 0 约束。 */
  @Test
  void issueWorkMailboxLeaseContractIsEnforced() throws SQLException {
    UUID projectId = uuid();
    UUID issueId = uuid();

    try (Connection conn = newConnection()) {
      insertProject(conn, projectId, "proj-work", "{\"states\": []}", 1L);
      insertIssue(conn, issueId, projectId, 1L, "issue-work", "OPEN", null, null, null, null);
    }

    // 1. work 必须指向真实 Issue
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_project_issue_work_issue", () -> insertIssueWork(conn, uuid(), 1L, null, null));
    }

    // 2. lease_token 与 lease_until 成对校验
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_project_issue_work_lease_pair",
          () -> insertIssueWork(conn, issueId, 1L, "token-only", null));
    }

    // 3. wake_version 必须大于 0
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "project_issue_work_wake_version_check",
          () -> insertIssueWork(conn, issueId, 0L, null, null));
    }

    // 健全性验证：合规无租约与带租约 work 行均可成功操作
    try (Connection conn = newConnection()) {
      insertIssueWork(conn, issueId, 1L, null, null);
    }
  }

  /** 验证 Chat 与 Session 关联契约： Session 一对一归属排他主键、双向 RESTRICT 外键、以及关联存在时阻止删除底层 Session。 */
  @Test
  void chatSessionAssociationIsRestrictedAndSingleOwner() throws SQLException {
    UUID chatId1 = uuid();
    UUID chatId2 = uuid();
    UUID sessionId = uuid();
    UUID entryId = uuid();

    try (Connection conn = newConnection()) {
      insertChat(conn, chatId1);
      insertChat(conn, chatId2);
      insertSessionAndEntry(conn, sessionId, entryId);
    }

    // 1. 引用未知 session 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_chat_session_session", () -> insertChatSession(conn, uuid(), chatId1));
    }

    // 2. 引用未知 chat 拒绝
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_chat_session_chat", () -> insertChatSession(conn, sessionId, uuid()));
    }

    // 3. 正常建立关联
    try (Connection conn = newConnection()) {
      insertChatSession(conn, sessionId, chatId1);
    }

    // 4. 同一个 Session 只能属于一个 Chat (pk_chat_session)
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "pk_chat_session", () -> insertChatSession(conn, sessionId, chatId2));
    }

    // 5. 存在关联时 RESTRICT 拒绝删除底层 harness_session（先移除其 ROOT Entry，只留关联边）
    try (Connection conn = newConnection()) {
      assertEquals(1, deleteEntry(conn, entryId));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_chat_session_session", () -> deleteSession(conn, sessionId));
    }
  }

  @Test
  void agentResourcesKeepProviderAndModelOwnership() throws SQLException {
    String providerName = "provider-" + FIXTURE_IDS.incrementAndGet();
    String modelName = "model-" + FIXTURE_IDS.incrementAndGet();
    for (String invalid : new String[] {" provider ", "\tprovider\t", "provider/name"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn, "ck_agent_provider_name", () -> insertProvider(conn, invalid));
      }
    }
    try (Connection conn = newConnection()) {
      insertProvider(conn, providerName);
    }

    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "fk_agent_model_provider", () -> insertModel(conn, "missing-provider", modelName));
    }
    try (Connection conn = newConnection()) {
      insertModel(conn, providerName, modelName);
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn, "ck_agent_model_name", () -> insertModel(conn, providerName, "\nmodel\n"));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "\tagent\t", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "ck_agent_definition_name",
          () -> insertDefinition(conn, "agent/name", providerName, modelName));
    }
    try (Connection conn = newConnection()) {
      assertTransactionConstraintViolation(
          conn,
          "fk_agent_definition_model",
          () -> insertDefinition(conn, "missing-agent", "missing-provider", "missing-model"));
    }
  }

  @Test
  void providerTypeCheckAllowsOnlyStableWireValues() throws SQLException {
    // 真实 PostgreSQL schema 必须接受四个稳定 wire 值，并在数据库边界拒绝大小写、空白和未知值。
    for (String providerType : new String[] {"openai", "openai_response", "anthropic", "google"}) {
      try (Connection conn = newConnection()) {
        insertProvider(conn, "provider-" + FIXTURE_IDS.incrementAndGet(), providerType);
      }
    }
    for (String invalid : new String[] {"OPENAI", "openai ", "OpenAI", "missing"}) {
      try (Connection conn = newConnection()) {
        assertTransactionConstraintViolation(
            conn,
            "ck_agent_provider_provider_type",
            () ->
                insertProvider(conn, "invalid-provider-" + FIXTURE_IDS.incrementAndGet(), invalid));
      }
    }
  }

  @Test
  void chatAgentNameContractIsCanonicalAndThreadVersionStaysAppOwned() throws SQLException {
    try (Connection conn = newConnection()) {
      insertChat(conn, uuid());
      insertChat(conn, uuid());
    }

    for (String invalid : new String[] {" ", " agent ", "\tagent\t", "agent/name"}) {
      try (Connection conn = newConnection();
          PreparedStatement ps =
              conn.prepareStatement(
                  "insert into chat (id, title, agent_name) values (?, 'schema-test', ?)")) {
        ps.setObject(1, uuid());
        ps.setString(2, invalid);
        assertTransactionConstraintViolation(conn, "ck_chat_agent_name", () -> ps.executeUpdate());
      }
    }

    UUID threadId = uuid();
    UUID sessionId = uuid();
    UUID entryId = uuid();
    try (Connection conn = newConnection()) {
      insertThread(conn, threadId, sessionId, entryId);
      assertEquals(
          0L, queryLong(conn, "select version from harness_thread where id = ?", threadId));
      try (PreparedStatement ps =
          conn.prepareStatement("update harness_thread set yolo_mode = 'ENABLE' where id = ?")) {
        ps.setObject(1, threadId);
        assertEquals(1, ps.executeUpdate());
      }
      assertEquals(
          0L,
          queryLong(conn, "select version from harness_thread where id = ?", threadId),
          "version is owned by HarnessRuntime; an application UPDATE must never bump it");
    }
  }

  /** Thread 通知必须在提交后携带严格 threadId:version；回滚既不发通知，也不改变权威版本。 */
  @Test
  void threadVersionNotificationsPublishOnlyCommittedCanonicalPayloads() throws Exception {
    UUID threadId = uuid();
    UUID sessionId = uuid();
    UUID entryId = uuid();
    String insertedPayload = threadId + ":0";

    try (Connection listener = newConnection();
        Connection writer = newConnection();
        Statement listen = listener.createStatement()) {
      listener.setAutoCommit(true);
      PGConnection notifications = listener.unwrap(PGConnection.class);
      listen.execute("LISTEN harness_thread_version");

      writer.setAutoCommit(false);
      insertThread(writer, threadId, sessionId, entryId);
      assertNoNotification(notifications);
      writer.commit();
      assertEquals(
          List.of(insertedPayload),
          awaitPayloads(notifications, "harness_thread_version", insertedPayload),
          "INSERT notification must be exactly <uuid>:<nonnegative-version>");

      updateThreadVersion(writer, threadId, 1L);
      assertNoNotification(notifications);
      writer.rollback();
      assertNoNotification(notifications);
      assertEquals(
          0L,
          queryLong(writer, "select version from harness_thread where id = ?", threadId),
          "rolled-back version writes must leave the authoritative row unchanged");

      updateThreadVersion(writer, threadId, 1L);
      updateThreadVersion(writer, threadId, 2L);
      writer.commit();
      List<String> payloads =
          awaitPayloads(notifications, "harness_thread_version", threadId + ":2");
      assertTrue(
          Set.of(threadId + ":1", threadId + ":2").containsAll(payloads),
          () -> "transaction notifications must contain only written versions: " + payloads);
      assertTrue(
          payloads.contains(threadId + ":2"),
          () -> "the committed final thread version must be observable: " + payloads);
      assertEquals(
          2L,
          queryLong(writer, "select version from harness_thread where id = ?", threadId),
          "multiple updates in one transaction must preserve the final application version");
    }
  }

  /** Singleton settings 通知遵循 PostgreSQL 事务边界；同事务多次更新允许标准折叠或多通知，但最终版本必须可观察且数据库绝不代写版本。 */
  @Test
  void systemSettingsNotificationsRespectTransactionsAndFinalVersion() throws Exception {
    try (Connection listener = newConnection();
        Connection writer = newConnection();
        Statement listen = listener.createStatement()) {
      listener.setAutoCommit(true);
      PGConnection notifications = listener.unwrap(PGConnection.class);
      listen.execute("LISTEN system_settings_changed");

      writer.setAutoCommit(false);
      updateSystemSettingsVersion(writer, 1L);
      assertNoNotification(notifications);
      writer.commit();
      assertEquals(
          List.of("1"),
          awaitPayloads(notifications, "system_settings_changed", "1"),
          "committed settings notification payload must be NEW.version text");

      updateSystemSettingsVersion(writer, 2L);
      assertNoNotification(notifications);
      writer.rollback();
      assertNoNotification(notifications);
      assertEquals(
          1L,
          queryLong(writer, "select version from system_setting where id = ?", 1L),
          "rolled-back settings updates must neither notify nor change the authoritative version");

      updateSystemSettingsVersion(writer, 2L);
      updateSystemSettingsVersion(writer, 3L);
      writer.commit();
      List<String> payloads = awaitPayloads(notifications, "system_settings_changed", "3");
      assertTrue(
          Set.of("2", "3").containsAll(payloads),
          () -> "transaction notifications must contain only written versions: " + payloads);
      assertTrue(
          payloads.contains("3"),
          () -> "the committed final settings version must be observable: " + payloads);
      assertEquals(
          3L,
          queryLong(writer, "select version from system_setting where id = ?", 1L),
          "the trigger must preserve the final version written by the application");
    }
  }

  private void insertProvider(Connection conn, String name) throws SQLException {
    insertProvider(conn, name, "openai");
  }

  private void insertProvider(Connection conn, String name, String providerType)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_provider (name, provider_type, config, connection_generation_id)"
                + " values (?, ?, '{}'::jsonb, ?::uuid)")) {
      ps.setString(1, name);
      ps.setString(2, providerType);
      ps.setObject(3, UUID.randomUUID());
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertModel(Connection conn, String providerName, String name) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_model (provider_name, name, model_id, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, providerName);
      ps.setString(2, name);
      ps.setString(3, "wire-" + FIXTURE_IDS.incrementAndGet());
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertDefinition(
      Connection conn, String name, String modelProviderName, String modelName)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into agent_definition (name, model_provider_name, model_name, config)"
                + " values (?, ?, ?, '{}'::jsonb)")) {
      ps.setString(1, name);
      ps.setString(2, modelProviderName);
      ps.setString(3, modelName);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static UUID uuid() {
    return new UUID(0L, FIXTURE_IDS.incrementAndGet());
  }

  private void insertChat(Connection conn, UUID id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into chat (id, title, agent_name)"
                + " values (?, 'schema-test', 'schema-agent')")) {
      ps.setObject(1, id);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertSessionAndEntry(Connection conn, UUID sessionId, UUID entryId)
      throws SQLException {
    try (PreparedStatement session =
            conn.prepareStatement(
                "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)");
        PreparedStatement entry =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
                    + " values (?, ?, 'ROOT', '{}'::jsonb, current_timestamp)")) {
      session.setObject(1, sessionId);
      session.setString(2, "session-" + sessionId);
      assertEquals(1, session.executeUpdate());
      entry.setObject(1, entryId);
      entry.setObject(2, sessionId);
      assertEquals(1, entry.executeUpdate());
    }
  }

  private void insertThread(Connection conn, UUID threadId, UUID sessionId, UUID entryId)
      throws SQLException {
    insertSessionAndEntry(conn, sessionId, entryId);
    try (PreparedStatement thread =
        conn.prepareStatement(
            "insert into harness_thread (id, session_id, head_entry_id, creation_request_hash,"
                + " name, yolo_mode, yolo_root_thread_id, execution_control, input_through_sequence,"
                + " next_command_sequence, version, created_at, updated_at)"
                + " values (?, ?, ?, '"
                + "0".repeat(64)
                + "', 'schema-business-test-thread', 'DISABLE', null, 'RUNNABLE', 0, 1, 0,"
                + " current_timestamp,"
                + " current_timestamp)")) {
      thread.setObject(1, threadId);
      thread.setObject(2, sessionId);
      thread.setObject(3, entryId);
      assertEquals(1, thread.executeUpdate());
    }
  }

  private static void updateThreadVersion(Connection conn, UUID threadId, long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("update harness_thread set version = ? where id = ?")) {
      ps.setLong(1, version);
      ps.setObject(2, threadId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static void updateSystemSettingsVersion(Connection conn, long version)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("update system_setting set version = ? where id = 1")) {
      ps.setLong(1, version);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private static List<String> awaitPayloads(
      PGConnection connection, String channel, String finalPayload) throws SQLException {
    List<String> payloads = new ArrayList<>();
    long deadlineNanos = System.nanoTime() + 5_000_000_000L;
    while (!payloads.contains(finalPayload) && System.nanoTime() < deadlineNanos) {
      PGNotification[] received = connection.getNotifications(250);
      if (received == null) {
        continue;
      }
      for (PGNotification notification : received) {
        assertEquals(channel, notification.getName());
        payloads.add(notification.getParameter());
      }
    }
    assertTrue(
        payloads.contains(finalPayload),
        () -> "timed out waiting for final payload " + finalPayload + "; received=" + payloads);
    return payloads;
  }

  private static void assertNoNotification(PGConnection connection) throws SQLException {
    PGNotification[] notifications = connection.getNotifications(200);
    assertTrue(
        notifications == null || notifications.length == 0,
        "rolled-back or uncommitted writes must not notify");
  }

  private static long queryLong(String sql, UUID id) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        return rs.getLong(1);
      }
    }
  }

  private long queryLong(Connection conn, String sql, UUID id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long queryLong(Connection conn, String sql, long id) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setLong(1, id);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void insertStorageBlob(Connection conn, UUID blobId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state)"
                + " values (?, '"
                + "a".repeat(64)
                + "', 10, 'text/plain', 1, 'ACTIVE')")) {
      ps.setObject(1, blobId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertCanvas(Connection conn, UUID id) throws SQLException {
    insertCanvasRow(conn, id, "schema-test");
  }

  private void insertCanvasRow(Connection conn, UUID id, String title) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("insert into canvas_document (id, title) values (?, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, title);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertCanvasGroup(Connection conn, UUID id, UUID canvasId, String title)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_group (id, canvas_id, title, x, y, width, height)"
                + " values (?, ?, ?, 0, 0, 100, 100)")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setString(3, title);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNode(Connection conn, UUID id, UUID canvasId) throws SQLException {
    insertNodeRow(conn, id, canvasId, "node-" + id, 100, 100, null);
  }

  private void insertNodeRow(
      Connection conn,
      UUID id,
      UUID canvasId,
      String name,
      double width,
      double height,
      UUID groupId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, name, x, y, width, height, group_id)"
                + " values (?, ?, ?, 0, 0, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setString(3, name);
      ps.setDouble(4, width);
      ps.setDouble(5, height);
      ps.setObject(6, groupId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertNodeWithFunction(
      Connection conn, UUID id, UUID canvasId, String name, String functionJson)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_node (id, canvas_id, name, x, y, width, height, \"function\")"
                + " values (?, ?, ?, 0, 0, 100, 100, ?::jsonb)")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setString(3, name);
      ps.setString(4, functionJson);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertResourceRaw(
      Connection conn,
      UUID id,
      UUID canvasId,
      UUID ownerNodeId,
      Integer resourceIndex,
      String name,
      UUID blobId,
      String textContent)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_resource"
                + " (id, canvas_id, owner_node_id, resource_index, name, blob_id, text_content)"
                + " values (?, ?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, canvasId);
      ps.setObject(3, ownerNodeId);
      ps.setObject(4, resourceIndex);
      ps.setString(5, name);
      ps.setObject(6, blobId);
      ps.setString(7, textContent);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertResourceText(
      Connection conn,
      UUID id,
      UUID canvasId,
      UUID ownerNodeId,
      Integer resourceIndex,
      String name,
      String textContent)
      throws SQLException {
    insertResourceRaw(conn, id, canvasId, ownerNodeId, resourceIndex, name, null, textContent);
  }

  private void insertResourceBlob(
      Connection conn,
      UUID id,
      UUID canvasId,
      UUID ownerNodeId,
      Integer resourceIndex,
      String name,
      UUID blobId)
      throws SQLException {
    insertResourceRaw(conn, id, canvasId, ownerNodeId, resourceIndex, name, blobId, null);
  }

  private void insertDedup(
      Connection conn, UUID canvasId, String requestHash, long acceptedRevision)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_command_dedup"
                + " (canvas_id, idempotency_key, request_hash, accepted_revision)"
                + " values (?, ?, ?, ?)")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, uuid());
      ps.setString(3, requestHash);
      ps.setLong(4, acceptedRevision);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertFunctionRun(
      Connection conn,
      UUID nodeId,
      UUID requestId,
      String status,
      String stateJson,
      Instant availableAt,
      String leaseToken,
      Instant leaseUntil)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_function_run"
                + " (node_id, request_id, status, state_json, available_at, lease_token, lease_until)"
                + " values (?, ?, ?, ?::jsonb, ?, ?, ?)")) {
      ps.setObject(1, nodeId);
      ps.setObject(2, requestId);
      ps.setString(3, status);
      ps.setString(4, stateJson);
      ps.setTimestamp(5, availableAt != null ? Timestamp.from(availableAt) : null);
      ps.setString(6, leaseToken);
      ps.setTimestamp(7, leaseUntil != null ? Timestamp.from(leaseUntil) : null);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertFunctionRunReady(Connection conn, UUID nodeId, UUID requestId)
      throws SQLException {
    insertFunctionRun(conn, nodeId, requestId, "READY", "{}", Instant.now(), null, null);
  }

  private void insertFunctionResourcePin(
      Connection conn, UUID canvasId, UUID nodeId, UUID requestId, String role, UUID resourceId)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into canvas_function_resource_pin"
                + " (canvas_id, node_id, request_id, role, resource_id)"
                + " values (?, ?, ?, ?, ?)")) {
      ps.setObject(1, canvasId);
      ps.setObject(2, nodeId);
      ps.setObject(3, requestId);
      ps.setString(4, role);
      ps.setObject(5, resourceId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private int deletePinsByCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_function_resource_pin where canvas_id = ?")) {
      ps.setObject(1, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteFunctionRun(Connection conn, UUID nodeId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_function_run where node_id = ?")) {
      ps.setObject(1, nodeId);
      return ps.executeUpdate();
    }
  }

  private int deleteResourcesByCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_resource where canvas_id = ?")) {
      ps.setObject(1, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteNode(Connection conn, UUID nodeId, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_node where id = ? and canvas_id = ?")) {
      ps.setObject(1, nodeId);
      ps.setObject(2, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteGroupsByCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_group where canvas_id = ?")) {
      ps.setObject(1, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteDedupByCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("delete from canvas_command_dedup where canvas_id = ?")) {
      ps.setObject(1, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteCanvas(Connection conn, UUID canvasId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("delete from canvas_document where id = ?")) {
      ps.setObject(1, canvasId);
      return ps.executeUpdate();
    }
  }

  private int deleteSession(Connection conn, UUID sessionId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("delete from harness_session where id = ?")) {
      ps.setObject(1, sessionId);
      return ps.executeUpdate();
    }
  }

  private int deleteEntry(Connection conn, UUID entryId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("delete from harness_entry where id = ?")) {
      ps.setObject(1, entryId);
      return ps.executeUpdate();
    }
  }

  private void insertProject(
      Connection conn, UUID id, String title, String workflowJson, long nextIssueNumber)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project (id, title, workflow, next_issue_number)"
                + " values (?, ?, ?::jsonb, ?)")) {
      ps.setObject(1, id);
      ps.setString(2, title);
      ps.setString(3, workflowJson);
      ps.setLong(4, nextIssueNumber);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssue(
      Connection conn,
      UUID id,
      UUID projectId,
      long number,
      String title,
      String state,
      String blockedFromState,
      String blockReason,
      String pauseReason,
      String pauseDetail)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue"
                + " (id, project_id, number, title, state, blocked_from_state, block_reason, pause_reason, pause_detail)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, projectId);
      ps.setLong(3, number);
      ps.setString(4, title);
      ps.setString(5, state);
      ps.setString(6, blockedFromState);
      ps.setString(7, blockReason);
      ps.setString(8, pauseReason);
      ps.setString(9, pauseDetail);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssueAgentThread(
      Connection conn, UUID issueId, String agentName, UUID threadId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_agent_thread (issue_id, agent_name, thread_id)"
                + " values (?, ?, ?)")) {
      ps.setObject(1, issueId);
      ps.setString(2, agentName);
      ps.setObject(3, threadId);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssueStageBudget(Connection conn, UUID issueId, String state, int maxRuns)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_stage_budget (issue_id, state, max_runs)"
                + " values (?, ?, ?)")) {
      ps.setObject(1, issueId);
      ps.setString(2, state);
      ps.setInt(3, maxRuns);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssueRunRaw(
      Connection conn,
      UUID id,
      UUID issueId,
      long ordinal,
      String state,
      UUID sessionId,
      UUID threadId,
      String status,
      UUID startEntryId,
      UUID endEntryId,
      long remainingExecutionMs,
      Instant activeSince,
      Instant startedAt,
      Instant endedAt)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_run"
                + " (id, issue_id, ordinal, state, session_id, thread_id, status,"
                + " start_entry_id, end_entry_id, remaining_execution_ms, active_since, started_at, ended_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, issueId);
      ps.setLong(3, ordinal);
      ps.setString(4, state);
      ps.setObject(5, sessionId);
      ps.setObject(6, threadId);
      ps.setString(7, status);
      ps.setObject(8, startEntryId);
      ps.setObject(9, endEntryId);
      ps.setLong(10, remainingExecutionMs);
      ps.setTimestamp(11, activeSince != null ? Timestamp.from(activeSince) : null);
      ps.setTimestamp(
          12, startedAt != null ? Timestamp.from(startedAt) : Timestamp.from(Instant.now()));
      ps.setTimestamp(13, endedAt != null ? Timestamp.from(endedAt) : null);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssueRun(
      Connection conn,
      UUID id,
      UUID issueId,
      long ordinal,
      String state,
      UUID sessionId,
      UUID threadId,
      String status,
      UUID startEntryId,
      long remainingExecutionMs,
      boolean withActiveSince,
      boolean withEnded)
      throws SQLException {
    Instant now = Instant.now();
    insertIssueRunRaw(
        conn,
        id,
        issueId,
        ordinal,
        state,
        sessionId,
        threadId,
        status,
        startEntryId,
        withEnded ? startEntryId : null,
        remainingExecutionMs,
        withActiveSince ? now : null,
        now,
        withEnded ? now : null);
  }

  private void insertActivity(
      Connection conn,
      UUID issueId,
      long sequence,
      String kind,
      String actorType,
      String actorAgentName,
      UUID runId,
      String body,
      String dataJson,
      String idempotencyKey)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_activity"
                + " (issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data, idempotency_key, request_hash)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, '"
                + "0".repeat(64)
                + "')")) {
      ps.setObject(1, issueId);
      ps.setLong(2, sequence);
      ps.setString(3, kind);
      ps.setString(4, actorType);
      ps.setString(5, actorAgentName);
      ps.setObject(6, runId);
      ps.setString(7, body);
      ps.setString(8, dataJson != null ? dataJson : "{}");
      ps.setString(9, idempotencyKey);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertEvidence(
      Connection conn, UUID issueId, UUID blobId, String actorAgentName, UUID runId, String name)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_evidence"
                + " (issue_id, blob_id, actor_agent_name, run_id, name)"
                + " values (?, ?, ?, ?, ?)")) {
      ps.setObject(1, issueId);
      ps.setObject(2, blobId);
      ps.setString(3, actorAgentName);
      ps.setObject(4, runId);
      ps.setString(5, name);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertIssueWork(
      Connection conn, UUID issueId, long wakeVersion, String leaseToken, Instant leaseUntil)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "insert into project_issue_work"
                + " (issue_id, wake_version, due_at, lease_token, lease_until)"
                + " values (?, ?, current_timestamp, ?, ?)")) {
      ps.setObject(1, issueId);
      ps.setLong(2, wakeVersion);
      ps.setString(3, leaseToken);
      ps.setTimestamp(4, leaseUntil != null ? Timestamp.from(leaseUntil) : null);
      assertEquals(1, ps.executeUpdate());
    }
  }

  private void insertChatSession(Connection conn, UUID sessionId, UUID chatId) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("insert into chat_session (session_id, chat_id) values (?, ?)")) {
      ps.setObject(1, sessionId);
      ps.setObject(2, chatId);
      assertEquals(1, ps.executeUpdate());
    }
  }
}
