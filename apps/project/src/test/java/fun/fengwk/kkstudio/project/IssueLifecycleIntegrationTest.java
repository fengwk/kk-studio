package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionFacts;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileOutcome;
import fun.fengwk.kkstudio.project.reconcile.IssueReconciler;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.work.DurableWorkStore;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 目标 Project 模型完整生命周期集成测试（真实 PostgreSQL Testcontainers）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li><b>Stop 收尾</b>：空闲 Issue 停止直接落 USER 暂停门禁；活动 Run 经安全停止收尾为 CANCELLED 并落 USER 暂停门禁，轮询调谐绝不立即启动新
 *       Run；
 *   <li><b>在途副作用不明进入 UNKNOWN</b>：在途工具/模型调用不明时收尾为 UNKNOWN 并落 UNKNOWN 门禁；
 *   <li><b>UNKNOWN 门禁 Fail-Closed</b>：未核查的 UNKNOWN 严格拒绝 resume / transition / reopen / archive /
 *       deep delete 等所有推进；
 *   <li><b>强制人工核查依据</b>：resolveUnknown 必须提交非空白核查依据，留痕 RESOLVE_UNKNOWN 控制活动，将门禁转为 USER 暂停门禁，允许显式
 *       resume，且历史 Run 保持 UNKNOWN 事实；
 *   <li><b>Issue 深删除</b>：严格拒绝存在活动 Run 或 UNKNOWN 门禁；合法时在单个事务内按锁序与外键约束清理全部事实，最后删除 Issue 行；
 *   <li><b>Project 深删除</b>：严格拒绝项目下任何 Issue 处于活动或 UNKNOWN 状态；合法时深删除全部 Issue 并删除 Project 行，无孤儿数据。
 * </ul>
 */
class IssueLifecycleIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueReconciler reconciler;
  @Autowired private DurableWorkStore issueWorkQueue;
  @Autowired private IssueEvidenceService evidenceService;
  @Autowired private StorageUploadService uploadService;
  @Autowired private StorageBlobManager blobManager;
  @Autowired private HarnessRuntime runtime;

  /** 测试意图：验证空闲 Issue 调用 stopIssue 设置 USER 暂停门禁，Reconciler 调谐判定门禁关闭而不启动新 Run。 */
  @Test
  void stopIssueOnIdleIssueSetsUserPauseGateAndReconcilerDoesNotStartRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("StopIdleProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());

    Issue stopped =
        issueLifecycleService.stopIssue(
            inDesign.getId(), inDesign.getVersion(), key("stop"), "Human requested stop");
    assertEquals(PauseReason.USER.name(), stopped.getPauseReason());
    assertEquals("Human requested stop", stopped.getPauseDetail());

    List<IssueActivity> activities = issueEditingService.listActivities(issue.getId(), 0, 10);
    boolean hasStopActivity =
        activities.stream()
            .anyMatch(
                a -> a.getKind() == IssueActivityKind.CONTROL && a.getData().contains("STOP"));
    assertTrue(hasStopActivity);

    // Reconciler 认领 work 并调谐，判定门禁关闭直接收敛，绝不创建新 Run
    issueWorkQueue.request(issue.getId(), Duration.ZERO);
    WorkClaim claim = claimWork();
    assertNotNull(claim);
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);
    assertEquals(IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE, outcome);
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：验证活动 Run 执行 stopIssue 时安全收尾为 CANCELLED，Issue 落 USER 暂停门禁，轮询不新建 Run。 */
  @Test
  void stopIssueOnActiveRunCancelsRunAndLeavesUserPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("StopActiveProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun activeRun = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    assertEquals(IssueRunStatus.RUNNING, activeRun.getStatus());

    Issue fresh = issueEditingService.getIssue(issue.getId());
    Issue stopped =
        issueLifecycleService.stopIssue(
            fresh.getId(), fresh.getVersion(), key("stop"), "Stop the current run");
    assertEquals(PauseReason.USER.name(), stopped.getPauseReason());

    IssueRun cancelledRun = issueRunService.getRun(activeRun.getId());
    assertEquals(IssueRunStatus.CANCELLED, cancelledRun.getStatus());
    assertNotNull(cancelledRun.getEndedAt());

    issueWorkQueue.request(issue.getId(), Duration.ZERO);
    WorkClaim claim = claimWork();
    assertNotNull(claim);
    IssueReconcileOutcome outcome = reconciler.reconcile(claim);
    assertEquals(IssueReconcileOutcome.CONVERGED_UNDISPATCHABLE, outcome);
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
  }

  /** 测试意图：验证当 Thread 处于在途工具处理等不明状态时，stopIssue 将 Run 收敛为 UNKNOWN，并设置 UNKNOWN 暂停门禁。 */
  @Test
  void stopIssueOnInFlightUndeterminedRunConvergesToUnknownWithUnknownPauseGate() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("StopUnknownProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun activeRun = issueRunService.acceptRun(inDesign.getId(), key("accept"));

    // 受控 Harness 假件在投影边界模拟「仍在途」执行事实：RUNNING 工具 → hasWork；真实分类契约由 Runtime 测试覆盖。
    ThreadState threadState = mock(ThreadState.class);
    when(threadState.headEntryId()).thenReturn(headEntry(activeRun.getThreadId()));
    when(threadState.sessionId()).thenReturn(activeRun.getSessionId());
    // 收尾闭合 scope 会用同一 Thread 的 next_command_sequence 接受纯 SET_CONTRIBUTOR_STATE 批次，必须为正。
    when(threadState.nextCommandSequence()).thenReturn(1L);
    // 收尾区间校验会读取当前 root-to-head 条目，head 必须是真实存在的 Entry id。
    EntryPath entryPath = mock(EntryPath.class);
    Entry rootEntry = mock(Entry.class);
    when(rootEntry.id()).thenReturn(activeRun.getStartEntryId());
    when(entryPath.entries()).thenReturn(List.of(rootEntry));
    when(entryPath.head()).thenReturn(rootEntry);

    ThreadSnapshot inFlightSnapshot = mock(ThreadSnapshot.class);
    when(inFlightSnapshot.thread()).thenReturn(threadState);
    when(inFlightSnapshot.requireFullPath()).thenReturn(entryPath);
    when(inFlightSnapshot.executionFacts())
        .thenReturn(new ThreadExecutionFacts(true, false, false));
    when(runtime.getThreadSnapshot(activeRun.getThreadId())).thenReturn(inFlightSnapshot);

    Issue fresh = issueEditingService.getIssue(issue.getId());
    Issue stopped =
        issueLifecycleService.stopIssue(
            fresh.getId(), fresh.getVersion(), key("stop"), "Network timeout while executing tool");
    assertEquals(PauseReason.UNKNOWN.name(), stopped.getPauseReason());
    assertTrue(stopped.getPauseDetail().contains("Network timeout"));

    IssueRun unknownRun = issueRunService.getRun(activeRun.getId());
    assertEquals(IssueRunStatus.UNKNOWN, unknownRun.getStatus());
    assertNotNull(unknownRun.getEndedAt());
  }

  /** 测试意图：验证 UNKNOWN 门禁存在时，所有推进与删除操作均 Fail-Closed。 */
  @Test
  void unknownGatingFailsClosedOnAllProgressionAndDeletion() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("UnknownGatingProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun activeRun = issueRunService.acceptRun(inDesign.getId(), key("accept"));

    issueRunService.markUnknown(
        activeRun.getId(),
        activeRun.getVersion(),
        key("unknown"),
        headEntry(activeRun.getThreadId()),
        "Undetermined state");
    Issue unknownIssue = issueEditingService.getIssue(issue.getId());
    assertEquals(PauseReason.UNKNOWN.name(), unknownIssue.getPauseReason());

    // 1. resumeIssue 拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.resumeIssue(
                unknownIssue.getId(), unknownIssue.getVersion(), key("r")));

    // 2. transition 拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.transition(
                unknownIssue.getId(), unknownIssue.getVersion(), key("t"), "REVIEW"));

    // 3. reopen 拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.reopen(
                unknownIssue.getId(), unknownIssue.getVersion(), key("ro")));

    // 4. archive 拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> issueLifecycleService.archiveIssue(unknownIssue.getId(), unknownIssue.getVersion()));

    // 5. deleteIssue 拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> issueLifecycleService.deleteIssue(unknownIssue.getId(), unknownIssue.getVersion()));

    // 6. deleteProject 拒绝
    Project project = projectService.getProject(projectId);
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.deleteProject(projectId, project.getVersion()));

    // 7. acceptRun 拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> issueRunService.acceptRun(unknownIssue.getId(), key("a")));
  }

  /**
   * 测试意图：验证 resolveUnknown 必须提交非空白核查说明，持久化 RESOLVE_UNKNOWN 控制事实，将门禁转为 USER 暂停，允许后续显式 resume，Run 保持
   * UNKNOWN。
   */
  @Test
  void resolveUnknownMandatoryHumanVerificationAllowsExplicitResumeAndPreservesUnknownRun() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("ResolveUnknownProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun activeRun = issueRunService.acceptRun(inDesign.getId(), key("accept"));
    issueRunService.markUnknown(
        activeRun.getId(),
        activeRun.getVersion(),
        key("unk"),
        headEntry(activeRun.getThreadId()),
        "Side effects unclear");
    Issue unknownIssue = issueEditingService.getIssue(issue.getId());

    // 空白说明拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.resolveUnknown(
                unknownIssue.getId(), unknownIssue.getVersion(), key("res"), "   "));

    // 提交有效人工核查说明
    Issue resolved =
        issueLifecycleService.resolveUnknown(
            unknownIssue.getId(),
            unknownIssue.getVersion(),
            key("res"),
            "Checked third-party provider logs: no side effect occurred.");
    assertEquals(PauseReason.USER.name(), resolved.getPauseReason());
    assertEquals(
        "Checked third-party provider logs: no side effect occurred.", resolved.getPauseDetail());

    // 校验活动留痕
    List<IssueActivity> activities = issueEditingService.listActivities(issue.getId(), 0, 10);
    IssueActivity resolveActivity =
        activities.stream()
            .filter(
                a ->
                    a.getKind() == IssueActivityKind.CONTROL
                        && a.getData().contains("RESOLVE_UNKNOWN"))
            .findFirst()
            .orElse(null);
    assertNotNull(resolveActivity);
    assertTrue(resolveActivity.getData().contains("third-party provider logs"));

    // 历史 Run 仍为 UNKNOWN 事实，不捏造成功或取消
    IssueRun runFact = issueRunService.getRun(activeRun.getId());
    assertEquals(IssueRunStatus.UNKNOWN, runFact.getStatus());

    // 现在可以显式 resumeIssue 解除 USER 门禁
    Issue resumed =
        issueLifecycleService.resumeIssue(resolved.getId(), resolved.getVersion(), key("resume"));
    assertNull(resumed.getPauseReason());
    assertNull(resumed.getPauseDetail());
  }

  /** 测试意图：验证 Issue 深删除在合法状态下按规范顺序清理证据、活动、Work、预算、Run、AgentThread 及 Harness Session，最后删除 Issue 行。 */
  @Test
  void issueDeepDeleteReleasesAllResourcesInCanonicalOrder() {
    String agent1 = createAgent();
    String agent2 = createAgent();
    UUID projectId = createProjectWithStages("IssueDeepDeleteProj", agent1, agent2, 3);
    Issue issue = createIssue(projectId);

    // 1. 在 DESIGN 阶段接受并完成一次 Run
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t1"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun run1 = issueRunService.acceptRun(inDesign.getId(), key("run1"));
    UUID endEntry1 = appendHistoryEntry(run1.getThreadId());
    issueRunService.completeRun(
        run1.getId(), run1.getVersion(), key("comp1"), endEntry1, null, "REVIEW");

    // 2. 在 REVIEW 阶段接受并完成一次 Run
    Issue inReview = issueEditingService.getIssue(issue.getId());
    IssueRun run2 = issueRunService.acceptRun(inReview.getId(), key("run2"));
    UUID endEntry2 = appendHistoryEntry(run2.getThreadId());
    issueRunService.completeRun(
        run2.getId(), run2.getVersion(), key("comp2"), endEntry2, null, "DONE");

    // 3. 发布一份证据
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);
    evidenceService.publishBlob(issue.getId(), null, null, blobId, "proof.png");

    // 确认所有关联表均有数据
    assertEquals(
        1L, count("select count(*) from project_issue_evidence where issue_id = ?", issue.getId()));
    assertTrue(
        count("select count(*) from project_issue_activity where issue_id = ?", issue.getId()) > 0);
    assertEquals(
        1L,
        count(
            "select count(*) from durable_work where target_type = 'ISSUE' and target_id = ?",
            issue.getId()));
    assertEquals(
        2L,
        count("select count(*) from project_issue_stage_budget where issue_id = ?", issue.getId()));
    assertEquals(
        2L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        2L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));

    // 4. 深删除 Issue
    Issue currentIssue = issueEditingService.getIssue(issue.getId());
    issueLifecycleService.deleteIssue(currentIssue.getId(), currentIssue.getVersion());

    // 验证所有关联表数据完全清理，无孤儿数据
    assertEquals(
        0L, count("select count(*) from project_issue_evidence where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_activity where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count(
            "select count(*) from durable_work where target_type = 'ISSUE' and target_id = ?",
            issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_stage_budget where issue_id = ?", issue.getId()));
    assertEquals(
        0L, count("select count(*) from project_issue_run where issue_id = ?", issue.getId()));
    assertEquals(
        0L,
        count("select count(*) from project_issue_agent_thread where issue_id = ?", issue.getId()));
    assertEquals(0L, count("select count(*) from project_issue where id = ?", issue.getId()));

    // 项目本身依然存在
    assertNotNull(projectService.getProject(projectId));
  }

  /** 测试意图：验证 Project 深删除包含多个 Issue 时，若任一 Issue 处于活动或 UNKNOWN 则整体拒绝；合法时深删除全部 Issue 与 Project。 */
  @Test
  void projectDeepDeleteWithMultipleIssuesAndStrictRejection() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("ProjectDeepDeleteProj", agent, agent, 3);

    // Issue 1: 完成
    Issue issue1 = createIssue(projectId);
    issueLifecycleService.transition(issue1.getId(), issue1.getVersion(), key("t1"), "DESIGN");
    Issue inDesign1 = issueEditingService.getIssue(issue1.getId());
    IssueRun run1 = issueRunService.acceptRun(inDesign1.getId(), key("run1"));
    UUID endEntry1 = appendHistoryEntry(run1.getThreadId());
    issueRunService.completeRun(
        run1.getId(), run1.getVersion(), key("comp1"), endEntry1, null, "REVIEW");
    Issue inReview1 = issueEditingService.getIssue(issue1.getId());
    IssueRun run1r = issueRunService.acceptRun(inReview1.getId(), key("run1r"));
    UUID endEntry1r = appendHistoryEntry(run1r.getThreadId());
    issueRunService.completeRun(
        run1r.getId(), run1r.getVersion(), key("comp1r"), endEntry1r, null, "DONE");

    // Issue 2: 拥有活动 Run
    Issue issue2 = createIssue(projectId);
    issueLifecycleService.transition(issue2.getId(), issue2.getVersion(), key("t2"), "DESIGN");
    Issue inDesign2 = issueEditingService.getIssue(issue2.getId());
    IssueRun run2 = issueRunService.acceptRun(inDesign2.getId(), key("run2"));

    // 1. 项目删除被拒绝（Issue 2 有活动 Run）
    Project project = projectService.getProject(projectId);
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.deleteProject(projectId, project.getVersion()));

    // 2. 将 Issue 2 置为 UNKNOWN 门禁
    issueRunService.markUnknown(
        run2.getId(),
        run2.getVersion(),
        key("unk2"),
        headEntry(run2.getThreadId()),
        "Unknown network failure");

    // 项目删除依然拒绝（Issue 2 有 UNKNOWN 门禁）
    Project projectAfterUnknown = projectService.getProject(projectId);
    assertThrows(
        ProjectValidationException.class,
        () -> projectService.deleteProject(projectId, projectAfterUnknown.getVersion()));

    // 3. 人工核查 Issue 2 并 resolveUnknown -> 转换为 USER 暂停门禁
    Issue unkIssue2 = issueEditingService.getIssue(issue2.getId());
    issueLifecycleService.resolveUnknown(
        unkIssue2.getId(), unkIssue2.getVersion(), key("res2"), "Residuals cleaned up");

    // 4. 现在项目深删除应当成功执行
    Project projectReady = projectService.getProject(projectId);
    projectService.deleteProject(projectId, projectReady.getVersion());

    // 验证两个 Issue 的所有数据与 Project 行全部清理
    assertEquals(0L, count("select count(*) from project_issue where project_id = ?", projectId));
    assertEquals(0L, count("select count(*) from project where id = ?", projectId));
  }

  /** 测试意图：验证 stopIssue 与 resolveUnknown 精确重试幂等回放，异指纹冲突。 */
  @Test
  void stopIssueAndResolveUnknownIdempotentReplay() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("ReplayProj", agent, agent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    String stopKey = key("stop");

    Issue stopped1 =
        issueLifecycleService.stopIssue(
            inDesign.getId(), inDesign.getVersion(), stopKey, "Detail 1");
    // 重复调用同键同指纹：精确回放
    Issue stopped2 =
        issueLifecycleService.stopIssue(
            inDesign.getId(), inDesign.getVersion(), stopKey, "Detail 1");
    assertEquals(stopped1.getId(), stopped2.getId());

    // 异指纹冲突
    assertThrows(
        Exception.class,
        () ->
            issueLifecycleService.stopIssue(
                inDesign.getId(), inDesign.getVersion(), stopKey, "Different Detail"));

    // 恢复暂停后接受 Run 并转为 UNKNOWN 状态
    issueLifecycleService.resumeIssue(inDesign.getId(), stopped1.getVersion(), key("res1"));
    Issue resumed = issueEditingService.getIssue(issue.getId());
    issueRunService.acceptRun(resumed.getId(), key("accept"));
    Issue fresh = issueEditingService.getIssue(issue.getId());
    IssueRun activeRun = issueRunService.getActiveRun(issue.getId());
    issueRunService.markUnknown(
        activeRun.getId(),
        activeRun.getVersion(),
        key("u"),
        headEntry(activeRun.getThreadId()),
        "fail");
    Issue unknownIssue = issueEditingService.getIssue(issue.getId());

    String resolveKey = key("res");
    Issue resolved1 =
        issueLifecycleService.resolveUnknown(
            unknownIssue.getId(), unknownIssue.getVersion(), resolveKey, "Proof 1");
    Issue resolved2 =
        issueLifecycleService.resolveUnknown(
            unknownIssue.getId(), unknownIssue.getVersion(), resolveKey, "Proof 1");
    assertEquals(resolved1.getId(), resolved2.getId());

    assertThrows(
        Exception.class,
        () ->
            issueLifecycleService.resolveUnknown(
                unknownIssue.getId(), unknownIssue.getVersion(), resolveKey, "Different Proof"));
  }

  /** 测试意图：验证阶段预算授权、重置、获取、AgentThread 列举与需求事实编辑。 */
  @Test
  void stageBudgetAndIssueDetailOperations() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("BudgetDetailProj", agent, agent, 5);
    Issue issue = createIssue(projectId);

    // updateIssue
    Issue updated =
        issueEditingService.updateIssue(issue.getId(), issue.getVersion(), "New Title", "New Desc");
    assertEquals("New Title", updated.getTitle());
    assertEquals("New Desc", updated.getDescription());

    // listIssues
    List<Issue> activeIssues = issueEditingService.listIssues(projectId, false);
    assertEquals(1, activeIssues.size());

    // authorizeStageBudget
    issueLifecycleService.transition(issue.getId(), updated.getVersion(), key("t"), "DESIGN");
    Issue inDesign = issueEditingService.getIssue(issue.getId());
    IssueRun run = issueRunService.acceptRun(inDesign.getId(), key("acc"));

    // listAgentThreads
    var threads = issueEditingService.listAgentThreads(issue.getId());
    assertEquals(1, threads.size());
    assertEquals(agent, threads.getFirst().agentName());

    // getStageBudget
    var budget = issueStageBudgetService.getStageBudget(issue.getId(), "DESIGN");
    assertEquals(5, budget.maxRuns());
    assertEquals(1L, budget.usedRuns());

    // resetStageBudget (requires no active run)
    UUID end = appendHistoryEntry(run.getThreadId());
    issueRunService.completeRun(run.getId(), run.getVersion(), key("comp"), end, null, null);
    Issue fresh = issueEditingService.getIssue(issue.getId());
    var resetBudget =
        issueStageBudgetService.resetStageBudget(
            fresh.getId(), fresh.getVersion(), key("reset"), "DESIGN", 10);
    assertEquals(10, resetBudget.maxRuns());
  }

  /** 测试意图：验证 Issue 归档与解归档、在已归档 Issue 上的写操作防御拒绝。 */
  @Test
  void issueArchiveAndUnarchiveOperations() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("ArchiveProj", agent, agent, 3);
    Issue issue = createIssue(projectId);

    Issue archived = issueLifecycleService.archiveIssue(issue.getId(), issue.getVersion());
    assertTrue(archived.isArchived());

    // 幂等重复归档返回原 Issue
    Issue archived2 = issueLifecycleService.archiveIssue(archived.getId(), archived.getVersion());
    assertEquals(archived.getId(), archived2.getId());

    // 在已归档 Issue 上尝试修改需求、评论、流转等操作均被拒绝
    assertThrows(
        ProjectValidationException.class,
        () -> issueEditingService.updateIssue(archived.getId(), archived.getVersion(), "T", "D"));
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueEditingService.appendComment(
                archived.getId(), archived.getVersion(), key("c"), "body"));
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.transition(
                archived.getId(), archived.getVersion(), key("t"), "DESIGN"));

    // 解归档回到可编辑状态
    Issue unarchived =
        issueLifecycleService.unarchiveIssue(archived.getId(), archived.getVersion());
    assertFalse(unarchived.isArchived());

    // 幂等重复解归档返回原 Issue
    Issue unarchived2 =
        issueLifecycleService.unarchiveIssue(unarchived.getId(), unarchived.getVersion());
    assertEquals(unarchived.getId(), unarchived2.getId());
  }

  /** 测试意图：验证业务阻塞 blockIssue、恢复 recoverIssue、DONE 重开 reopen、活动窗口查询与指示投递。 */
  @Test
  void issueBlockRecoverReopenAndActivityOperations() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("BlockReopenProj", agent, agent, 3);
    Issue issue = createIssue(projectId);

    // appendComment
    Issue withComment =
        issueEditingService.appendComment(
            issue.getId(), issue.getVersion(), key("c1"), "Comment text");
    var activities = issueEditingService.listActivities(issue.getId(), 0, 10);
    assertTrue(activities.stream().anyMatch(a -> "Comment text".equals(a.getBody())));

    // 非活动 Run 时投递指示拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueEditingService.appendInstruction(
                withComment.getId(), withComment.getVersion(), key("ins"), "Instruction text"));

    // 流转到 DESIGN 阶段
    Issue fresh = issueEditingService.getIssue(issue.getId());
    Issue inDesign =
        issueLifecycleService.transition(fresh.getId(), fresh.getVersion(), key("t1"), "DESIGN");

    // 非 BLOCKED 状态调用 recover 拒绝
    assertThrows(
        ProjectValidationException.class,
        () ->
            issueLifecycleService.recoverIssue(
                inDesign.getId(), inDesign.getVersion(), key("rec_err")));

    // 业务阻塞
    Issue blocked =
        issueLifecycleService.blockIssue(
            inDesign.getId(), inDesign.getVersion(), key("b1"), "Waiting on vendor API");
    assertTrue(blocked.isBlocked());
    assertEquals("DESIGN", blocked.getBlockedFromState());
    assertEquals("Waiting on vendor API", blocked.getBlockReason());

    // 恢复阻塞
    Issue recovered =
        issueLifecycleService.recoverIssue(blocked.getId(), blocked.getVersion(), key("rec1"));
    assertFalse(recovered.isBlocked());
    assertEquals("DESIGN", recovered.getState());

    // 启动 Run 并在有活动 Run 时投递指示
    IssueRun run = issueRunService.acceptRun(recovered.getId(), key("acc"));
    fresh = issueEditingService.getIssue(issue.getId());
    issueEditingService.appendInstruction(
        fresh.getId(), fresh.getVersion(), key("ins1"), "Do something");

    // 完成并交接到 DONE
    UUID end = appendHistoryEntry(run.getThreadId());
    issueRunService.completeRun(run.getId(), run.getVersion(), key("comp"), end, null, "REVIEW");
    Issue inReview = issueEditingService.getIssue(issue.getId());
    IssueRun run2 = issueRunService.acceptRun(inReview.getId(), key("acc2"));
    UUID end2 = appendHistoryEntry(run2.getThreadId());
    issueRunService.completeRun(run2.getId(), run2.getVersion(), key("comp2"), end2, null, "DONE");

    // DONE 重开到 INIT
    Issue doneIssue = issueEditingService.getIssue(issue.getId());
    assertEquals("DONE", doneIssue.getState());
    Issue reopened =
        issueLifecycleService.reopen(doneIssue.getId(), doneIssue.getVersion(), key("ro1"));
    assertEquals("INIT", reopened.getState());

    // publishBlob with agent and runId + listEvidence
    UUID blobId = UUID.randomUUID();
    insertStorageBlob(blobId);
    var ev = evidenceService.publishBlob(issue.getId(), agent, run2.getId(), blobId, "report.pdf");
    assertEquals("report.pdf", ev.getName());
    var evList = evidenceService.listEvidence(issue.getId());
    assertEquals(1, evList.size());
  }

  /** 测试意图：验证 Issue 与 Project 删除时的版本 CAS 冲突。 */
  @Test
  void deleteVersionConflictRejections() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("CasProj", agent, agent, 3);
    Issue issue = createIssue(projectId);

    assertThrows(
        ProjectVersionConflictException.class,
        () -> issueLifecycleService.deleteIssue(issue.getId(), 999L));

    Project project = projectService.getProject(projectId);
    assertThrows(
        ProjectVersionConflictException.class, () -> projectService.deleteProject(projectId, 999L));
  }

  private WorkClaim claimWork() {
    String leaseToken = UUID.randomUUID().toString();
    return issueWorkQueue.claimNext(leaseToken, Duration.ofMinutes(1)).orElse(null);
  }

  private void insertStorageBlob(UUID blobId) {
    String sha256 =
        UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "insert into storage_blob (id, sha256, size_bytes, media_type, ref_count, state) "
            + "values (?, ?, 1024, 'image/png', 1, 'ACTIVE')",
        blobId,
        sha256);
  }
}
