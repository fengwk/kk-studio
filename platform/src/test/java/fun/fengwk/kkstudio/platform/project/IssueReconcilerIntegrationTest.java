package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconcileOutcome;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconciler;
import fun.fengwk.kkstudio.platform.project.controller.IssueReconciler.IssueWorkClaim;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueWork;
import fun.fengwk.kkstudio.platform.project.model.PauseReason;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.service.IssueWorkStore;
import fun.fengwk.kkstudio.platform.project.tool.IssueTransitionService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

  @BeforeEach
  void setUpHarnessSnapshot() {
    when(harnessRuntime.getThreadSnapshot(any(UUID.class)))
        .thenAnswer(invocation -> createSnapshot(invocation.getArgument(0)));
  }

  /** 构造完整的受控 ThreadSnapshot，使 entryPath.head() 与 entry.payload() 具备合法的非空结构。 */
  private ThreadSnapshot createSnapshot(UUID threadId) {
    Map<String, Object> row =
        jdbc.queryForMap(
            "select session_id, head_entry_id, next_command_sequence from harness_thread where id"
                + " = ?",
            threadId);
    UUID headEntryId = (UUID) row.get("head_entry_id");
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn((UUID) row.get("session_id"));
    when(thread.headEntryId()).thenReturn(headEntryId);
    when(thread.nextCommandSequence())
        .thenReturn(((Number) row.get("next_command_sequence")).longValue());

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
      when(entry.payload()).thenReturn(new CustomEntryPayload("test", "test", 1, "{}"));
      entries.add(entry);
    }
    EntryPath path = mock(EntryPath.class);
    when(path.entries()).thenReturn(List.copyOf(entries));
    if (!entries.isEmpty()) {
      when(path.head()).thenReturn(entries.get(entries.size() - 1));
    }
    return new ThreadSnapshot(thread, path, List.of(), null, List.of(), List.of());
  }

  /** 获取指定 Issue 的调度工作 Claim，保证在调谐前该工作已到期并被成功锁定。 */
  private IssueWorkClaim claimWork(UUID issueId) {
    Instant now = Instant.now();
    issueWorkStore.requestWork(issueId, now.minusSeconds(1));
    String leaseToken = key("lease");
    Instant leaseUntil = now.plusSeconds(30);
    IssueWork claimed =
        issueWorkStore
            .claimNext(now, leaseToken, leaseUntil)
            .orElseThrow(
                () -> new IllegalStateException("Failed to claim work for issue " + issueId));
    return new IssueWorkClaim(
        claimed.getIssueId(),
        claimed.getLeaseToken(),
        claimed.getLeaseUntil(),
        claimed.getWakeVersion());
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

  /** 测试意图：活动执行时长耗尽的 Run 会在单事务内失败收尾，并将 Issue 置为 ERROR 暂停门禁，保留 mailbox 等待恢复。 */
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
    issueTransitionService.accept(run.getThreadId(), "REVIEW");

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

  /** 测试意图：存在入队命令时，reconcile 判定为在途执行尚未静止，返回 DEFERRED_PROCESSING 延后重检。 */
  @Test
  void activeRunProcessingDefersProcessingWithQueuedCommands() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("命令在途延后", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));

    ThreadSnapshot base = createSnapshot(run.getThreadId());
    ThreadSnapshot processing =
        new ThreadSnapshot(
            base.thread(),
            base.entryPath(),
            List.of(mock(ThreadCommand.class)),
            null,
            List.of(),
            List.of());
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

    ThreadSnapshot base = createSnapshot(run.getThreadId());
    ThreadSnapshot processing =
        new ThreadSnapshot(
            base.thread(),
            base.entryPath(),
            List.of(),
            mock(ModelInvocation.class),
            List.of(),
            List.of());
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

    Instant now = Instant.now();
    issueWorkStore.requestWork(issue.getId(), now.minusSeconds(1));
    String token = key("lease");
    Instant shortLeaseUntil = now.plusSeconds(5);
    IssueWork claimed = issueWorkStore.claimNext(now, token, shortLeaseUntil).orElseThrow();
    IssueWorkClaim claim =
        new IssueWorkClaim(issue.getId(), token, shortLeaseUntil, claimed.getWakeVersion());

    IssueReconcileOutcome outcome = reconciler.reconcile(claim);

    assertEquals(IssueReconcileOutcome.DEFERRED_IDLE, outcome);
    // reconcile 结束后 rescheduleWork 释放了 lease_token 与 lease_until，并保留 due_at
    IssueWork work = issueWorkStore.getWork(issue.getId());
    assertNull(work.getLeaseToken());
    assertNull(work.getLeaseUntil());
    assertNotNull(work.getDueAt());
  }

  /** 测试意图：当 claim 在调谐完成前已被新唤醒或并发操作 supersede 时，finish 安全记录围栏不匹配且不导致事务回滚。 */
  @Test
  void reconcileFinishHandlesSupersededClaimGracefully() {
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
}
