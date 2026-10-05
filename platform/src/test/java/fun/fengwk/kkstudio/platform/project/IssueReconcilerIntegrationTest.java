package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.platform.project.tool.IssueTransitionService;
import fun.fengwk.kkstudio.project.controller.IssueReconcileOutcome;
import fun.fengwk.kkstudio.project.controller.IssueReconciler;
import fun.fengwk.kkstudio.project.controller.IssueReconciler.IssueWorkClaim;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link IssueReconciler} 状态机调谐器的真实 PostgreSQL（Testcontainers）集成测试。
 *
 * <p>测试意图：严格验证 Issue 调谐器每次调谐的确定性决策与持久化事实，包含归档与 DONE 收敛、空闲派发与额度授权、
 * 保留态与暂停态不自动执行、执行时长耗尽的原子失败暂停收尾、静止点交接推进、迟到旧 Run 的安全收尾、定向指示精确投递与游标推进、 在途执行挂起延后、等待与恢复门禁、租约续租与
 * heartbeat 等全部决策分支与数据库表行状态。
 */
class IssueReconcilerIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueReconciler reconciler;
  @Autowired private IssueWorkStore issueWorkStore;
  @Autowired private IssueTransitionService issueTransitionService;
  @Autowired private HarnessRuntime harnessRuntime;

  private final Map<UUID, EntryPayload> customPayloads = new ConcurrentHashMap<>();

  @BeforeEach
  void setUpHarnessSnapshot() {
    customPayloads.clear();
    when(harnessRuntime.getThreadSnapshot(any(UUID.class)))
        .thenAnswer(invocation -> createSnapshot(invocation.getArgument(0)));
  }

  /** 构造完整的受控 ThreadSnapshot，使 entryPath.head() 与 entry.payload() 具备合法的非空结构。 */
  private ThreadSnapshot createSnapshot(UUID threadId) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select session_id, head_entry_id, next_command_sequence, execution_control,"
                + " input_through_sequence from harness_thread where id = ?",
            threadId);
    UUID headEntryId = (UUID) row.get("head_entry_id");
    ThreadState thread = mock(ThreadState.class);
    when(thread.id()).thenReturn(threadId);
    when(thread.sessionId()).thenReturn((UUID) row.get("session_id"));
    when(thread.headEntryId()).thenReturn(headEntryId);
    when(thread.nextCommandSequence())
        .thenReturn(((Number) row.get("next_command_sequence")).longValue());
    // 持久执行控制来自真实行：运行阶段判定读取的就是这一事实，测试不得伪造固定值。
    when(thread.executionControl())
        .thenReturn(ThreadExecutionControl.valueOf((String) row.get("execution_control")));
    when(thread.inputThroughSequence())
        .thenReturn(((Number) row.get("input_through_sequence")).longValue());

    List<UUID> ids = new ArrayList<>();
    UUID cursor = headEntryId;
    while (cursor != null) {
      ids.add(cursor);
      cursor =
          jdbc.queryForObject(
              "select parent_entry_id from harness_entry where id = ?", UUID.class, cursor);
    }
    Collections.reverse(ids);
    List<Entry> entries = new ArrayList<>(ids.size());
    for (UUID entryId : ids) {
      Entry entry = mock(Entry.class);
      when(entry.id()).thenReturn(entryId);
      EntryPayload payload =
          customPayloads.getOrDefault(entryId, new CustomEntryPayload("test", "test", 1, "{}"));
      when(entry.payload()).thenReturn(payload);
      entries.add(entry);
    }
    EntryPath path = mock(EntryPath.class);
    when(path.entries()).thenReturn(List.copyOf(entries));
    if (!entries.isEmpty()) {
      when(path.head()).thenReturn(entries.get(entries.size() - 1));
    }
    return new ThreadSnapshot(thread, path, List.of(), null, List.of(), List.of(), List.of());
  }

  /** 获取指定 Issue 的调度工作 Claim，保证在调谐前该工作已到期并被成功锁定。 */
  private IssueWorkClaim claimWork(UUID issueId) {
    issueWorkStore.requestWork(issueId, Duration.ZERO);
    String leaseToken = key("lease");
    IssueWork claimed =
        issueWorkStore
            .claimNext(leaseToken, Duration.ofSeconds(30))
            .orElseThrow(
                () -> new IllegalStateException("Failed to claim work for issue " + issueId));
    return new IssueWorkClaim(
        claimed.getIssueId(),
        claimed.getLeaseToken(),
        claimed.getLeaseUntil(),
        claimed.getWakeVersion());
  }

  private void insertStorageBlob(UUID blobId) {
    String sha256 =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state) "
            + "values (?, ?, 1024, 'application/pdf', 1, 'ACTIVE')",
        blobId,
        sha256);
  }

  /** 模拟来源 Run Session 当前持有该 Blob：公开证据必须能证明归属，而不是只看 Blob 活跃。 */
  private void retainSessionBlob(UUID threadId, UUID blobId) {
    UUID sessionId =
        jdbc.queryForObject(
            "select session_id from harness_thread where id = ?", UUID.class, threadId);
    jdbc.update(
        "insert into session_blob_ref (session_id, blob_id) values (?, ?)", sessionId, blobId);
  }

  private ToolInvocation unknownTool(String toolName, String callId) {
    ToolInvocation tool = mock(ToolInvocation.class);
    when(tool.status()).thenReturn(ToolInvocationStatus.UNKNOWN);
    when(tool.call()).thenReturn(new ToolCall(callId, toolName, "{}"));
    when(tool.id()).thenReturn(UUID.randomUUID());
    return tool;
  }

  private ThreadSnapshot withTools(UUID threadId, List<ToolInvocation> tools) {
    return overlay(threadId, List.of(), null, tools);
  }

  /**
   * 在真实 Thread 行之上投影一组受控命令/模型/工具状态。
   *
   * <p>真实 {@link ThreadSnapshot} 构造会校验 live context 形状不变量，而这些测试只需要驱动 Reconciler 对状态分类的读取， 因此用 mock
   * 只覆盖被消费的访问器。
   */
  private ThreadSnapshot overlay(
      UUID threadId,
      List<ThreadCommand> commands,
      ModelInvocation model,
      List<ToolInvocation> tools) {
    ThreadSnapshot base = createSnapshot(threadId);
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(base.thread());
    when(snapshot.entryPath()).thenReturn(base.entryPath());
    when(snapshot.queuedCommands()).thenReturn(commands);
    when(snapshot.model()).thenReturn(model);
    when(snapshot.toolSiblings()).thenReturn(tools);
    return snapshot;
  }

  private MessagePayload assistantMessage(AgentMessageContent... contents) {
    ModelUsage usage = new ModelUsage(0, 0, 0, 0, 0, 0, 0);
    ModelCost cost =
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    AssistantMessageMetadata metadata =
        new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage, cost);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(contents)), metadata, null);
  }

  private ToolInvocation mockTool(ToolInvocationStatus status) {
    ToolInvocation tool = mock(ToolInvocation.class);
    when(tool.status()).thenReturn(status);
    return tool;
  }

  /** 测试意图：Claim 记录与调谐输入参数非空校验严格。 */
  @Test
  void claimValidationRejectsNullFields() {
    assertThrows(NullPointerException.class, () -> reconciler.reconcile(null));
    assertThrows(
        NullPointerException.class, () -> new IssueWorkClaim(null, "token", Instant.now(), 1L));
    assertThrows(
        NullPointerException.class,
        () -> new IssueWorkClaim(UUID.randomUUID(), null, Instant.now(), 1L));
    assertThrows(
        NullPointerException.class, () -> new IssueWorkClaim(UUID.randomUUID(), "token", null, 1L));
  }

  /** 测试意图：已归档的 Issue 不会继续派发执行，reconcile 返回 CONVERGED_ARCHIVED 并清除 mailbox 工作行。 */
  @Test
  void archivedIssueConvergesAndRemovesWorkRow() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("归档Issue调谐", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    issue = issueService.getIssue(issue.getId());
    issueService.archiveIssue(issue.getId(), issue.getVersion());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_ARCHIVED, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue where id = ? and archived_at is not null",
            issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：已完成（DONE）的 Issue 不会继续派发执行，reconcile 返回 CONVERGED_ARCHIVED 并清除 mailbox 工作行。 */
  @Test
  void doneIssueConvergesAndRemovesWorkRow() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("DONE状态Issue调谐", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t1"), "DESIGN");
    issue = issueService.getIssue(issue.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t2"), "REVIEW");
    issue = issueService.getIssue(issue.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t3"), "DONE");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_ARCHIVED, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals("DONE", issueService.getIssue(issue.getId()).getState());
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：处于启用且配置了 Agent 的工作阶段且无活动 Run 的 Issue，reconcile 会自动接受一次 Run、授权阶段预算并写入 RUN 活动。 */
  @Test
  void idleIssueInAgentStageAcceptsRunAndAuthorizesBudget() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("空闲接受Run", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_ACCEPTED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where issue_id = ? and status = 'RUNNING'"
                + " and ordinal = 1 and state = 'DESIGN'",
            issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_stage_budget where issue_id = ? and state ="
                + " 'DESIGN' and max_runs = 3",
            issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'RUN'",
            issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ? and agent_name ="
                + " ?",
            issue.getId(),
            agent));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        2L,
        (long)
            jdbc.queryForObject(
                "select next_run_ordinal from project_issue where id = ?",
                Long.class,
                issue.getId()));
  }

  /** 测试意图：处于保留阶段（如 INIT）且无活动 Run 的 Issue 不会自动派发，reconcile 返回 CONVERGED_NO_AGENT 并清除 mailbox。 */
  @Test
  void idleIssueInReservedInitStageConvergesNoAgent() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("INIT保留阶段", agent, agent, 3);
    Issue issue = createIssue(projectId);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_NO_AGENT, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals("INIT", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：处于无 Agent 绑定的阶段且无活动 Run 的 Issue 不会自动派发，reconcile 返回 CONVERGED_NO_AGENT 并清除 mailbox。 */
  @Test
  void idleIssueInStageWithoutAgentConvergesNoAgent() {
    Project project = projectService.createProject("人工阶段项目", "描述", true);
    String wf =
        "{\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"MANUAL\"]},"
            + "{\"state\":\"MANUAL\",\"name\":\"人工阶段\",\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";
    projectService.updateWorkflow(project.getId(), project.getVersion(), wf);
    Issue issue = createIssue(project.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "MANUAL");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_NO_AGENT, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals("MANUAL", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：当项目工作流配置无法被业务正常解码时，reconcile 返回 CONVERGED_NO_AGENT 并清除 mailbox。 */
  @Test
  void idleIssueWithCorruptedWorkflowJsonConvergesNoAgent() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("损坏工作流项目", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    jdbc.update(
        "update project set workflow = '{\"states\":[{\"state\":\"INVALID LOWERCASE\"}]}'::jsonb"
            + " where id = ?",
        projectId);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_NO_AGENT, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：处于控制暂停门禁下且无活动 Run 的 Issue 无法自动派发，reconcile 返回 CONVERGED_UNDISPATCHABLE 并清除 mailbox。 */
  @Test
  void idleIssueInPausedStateConvergesUndispatchable() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("暂停阶段空闲", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    issue = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        issue.getId(), issue.getVersion(), key("pause"), PauseReason.USER, "人工暂停");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertTrue(issueService.getIssue(issue.getId()).isPaused());
  }

  /**
   * 测试意图：阶段预算已耗尽且无活动 Run 的 Issue 不会新建 Run，reconcile 返回 CONVERGED_BUDGET_EXHAUSTED 并保留 mailbox 延后轮询。
   */
  @Test
  void idleIssueWithExhaustedStageBudgetConvergesBudgetExhausted() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("阶段额度耗尽", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID endEntry = appendHistoryEntry(run.getThreadId());
    issueRunService.completeRun(
        run.getId(), run.getVersion(), key("complete"), endEntry, null, null);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_BUDGET_EXHAUSTED, outcome);
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：活动执行时长耗尽且处于静止状态的 Run 会在单事务内失败收尾，并将 Issue 置为 ERROR 暂停门禁，保留 mailbox 等待恢复。 */
  @Test
  void activeRunBudgetExhaustedFailsRunAndPausesIssueWithErrorGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("执行时长耗尽", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    jdbc.update(
        "update project_issue_run set remaining_execution_ms = 100, active_since ="
            + " current_timestamp - interval '10 seconds' where id = ?",
        run.getId());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_BUDGET_EXHAUSTED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'FAILED' and error ="
                + " 'Run active execution budget is exhausted' and end_entry_id is not null and"
                + " ended_at is not null",
            run.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue where id = ? and pause_reason = 'ERROR' and"
                + " pause_detail = 'Run active execution budget is exhausted'",
            issue.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind = 'CONTROL'",
            issue.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /** 测试意图：在完全静止点且存在交接意图（next_state）的活动 Run，reconcile 会正常收尾该 Run 并推进 Issue 状态到目标阶段。 */
  @Test
  void quiescentRunHandoffCompletesRunAndAdvancesIssueState() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("交接推进", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);
    issueTransitionService.accept(
        run.getThreadId(),
        ProjectRunScope.SCHEMA_VERSION,
        frozenRunScopeJson(run.getThreadId()),
        "REVIEW");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_HANDED_OFF, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'COMPLETED' and"
                + " next_state = 'REVIEW' and end_entry_id = ?",
            run.getId(),
            newHead));
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_agent_thread where issue_id = ? and agent_name ="
                + " ?",
            issue.getId(),
            agent));
    assertEquals(
        2L,
        count(
            "select count(*) from project_issue_activity where issue_id = ? and kind ="
                + " 'STATE_CHANGE'",
            issue.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /** 测试意图：阶段已不匹配但有历史产出的迟到 Run，reconcile 会安全将其收尾为 COMPLETED，但绝不推进 Issue 当前阶段。 */
  @Test
  void staleRunWithProgressClosesRunWithoutAdvancingIssueStage() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("迟到Run有进展", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);
    jdbc.update(
        "update project_issue set state = 'REVIEW', version = version + 1 where id = ?",
        issue.getId());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.STALE_RUN_CLOSED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'COMPLETED' and"
                + " next_state is null and end_entry_id = ?",
            run.getId(),
            newHead));
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：阶段已不匹配且无历史产出的迟到 Run，reconcile 将其失败收尾并将 Issue 暂停置 ERROR。 */
  @Test
  void staleRunWithoutProgressFailsRunAndPausesIssue() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("迟到Run无进展", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    jdbc.update(
        "update project_issue set state = 'REVIEW', version = version + 1 where id = ?",
        issue.getId());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.STALE_RUN_CLOSED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'FAILED' and error ="
                + " 'Run no longer matches the current issue stage'",
            run.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue where id = ? and pause_reason = 'ERROR'",
            issue.getId()));
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：工作流中阶段 Agent 发生变更导致所有权不再匹配时，reconcile 安全收尾旧 Run 为 COMPLETED 且不推进阶段。 */
  @Test
  void staleRunStageNoLongerOwnedClosesStaleRun() {
    String designAgent1 = createAgent();
    String designAgent2 = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages("Agent变更迟到Run", designAgent1, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);

    jdbc.update(
        "update project set workflow = ?::jsonb, version = version + 1 where id = ?",
        workflowJson(designAgent2, reviewAgent, 3),
        projectId);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.STALE_RUN_CLOSED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'COMPLETED' and"
                + " end_entry_id = ?",
            run.getId(),
            newHead));
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：未投递的 INSTRUCTION 活动会被精确投递给当前 Run 绑定的 Agent Thread，推进 observed sequence，再次调谐不会重复投递。 */
  @Test
  void instructionDeliveredExactlyOnceAndAdvancesRunSequence() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("指示投递", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issue = issueService.getIssue(issue.getId());
    issueService.appendInstruction(issue.getId(), issue.getVersion(), key("inst"), "请调整方案设计");
    Long instSeq =
        jdbc.queryForObject(
            "select sequence from project_issue_activity where issue_id = ? and kind ="
                + " 'INSTRUCTION'",
            Long.class,
            issue.getId());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.INSTRUCTION_DELIVERED, outcome);
    assertEquals(
        instSeq,
        jdbc.queryForObject(
            "select observed_activity_sequence from project_issue_run where id = ?",
            Long.class,
            run.getId()));

    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);

    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, secondOutcome);
    assertEquals(
        instSeq,
        jdbc.queryForObject(
            "select observed_activity_sequence from project_issue_run where id = ?",
            Long.class,
            run.getId()));
  }

  /** 测试意图：检视到非 INSTRUCTION 活动（如普通评论）时，reconciler 会跳过并就地推进 observed sequence，避免游标被卡住。 */
  @Test
  void nonInstructionActivityAdvancesObservedSequence() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("普通评论跳过", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issue = issueService.getIssue(issue.getId());
    issueService.appendComment(issue.getId(), issue.getVersion(), key("c"), "这是一条普通评论");
    Long commentSeq =
        jdbc.queryForObject(
            "select sequence from project_issue_activity where issue_id = ? and kind = 'COMMENT'",
            Long.class,
            issue.getId());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, outcome);
    assertEquals(
        commentSeq,
        jdbc.queryForObject(
            "select observed_activity_sequence from project_issue_run where id = ?",
            Long.class,
            run.getId()));
  }

  /** 测试意图：处于 RUNNING 状态的活动 Run 在 Issue 遇到控制暂停时会被置为 WAITING 状态并停止活动计时。 */
  @Test
  void activeRunRunningAndIssuePausedWaitsRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("运行中遇到暂停", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issue = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        issue.getId(), issue.getVersion(), key("pause"), PauseReason.USER, "人工暂停");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'WAITING' and"
                + " active_since is null",
            run.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /** 测试意图：处于 WAITING 状态且 Issue 仍然暂停的 Run，reconcile 返回 WAITING_FOR_GATE 并继续延后检查。 */
  @Test
  void activeRunWaitingAndIssueStillPausedWaitsForGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("等待中仍暂停", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issue = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        issue.getId(), issue.getVersion(), key("pause"), PauseReason.USER, "人工暂停");
    issueRunService.waitRun(run.getId(), run.getVersion());

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'WAITING'",
            run.getId()));
  }

  /** 测试意图：处于 WAITING 状态的 Run 在 Issue 暂停解除后被恢复为 RUNNING 状态，不消耗新的阶段额度。 */
  @Test
  void activeRunWaitingAndIssueResumedResumesRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("等待中恢复", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issue = issueService.getIssue(issue.getId());
    issueService.pauseIssue(
        issue.getId(), issue.getVersion(), key("pause"), PauseReason.USER, "人工暂停");
    issueRunService.waitRun(run.getId(), run.getVersion());
    issue = issueService.getIssue(issue.getId());
    issueService.resumeIssue(issue.getId(), issue.getVersion(), key("resume"));

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_RESUMED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'RUNNING' and"
                + " active_since is not null",
            run.getId()));
  }

  /** 测试意图：在完全静止点有历史产出但无交接意图的 Run，reconcile 将其正常收尾为 COMPLETED，Issue 保持当前阶段。 */
  @Test
  void activeRunWithProgressCompletesRunWithoutHandoff() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("有产出无交接收尾", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_COMPLETED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'COMPLETED' and"
                + " next_state is null and end_entry_id = ?",
            run.getId(),
            newHead));
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
  }

  /** 测试意图：在完全静止点且尚无历史产出、无交接、无待投递指令的 Run，reconcile 返回 DEFERRED_IDLE 并延后重检。 */
  @Test
  void activeRunIdleWithNoProgressDefersIdle() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("无产出静止延后", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'RUNNING'",
            run.getId()));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /**
   * 测试意图：Run 收尾只取决于本次 root Join 是否已冻结结果，不再等待委派子树静止；即使存在未静止的委派子 Thread， 只要 root Join 匹配，Run
   * 就必须立即正常收尾为 COMPLETED。
   */
  @Test
  void delegatedChildThreadDoesNotBlockRunCompletion() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("委派子线程不阻塞收尾", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID newHead = appendHistoryEntry(run.getThreadId());
    // 未静止的委派子树不再参与 Run 收尾判定。
    insertDelegatedChildThread(run.getThreadId());

    matchRunJoin(run.getId(), newHead);
    assertEquals(
        IssueReconcileOutcome.RUN_COMPLETED, reconciler.reconcile(claimWork(issue.getId())));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'COMPLETED' and"
                + " end_entry_id = ?",
            run.getId(),
            newHead));
  }

  /** 建一个归入父执行子树的委派子 Thread（独立 Session 与 ROOT Entry）：委派子树的存在不再改写父 Thread 的执行控制状态， 也不再参与 Run 收尾判定。 */
  private UUID insertDelegatedChildThread(UUID parentThreadId) {
    UUID sessionId = UUID.randomUUID();
    UUID rootEntryId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_session (id, name, created_at) values (?, ?, current_timestamp)",
        sessionId,
        "delegated-" + sessionId);
    jdbc.update(
        "insert into harness_entry (id, session_id, entry_type, payload, created_at)"
            + " values (?, ?, 'ROOT', '{}'::jsonb, current_timestamp)",
        rootEntryId,
        sessionId);
    UUID childThreadId = UUID.randomUUID();
    jdbc.update(
        "insert into harness_thread (id, session_id, parent_thread_id, head_entry_id,"
            + " creation_request_hash, name, yolo_enabled, execution_control,"
            + " input_through_sequence, next_command_sequence, version, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?, false, 'RUNNABLE', 0, 1, 0, current_timestamp,"
            + " current_timestamp)",
        childThreadId,
        sessionId,
        parentThreadId,
        rootEntryId,
        "0".repeat(64),
        "delegated-" + childThreadId);
    return childThreadId;
  }

  /** 测试意图：存在入队命令时，reconcile 判定为在途执行尚未静止，返回 DEFERRED_PROCESSING 延后重检。 */
  @Test
  void activeRunProcessingDefersProcessingWithQueuedCommands() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("命令在途延后", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    ThreadSnapshot processing =
        overlay(run.getThreadId(), List.of(mock(ThreadCommand.class)), null, List.of());
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(processing);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'RUNNING'",
            run.getId()));
  }

  /** 测试意图：存在活跃模型调用时，reconcile 判定为在途执行尚未静止，返回 DEFERRED_PROCESSING 延后重检。 */
  @Test
  void activeRunProcessingDefersProcessingWithActiveModel() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("模型在途延后", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    ModelInvocation model = mock(ModelInvocation.class);
    when(model.status()).thenReturn(ModelInvocationStatus.RUNNING);
    ThreadSnapshot processing = overlay(run.getThreadId(), List.of(), model, List.of());
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(processing);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'RUNNING'",
            run.getId()));
  }

  /** 测试意图：TurnEnd 要求继续驱动模型时，reconcile 判定为在途执行尚未静止，返回 DEFERRED_PROCESSING 延后重检。 */
  @Test
  void activeRunProcessingDefersProcessingWithContinueModel() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("连续Turn在途延后", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    ThreadSnapshot base = createSnapshot(run.getThreadId());
    TurnEndPayload turnEnd =
        new TurnEndPayload(UUID.randomUUID(), TurnEndOutcome.COMPLETED, true, null, null);
    Entry headEntry = base.entryPath().head();
    when(headEntry.payload()).thenReturn(turnEnd);
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(base);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'RUNNING'",
            run.getId()));
  }

  /** 测试意图：调谐开始时执行 heartbeat 自动续租，并在结束时按 rescheduleWork 语义释放租约与延后检查。 */
  @Test
  void reconcileHeartbeatRenewsLeaseAndReschedulesWork() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("心跳与租约行为", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    issueWorkStore.requestWork(issue.getId(), Duration.ZERO);
    String token = key("lease");
    IssueWork claimed = issueWorkStore.claimNext(token, Duration.ofSeconds(5)).orElseThrow();
    IssueWorkClaim claim =
        new IssueWorkClaim(issue.getId(), token, claimed.getLeaseUntil(), claimed.getWakeVersion());

    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, outcome);
    // reconcile 结束后 rescheduleWork 释放了 lease_token 与 lease_until，并保留 due_at
    IssueWork work = issueWorkStore.getWork(issue.getId());
    assertNull(work.getLeaseToken());
    assertNull(work.getLeaseUntil());
    assertNotNull(work.getDueAt());
    assertEquals(work.getUpdatedAt().plusSeconds(1), work.getDueAt());
  }

  /** 测试意图：不存在的 Issue 在 lockIssue 返回 null 后安全收敛，完成不存在的 work 不导致事务回滚。 */
  @Test
  void reconcileHandlesMissingIssueGracefully() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("已取代Claim调谐", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    // 不存在的 Issue 触发 lockIssue == null 路径，并经 finish(completeWork) 安全处理
    IssueWorkClaim nonExistentClaim =
        new IssueWorkClaim(UUID.randomUUID(), key("token"), Instant.now().plusSeconds(30), 1L);
    IssueReconcileOutcome outcome = reconciler.reconcile(nonExistentClaim);
    assertEquals(IssueReconcileOutcome.CONVERGED_ARCHIVED, outcome);
  }

  // ========================== 缺陷 1-8 针对性语义测试 ==========================

  /**
   * 缺陷 1 测试意图：区分真正在途执行与静止等待。当存在 WAITING_INPUT 或 WAITING_APPROVAL 的工具调用时， 属于安全静止点，Run 必须转入 WAITING
   * 状态并停止扣减活动执行时长；后续再次调谐时仍维持 WAITING_FOR_GATE 且不扣时长； 当工具回答完成后门禁打开，Run 恢复为 RUNNING。
   */
  @Test
  void motionlessWaitToolTransitionsRunToWaitingAndStopsActiveTime() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("静止工具等待测试", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    // 模拟工具处于 WAITING_INPUT 状态（问卷安全等待）
    ThreadSnapshot waiting =
        withTools(run.getThreadId(), List.of(mockTool(ToolInvocationStatus.WAITING_INPUT)));
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(waiting);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, outcome);
    IssueRun waitingRun = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.WAITING, waitingRun.getStatus());
    assertNull(waitingRun.getActiveSince());
    long remainingBudget = waitingRun.getRemainingExecutionMs();

    // 再次调谐：工具仍处于等待，Run 维持 WAITING，剩余执行时长不变
    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);
    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, secondOutcome);
    assertEquals(remainingBudget, issueRunService.getRun(run.getId()).getRemainingExecutionMs());

    // 工具回答完成，进入普通静止点
    ThreadSnapshot settled = createSnapshot(run.getThreadId());
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(settled);
    IssueWorkClaim resumeClaim = claimWork(issue.getId());
    IssueReconcileOutcome resumeOutcome = reconciler.reconcile(resumeClaim);
    assertEquals(IssueReconcileOutcome.RUN_RESUMED, resumeOutcome);
    IssueRun resumedRun = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, resumedRun.getStatus());
    assertNotNull(resumedRun.getActiveSince());
    assertEquals(run.getId(), resumedRun.getId());
  }

  /**
   * 缺陷 1 测试意图：当阶段最大额度为 1 时，Run 接受后阶段已无新建额度；当该 Run 进入 WAITING 后， 门禁解除时恢复的是同一 Run（不新建 Run，不重新扣阶段额度）。
   */
  @Test
  void waitingRunResumesSameRunWhenLastAuthorizedBudget() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("最后额度等待恢复测试", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    assertEquals(1L, run.getOrdinal());

    // 置为 WAITING
    issueRunService.waitRun(run.getId(), run.getVersion());

    // 调谐：此时 stage budget used == 1 (maxRuns == 1)，但 WAITING 的 Run 必须成功 resume 同一 Run
    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_RESUMED, outcome);
    IssueRun resumed = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.RUNNING, resumed.getStatus());
    assertEquals(run.getId(), resumed.getId());
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /**
   * 缺陷 2 测试意图：活动时长耗尽时，如果外部执行仍在途（例如模型调用中），绝不能立即 failRun 杀死可能存在副作用的在途执行； 必须先关闭派发门禁（pause ERROR）并返回
   * DEFERRED_PROCESSING；只有当执行完全静止后才可安全 failRun。
   */
  @Test
  void budgetExhaustedWithInFlightExecutionPausesIssueAndDefersWithoutFailingRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("在途时长耗尽安全停机", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    // 将时长设置为已耗尽
    jdbc.update(
        "update project_issue_run set remaining_execution_ms = 100, active_since ="
            + " current_timestamp - interval '10 seconds' where id = ?",
        run.getId());

    // 模拟在途模型调用
    ThreadSnapshot base = createSnapshot(run.getThreadId());
    ModelInvocation activeModel = mock(ModelInvocation.class);
    when(activeModel.status()).thenReturn(ModelInvocationStatus.RUNNING);
    ThreadSnapshot inFlightSnapshot =
        new ThreadSnapshot(
            base.thread(),
            base.entryPath(),
            List.of(),
            activeModel,
            List.of(),
            List.of(),
            List.of());
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(inFlightSnapshot);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    // 1. 在途时不直接失败，而是先关闭 Issue 门禁并返回 DEFERRED_PROCESSING
    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, outcome);
    Issue pausedIssue = issueService.getIssue(issue.getId());
    assertEquals("ERROR", pausedIssue.getPauseReason());
    assertEquals("Run active execution budget is exhausted", pausedIssue.getPauseDetail());
    // Run 依然保留为 RUNNING，没有被替换或提前终结
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());

    // 2. 模型执行收敛至完全静止
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(base);
    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);

    // 静止后安全收尾为 RUN_BUDGET_EXHAUSTED
    assertEquals(IssueReconcileOutcome.RUN_BUDGET_EXHAUSTED, secondOutcome);
    assertEquals(IssueRunStatus.FAILED, issueRunService.getRun(run.getId()).getStatus());
  }

  /**
   * 缺陷 3 测试意图：BLOCKED 状态等同于门禁关闭。处于 WAITING 状态的 Run 在 Issue BLOCKED 期间不得自动恢复； 只有通过 recoverIssue
   * 显式解除业务阻塞后才允许 resumeRun。
   */
  @Test
  void blockedIssueClosesGateAndBlocksWaitingRunResume() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("BLOCKED门禁测试", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueRunService.waitRun(run.getId(), run.getVersion());

    issue = issueService.getIssue(issue.getId());
    issueService.blockIssue(issue.getId(), issue.getVersion(), key("block"), "等待外部依赖");
    assertTrue(issueService.getIssue(issue.getId()).isBlocked());
    assertTrue(issueService.getIssue(issue.getId()).isGateClosed());

    // 处于 BLOCKED 时调谐，必须维持 WAITING_FOR_GATE，禁止 resume
    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);
    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, outcome);
    assertEquals(IssueRunStatus.WAITING, issueRunService.getRun(run.getId()).getStatus());

    // 显式恢复业务阻塞
    issue = issueService.getIssue(issue.getId());
    issueService.recoverIssue(issue.getId(), issue.getVersion(), key("recover"));
    assertFalse(issueService.getIssue(issue.getId()).isGateClosed());

    // 恢复后调谐：Run 恢复为 RUNNING
    IssueWorkClaim resumeClaim = claimWork(issue.getId());
    IssueReconcileOutcome resumeOutcome = reconciler.reconcile(resumeClaim);
    assertEquals(IssueReconcileOutcome.RUN_RESUMED, resumeOutcome);
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
  }

  /** 测试意图：处于 BLOCKED 状态且无活动 Run 的 Issue，reconcileIdle 判定为 CONVERGED_UNDISPATCHABLE 并收敛。 */
  @Test
  void blockedIssueInIdleConvergesUndispatchable() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("空闲BLOCKED调谐", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    issue = issueService.getIssue(issue.getId());
    issueService.blockIssue(issue.getId(), issue.getVersion(), key("b"), "业务阻断");

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);
    assertEquals(IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /**
   * 缺陷 4 测试意图：即使在 250 条普通评论之后到达的定向 INSTRUCTION，也不会被分页窗口遗漏； 并且交接（next_state）绝不会在 INSTRUCTION
   * 投递前提前提交，必须先投递全部指示后再交接。
   */
  @Test
  void instructionDeliveredBeforeHandoffAcrossLargeCommentWindow() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("大窗口指示优先交接", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    // 插入 220 条普通评论（超过单页 200 限制）
    issue = issueService.getIssue(issue.getId());
    for (int i = 1; i <= 220; i++) {
      issue =
          issueService.appendComment(issue.getId(), issue.getVersion(), key("c" + i), "评论 " + i);
    }
    // 随后追加一条 INSTRUCTION
    issue =
        issueService.appendInstruction(
            issue.getId(), issue.getVersion(), key("inst"), "第221条是指示，必须先处理");
    Long instSeq =
        jdbc.queryForObject(
            "select sequence from project_issue_activity where issue_id = ? and kind ="
                + " 'INSTRUCTION'",
            Long.class,
            issue.getId());

    // 模拟 Agent 发起交接请求
    issueTransitionService.accept(
        run.getThreadId(),
        ProjectRunScope.SCHEMA_VERSION,
        frozenRunScopeJson(run.getThreadId()),
        "REVIEW");
    run = issueRunService.getRun(run.getId());
    assertEquals("REVIEW", run.getNextState());

    // 调谐：必须优先投递指示，绝不能直接交接收尾！
    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.INSTRUCTION_DELIVERED, outcome);
    assertEquals(
        instSeq,
        jdbc.queryForObject(
            "select observed_activity_sequence from project_issue_run where id = ?",
            Long.class,
            run.getId()));
    // Issue 状态依然为 DESIGN，交接尚未提交
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());

    // 指示投递后，模拟生成产出并完全静止
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);
    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);

    // 此时才安全交接
    assertEquals(IssueReconcileOutcome.RUN_HANDED_OFF, secondOutcome);
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
    assertEquals(IssueRunStatus.COMPLETED, issueRunService.getRun(run.getId()).getStatus());
  }

  /**
   * 缺陷 5 测试意图：Run 收尾时自动计算并冻结 final_answer_entry_id，并将其中的 ResourceMessageContent 作为 Issue 证据发布（使用
   * IssueEvidenceService）。
   */
  @Test
  void closeoutFreezesFinalAnswerAndPublishesReportEvidence() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("收尾报告与证据发布", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);
    retainSessionBlob(run.getThreadId(), blobId);

    UUID assistantEntryId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        assistantEntryId,
        assistantMessage(
            new TextMessageContent("设计方案终稿"),
            ResourceMessageContent.media(blobId, "architecture-design.pdf")));

    matchRunJoin(run.getId(), assistantEntryId);
    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_COMPLETED, outcome);
    IssueRun completed = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    assertEquals(assistantEntryId, completed.getFinalAnswerEntryId());
    assertEquals(assistantEntryId, completed.getEndEntryId());

    // 验证证据已发布到 project_issue_evidence 表
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ? and"
                + " name = 'architecture-design.pdf' and actor_agent_name = ? and run_id = ?",
            issue.getId(),
            blobId,
            agent,
            run.getId()));
  }

  /**
   * E01：工具 UNKNOWN 已收敛为模型反馈，不再由 IssueReconciler 升级为人工核查门禁。存在在途命令时只延后；一旦只剩结果未定的工具，同样不构成 静止点或故障，Run
   * 保持 RUNNING 并返回 DEFERRED_IDLE，不自动完成、交接或发布证据，Issue 既不暂停也不推进阶段。
   */
  @Test
  void unknownToolIsModelFeedbackNotAHumanGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("UNKNOWN工具模型反馈", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    issueTransitionService.accept(
        run.getThreadId(),
        ProjectRunScope.SCHEMA_VERSION,
        frozenRunScopeJson(run.getThreadId()),
        "REVIEW");

    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);
    retainSessionBlob(run.getThreadId(), blobId);
    UUID assistantEntryId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        assistantEntryId,
        assistantMessage(
            new TextMessageContent("中间报告"),
            ResourceMessageContent.media(blobId, "should-stay-private.pdf")));

    // 真实快照构造会校验 tool sibling 形状；这里只覆盖 Reconciler 消费的工具列表。
    ThreadSnapshot settled = createSnapshot(run.getThreadId());
    List<ToolInvocation> unknownTools =
        List.of(unknownTool("shell", "call-z"), unknownTool("http", "call-a"));
    ThreadSnapshot stillRunning = mock(ThreadSnapshot.class);
    when(stillRunning.thread()).thenReturn(settled.thread());
    when(stillRunning.entryPath()).thenReturn(settled.entryPath());
    when(stillRunning.queuedCommands()).thenReturn(List.of(mock(ThreadCommand.class)));
    when(stillRunning.model()).thenReturn(null);
    when(stillRunning.toolSiblings()).thenReturn(unknownTools);
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(stillRunning);

    IssueReconcileOutcome deferred = reconciler.reconcile(claimWork(issue.getId()));
    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, deferred);
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());

    ThreadSnapshot unknownSnapshot = withTools(run.getThreadId(), unknownTools);
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(unknownSnapshot);
    IssueReconcileOutcome outcome = reconciler.reconcile(claimWork(issue.getId()));

    // UNKNOWN 工具是已收敛的模型反馈：不构成门禁，Run 保持运行并延后重检。
    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, outcome);
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
    assertEquals("DESIGN", issueService.getIssue(issue.getId()).getState());
    assertNull(issueService.getIssue(issue.getId()).getPauseReason());
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ?",
            issue.getId(),
            blobId));
    assertEquals(
        1L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
  }

  /**
   * E01 边界：UNKNOWN 不再优先于审批/输入等待。UNKNOWN 只是已收敛的模型反馈，不构成门禁；同一快照中仍有等待审批的工具时，Run 安全停在该
   * 等待点上，既不升级为人工核查，也不被提前收尾。
   */
  @Test
  void unknownToolDoesNotOutrankApprovalWaiting() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("UNKNOWN不优先于审批等待", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    appendHistoryEntry(run.getThreadId());

    ThreadSnapshot snapshot =
        withTools(
            run.getThreadId(),
            List.of(
                unknownTool("shell", "call-1"), mockTool(ToolInvocationStatus.WAITING_APPROVAL)));
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(snapshot);

    IssueReconcileOutcome outcome = reconciler.reconcile(claimWork(issue.getId()));

    assertEquals(IssueReconcileOutcome.WAITING_FOR_GATE, outcome);
    assertEquals(IssueRunStatus.WAITING, issueRunService.getRun(run.getId()).getStatus());
    assertNull(issueService.getIssue(issue.getId()).getPauseReason());
  }

  /**
   * E01 边界：迟到（阶段或归属已不匹配）的旧 Run 只持有结果未定的工具时，UNKNOWN 不再构成人工核查门禁，也不构成安全静止点；既然没有本次 root Join 冻结的结果，旧
   * Run 必须失败关闭并暂停 Issue 等待人工介入，绝不推进当前阶段。
   */
  @Test
  void staleRunWithUnknownToolFailsClosedWithoutMatchedJoin() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("迟到UNKNOWN工具失败关闭", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    appendHistoryEntry(run.getThreadId());

    // 强制推进 Issue 阶段，使 Run 成为迟到回调
    jdbc.update(
        "update project_issue set state = 'REVIEW', version = version + 1 where id = ?",
        issue.getId());

    ThreadSnapshot stale =
        withTools(run.getThreadId(), List.of(unknownTool("shell", "call-stale")));
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(stale);

    IssueReconcileOutcome outcome = reconciler.reconcile(claimWork(issue.getId()));

    assertEquals(IssueReconcileOutcome.STALE_RUN_CLOSED, outcome);
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_run where id = ? and status = 'FAILED' and error ="
                + " 'Run no longer matches the current issue stage'",
            run.getId()));
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue where id = ? and pause_reason = 'ERROR'",
            issue.getId()));
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
  }

  /** E02：只有最终 assistant 答复明确引用、且来源 Session 持有的 Resource 才公开。中间消息、工具/用户附件和非本 Session 的 Blob 保持私有。 */
  @Test
  void closeoutPublishesOnlyFinalAssistantResourcesHeldByRunSession() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("最终答复证据边界", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    UUID intermediateBlob = UUID.randomUUID();
    UUID userBlob = UUID.randomUUID();
    UUID finalBlob = UUID.randomUUID();
    UUID foreignBlob = UUID.randomUUID();
    for (UUID blobId : List.of(intermediateBlob, userBlob, finalBlob, foreignBlob)) {
      insertStorageBlob(blobId);
    }
    retainSessionBlob(run.getThreadId(), intermediateBlob);
    retainSessionBlob(run.getThreadId(), userBlob);
    retainSessionBlob(run.getThreadId(), finalBlob);

    UUID intermediateId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        intermediateId,
        assistantMessage(ResourceMessageContent.media(intermediateBlob, "scratch.pdf")));
    UUID userId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        userId,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(ResourceMessageContent.media(userBlob, "user-note.pdf"))),
            null,
            null));
    UUID finalId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        finalId,
        assistantMessage(
            new TextMessageContent("终稿"),
            ResourceMessageContent.media(finalBlob, "final-report.pdf"),
            ResourceMessageContent.media(foreignBlob, "foreign.pdf")));

    matchRunJoin(run.getId(), finalId);
    IssueReconcileOutcome outcome = reconciler.reconcile(claimWork(issue.getId()));
    assertEquals(IssueReconcileOutcome.RUN_COMPLETED, outcome);
    assertEquals(finalId, issueRunService.getRun(run.getId()).getFinalAnswerEntryId());
    assertEquals(
        1L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ? and name = 'final-report.pdf'",
            issue.getId(),
            finalBlob));
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id in (?, ?, ?)",
            issue.getId(),
            intermediateBlob,
            userBlob,
            foreignBlob));
  }

  /**
   * 缺陷 6 测试意图：迟到（stale）的旧 Run 在处于在途执行时不得强制收尾，必须挂起等待（DEFERRED_PROCESSING）； 静止后安全收尾为
   * COMPLETED，且其原本携带的 next_state 绝对不得推进当前 Issue 的阶段。
   */
  @Test
  void staleInFlightRunDefersAndDoesNotAdvanceIssueStage() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("迟到在途Run安全停机", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    // 将 Issue 强制推进到 REVIEW 阶段，并将 Run 的 next_state 设为 DONE
    jdbc.update(
        "update project_issue set state = 'REVIEW', version = version + 1 where id = ?",
        issue.getId());
    jdbc.update(
        "update project_issue_run set next_state = 'DONE', version = version + 1 where id = ?",
        run.getId());

    // 模拟迟到 Run 正在执行工具调用
    ThreadSnapshot inFlightSnapshot =
        withTools(run.getThreadId(), List.of(mockTool(ToolInvocationStatus.RUNNING)));
    when(harnessRuntime.getThreadSnapshot(run.getThreadId())).thenReturn(inFlightSnapshot);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    // 1. 在途时不收尾，返回 DEFERRED_PROCESSING
    assertEquals(IssueReconcileOutcome.DEFERRED_PROCESSING, outcome);
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(run.getId()).getStatus());
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());

    // 2. 执行静止且有进展
    UUID newHead = appendHistoryEntry(run.getThreadId());
    matchRunJoin(run.getId(), newHead);
    when(harnessRuntime.getThreadSnapshot(run.getThreadId()))
        .thenAnswer(inv -> createSnapshot(run.getThreadId()));
    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);

    // 3. 静止后安全收尾，next_state 被丢弃，Issue 状态依然保持在 REVIEW（未被推进到 DONE）
    assertEquals(IssueReconcileOutcome.STALE_RUN_CLOSED, secondOutcome);
    IssueRun closedRun = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.COMPLETED, closedRun.getStatus());
    assertNull(closedRun.getNextState());
    assertEquals(newHead, closedRun.getEndEntryId());
    assertEquals("REVIEW", issueService.getIssue(issue.getId()).getState());
  }

  /** 缺陷 8 测试意图：归档的项目（project.isArchived()）不会执行任何派发，reconcile 返回 CONVERGED_ARCHIVED。 */
  @Test
  void archivedProjectConvergesAndRemovesWorkRow() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("归档项目调谐", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    jdbc.update("update project set archived_at = current_timestamp where id = ?", projectId);

    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.CONVERGED_ARCHIVED, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_work where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 缺陷 8 测试意图：当工作流中的工作阶段没有配置 Agent 时，检查 stage.hasAgent() 绝不抛出 NullPointerException。 */
  @Test
  void stageWithoutAgentDoesNotThrowNpeWhenActiveRunChecked() {
    Project project = projectService.createProject("无Agent阶段防NPE", "描述", true);
    String wf =
        "{\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"MANUAL\"]},"
            + "{\"state\":\"MANUAL\",\"name\":\"人工阶段\",\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}";
    projectService.updateWorkflow(project.getId(), project.getVersion(), wf);
    Issue issue = createIssue(project.getId());
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "MANUAL");

    // 验证空闲派发安全收敛为 CONVERGED_NO_AGENT
    IssueWorkClaim claim = claimWork(issue.getId());
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);
    assertEquals(IssueReconcileOutcome.CONVERGED_NO_AGENT, outcome);
  }

  /**
   * 缺陷 7 测试意图：reconcileIdle 不再使用 catch(RuntimeException) 吞掉异常；真实的系统或数据库异常必须向外传播，以便 dispatcher 重试。
   */
  @Test
  void reconcileIdlePropagatesRealExceptionsInsteadOfSwallowing() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("异常向外传播测试", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");

    // 删除 agent_definition 模拟数据损坏或环境异常，验证异常向外传播而不是被吞为 CONVERGED_UNDISPATCHABLE
    jdbc.update("delete from agent_definition where name = ?", agent);

    IssueWorkClaim claim = claimWork(issue.getId());
    assertThrows(RuntimeException.class, () -> reconciler.reconcile(claim));
  }

  /**
   * 缺陷 5/7 增强测试意图：当 Run 产出的历史消息中包含不可用/不存在（或未处于 ACTIVE 状态）的 Resource Blob 引用时，
   * 调谐器通过预校验安全跳过证据发布，绝不调用可能抛出异常的参与事务方法，不导致物理事务被标记为 rollback-only（不会产生 UnexpectedRollbackException）；
   * Run 确定性收尾为 COMPLETED，无该未发布证据行，mailbox 正常收敛，后续再次调谐不报错。
   */
  @Test
  void closeoutWithUnpublishableResourceCompletesWithoutTransactionPoisoning() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("不可用资源收尾测试", agent, agent, 1);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    // 构造一个未在 storage_blob 中注册（不存在）的虚假 blobId
    UUID unpublishableBlobId = UUID.randomUUID();

    UUID assistantEntryId = appendHistoryEntry(run.getThreadId());
    customPayloads.put(
        assistantEntryId,
        assistantMessage(
            new TextMessageContent("包含无效附件的设计报告"),
            ResourceMessageContent.media(unpublishableBlobId, "missing-file.pdf")));

    matchRunJoin(run.getId(), assistantEntryId);
    IssueWorkClaim claim = claimWork(issue.getId());
    // 调谐必须正常完成，绝不能因为内部异常或 rollback-only 抛出 UnexpectedRollbackException
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.RUN_COMPLETED, outcome);
    IssueRun completed = issueRunService.getRun(run.getId());
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());
    assertEquals(assistantEntryId, completed.getFinalAnswerEntryId());
    assertEquals(assistantEntryId, completed.getEndEntryId());

    // 验证未向 project_issue_evidence 插入无效 Blob 的证据记录
    assertEquals(
        0L,
        count(
            "select count(*) from project_issue_evidence where issue_id = ? and blob_id = ?",
            issue.getId(),
            unpublishableBlobId));

    // 再次调谐已收敛的 Issue，确保状态机和 mailbox 完全健康可重复调用
    IssueWorkClaim secondClaim = claimWork(issue.getId());
    IssueReconcileOutcome secondOutcome = reconciler.reconcile(secondClaim);
    assertEquals(IssueReconcileOutcome.CONVERGED_BUDGET_EXHAUSTED, secondOutcome);
  }
}
