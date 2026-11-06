package fun.fengwk.kkstudio.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.project.ProjectTestSupport;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.UUID;

/**
 * 交接登记的真实 PostgreSQL 验收：调用 Thread 只用来定位 Project 自己的稳定绑定与活动 Run，真正的控制面是活动 Run 与该 Thread 的真实历史位置。
 *
 * <p>测试意图：覆盖交接意图只落 {@code project_issue_run.next_state}（Issue 阶段不变）、同目标幂等重放不推进版本、异目标与非法边在写前拒绝且无部分写、
 * 没有稳定绑定的 Thread（fork）被拒绝、Run 收尾后迟到交接被拒绝，以及同一 Thread 后续 Run 期间落库的旧 assistant 调用绝不获得新 Run
 * 的写权限。Harness 行由基座受控假件在同一事务写入，因此断言的是真实行与真实外键。
 */
class IssueTransitionIntegrationTest extends ProjectTestSupport {

  private static final String NO_ACTIVE_RUN = "no active run";
  private static final String NOT_AN_AGENT_THREAD = "bound to an Issue agent";
  private static final String NOT_THE_RUNS_TURN = "assistant turn of the active run";

  @Autowired private IssueTransitionService issueTransitionService;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private ApplicationContext applicationContext;

  /** 测试意图：生产组合根里受控组件各只有一个 Bean、事务边界真的生效（否则锁序与 CAS 都失去意义），且工具以 SELECTABLE 发布、由 Agent 显式声明。 */
  @Test
  void composesExactlyOneControlledDefinition() {
    assertEquals(1, applicationContext.getBeanNamesForType(IssueTransitionService.class).length);
    assertEquals(1, applicationContext.getBeanNamesForType(IssueTransitionTool.class).length);
    assertTrue(AopUtils.isAopProxy(issueTransitionService), "交接事务代理必须生效");

    ToolContribution tool = harnessCatalog.findTool(IssueTransitionTool.NAME).orElseThrow();
    assertEquals("project", tool.id().contributorId().value());
    assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
    assertTrue(
        harnessCatalog.selectableTools().stream()
            .anyMatch(candidate -> candidate.id().equals(tool.id())));
    // Issue 的运行上下文由 Markdown 任务消息承载，不注册 custom entry type。
    HarnessRegistrar registrar = mock(HarnessRegistrar.class);
    applicationContext.getBean(ProjectHarnessContributor.class).contribute(registrar);
    verify(registrar, never()).registerCustomEntryType(anyString(), anyString(), anyInt());
    verify(registrar, never()).registerCustomEntryType(anyString(), anyString());
  }

  /** 建一个 Issue 处于 DESIGN 阶段、已有活动 Run 的场景，返回该 Run。 */
  private RunFixture designRun(String title) {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages(title, designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    return new RunFixture(issue.getId(), run);
  }

  private record RunFixture(UUID issueId, IssueRun run) {}

  private UUID appendAssistantTurn(UUID threadId) {
    return appendHandoffAssistant(threadId);
  }

  /** 以 Run 自己 Thread 上真实的 assistant 历史位置调用业务工具（模型不提供任何身份）。 */
  private IssueTransitionService.IssueTransitionResult accept(
      RunFixture fixture, UUID assistantEntryId, String toState) {
    return issueTransitionService.accept(fixture.run().getThreadId(), assistantEntryId, toState);
  }

  /** 断言一次交接被确定性拒绝，并且拒绝原因包含预期片段。 */
  private void assertRejected(
      UUID threadId, UUID assistantEntryId, String toState, String expected) {
    Runnable call = () -> issueTransitionService.accept(threadId, assistantEntryId, toState);
    DomainException rejected = assertThrows(DomainException.class, call::run);
    assertTrue(rejected.getMessage().contains(expected), rejected.getMessage());
  }

  private void assertRejected(
      RunFixture fixture, UUID assistantEntryId, String toState, String expected) {
    assertRejected(fixture.run().getThreadId(), assistantEntryId, toState, expected);
  }

  private String nextState(UUID runId) {
    return jdbc.queryForObject(
        "select next_state from project_issue_run where id = ?", String.class, runId);
  }

  private long runVersion(UUID runId) {
    Long version =
        jdbc.queryForObject(
            "select version from project_issue_run where id = ?", Long.class, runId);
    return version == null ? 0L : version;
  }

  /** 测试意图：合法交接只登记 Run 的 next_state，Issue 阶段在安全收尾前保持不变。 */
  @Test
  void registersHandoffOnTheActiveRunOnly() {
    RunFixture fixture = designRun("交接登记");
    UUID assistantEntryId = appendAssistantTurn(fixture.run().getThreadId());

    IssueTransitionService.IssueTransitionResult accepted =
        accept(fixture, assistantEntryId, "REVIEW");

    assertFalse(accepted.replayed());
    assertEquals("DESIGN", accepted.fromState());
    assertEquals("REVIEW", accepted.toState());
    assertEquals(fixture.run().getId(), accepted.runId());
    assertEquals("REVIEW", nextState(fixture.run().getId()));
    assertEquals("DESIGN", issueEditingService.getIssue(fixture.issueId()).getState());
    assertEquals(
        IssueRunStatus.RUNNING, issueRunRepository.getById(fixture.run().getId()).getStatus());
  }

  /** 测试意图：同目标重放是无写操作的幂等结果（版本不推进）；异目标在写前拒绝，不留下第二个交接意图。 */
  @Test
  void replaysSameTargetAndRejectsAConflictingOne() {
    RunFixture fixture = designRun("交接幂等");
    UUID runId = fixture.run().getId();
    UUID assistantEntryId = appendAssistantTurn(fixture.run().getThreadId());
    accept(fixture, assistantEntryId, "REVIEW");
    long acceptedVersion = runVersion(runId);

    IssueTransitionService.IssueTransitionResult replay =
        accept(fixture, assistantEntryId, "REVIEW");

    assertTrue(replay.replayed());
    assertEquals(acceptedVersion, runVersion(runId));

    assertRejected(fixture, assistantEntryId, "DONE", "already accepted");
    assertEquals("REVIEW", nextState(runId));
    assertEquals(acceptedVersion, runVersion(runId));
  }

  /** 测试意图：workflow {@code next} 白名单外的目标（含保留态）被拒绝，且不产生任何写入。 */
  @Test
  void rejectsTargetOutsideTheWorkflowEdges() {
    RunFixture fixture = designRun("非法交接");
    UUID assistantEntryId = appendAssistantTurn(fixture.run().getThreadId());
    long versionBefore = runVersion(fixture.run().getId());

    assertRejected(fixture, assistantEntryId, "DONE", "cannot transition to DONE");

    assertNull(nextState(fixture.run().getId()));
    assertEquals(versionBefore, runVersion(fixture.run().getId()));
  }

  /** 测试意图：没有稳定绑定的 Thread（fork 出来的分支或别的 Session）不能借用交接权限。 */
  @Test
  void rejectsHandoffFromAForkedThread() {
    RunFixture fixture = designRun("fork 拒交接");
    UUID assistantEntryId = appendAssistantTurn(fixture.run().getThreadId());

    assertRejected(UUID.randomUUID(), assistantEntryId, "REVIEW", NOT_AN_AGENT_THREAD);

    assertNull(nextState(fixture.run().getId()));
  }

  /** 测试意图：Run 收尾后不接受交接（旧 Run 的迟到调用不借新一 Run 的权限）。 */
  @Test
  void rejectsHandoffAfterTheRunIsClosedOut() {
    RunFixture fixture = designRun("收尾后交接");
    UUID assistantEntryId = appendAssistantTurn(fixture.run().getThreadId());
    IssueRun completed =
        issueRunService.finishRun(
            fixture.run().getId(),
            fixture.run().getVersion(),
            key("complete"),
            IssueRunStatus.COMPLETED,
            assistantEntryId,
            assistantEntryId,
            null,
            null);
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());

    assertRejected(fixture, assistantEntryId, "REVIEW", NO_ACTIVE_RUN);

    assertNull(nextState(fixture.run().getId()));
  }

  /** 测试意图：Run 起点本身不是一次合法调用位置，assistant 入口必须严格晚于起点。 */
  @Test
  void rejectsAssistantTurnBeforeTheRunStarted() {
    RunFixture fixture = designRun("起点不当调用");

    assertRejected(fixture, fixture.run().getStartEntryId(), "REVIEW", NOT_THE_RUNS_TURN);

    assertNull(nextState(fixture.run().getId()));
  }

  /**
   * 测试意图：同一 Thread 上的旧 assistant 调用迟到落库时，绝不落入该 Thread 后续的 Run —— assistant 入口必须严格晚于活动 Run 的
   * startEntryId，否则按陈旧调用拒绝且不写任何状态。
   */
  @Test
  void rejectsLateAssistantCallOfAnEarlierRunOnTheSameThread() {
    String agent = createAgent();
    UUID projectId = createProjectReusingOneAgent("同 Thread 迟到调用", agent);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");

    IssueRun first = issueRunService.acceptRun(issue.getId(), key("accept-1"));
    UUID threadId = first.getThreadId();
    // 旧 Run 的 assistant turn 先正常登记交接目标；同一 turn 的迟到调用稍后必须被隔离。
    UUID staleAssistantEntryId = appendAssistantTurn(threadId);
    UUID firstFinalEntryId = appendAssistantTurn(threadId);
    assertEquals(
        "REVISE",
        issueTransitionService.accept(threadId, staleAssistantEntryId, "REVISE").toState());
    IssueRun beforeClose = issueRunRepository.getById(first.getId());
    IssueRun handedOff =
        issueRunService.finishRun(
            first.getId(),
            beforeClose.getVersion(),
            key("complete-1"),
            IssueRunStatus.COMPLETED,
            firstFinalEntryId,
            firstFinalEntryId,
            "REVISE",
            null);
    assertEquals(IssueRunStatus.COMPLETED, handedOff.getStatus());
    assertEquals("REVISE", issueEditingService.getIssue(issue.getId()).getState());

    // 同一 Agent 在下一阶段复用同一 Thread：新 Run 从旧 Run 的收尾 head 开始。
    IssueRun second = issueRunService.acceptRun(issue.getId(), key("accept-2"));
    assertEquals(threadId, second.getThreadId());
    assertEquals(headEntry(threadId), second.getStartEntryId());
    long secondVersion = second.getVersion();

    assertRejected(threadId, staleAssistantEntryId, "DONE", NOT_THE_RUNS_TURN);

    assertNull(nextState(second.getId()));
    assertEquals(secondVersion, runVersion(second.getId()));
    assertEquals("REVISE", issueEditingService.getIssue(issue.getId()).getState());

    // 新 Run 自己的 assistant turn 严格晚于新起点，因此是合法调用。
    UUID freshAssistantEntryId = appendAssistantTurn(threadId);
    IssueTransitionService.IssueTransitionResult accepted =
        issueTransitionService.accept(threadId, freshAssistantEntryId, "DONE");
    assertEquals("REVISE", accepted.fromState());
    assertEquals("DONE", accepted.toState());
    assertEquals(second.getId(), accepted.runId());
    assertEquals("DONE", nextState(second.getId()));
  }

  /** 直接收尾留下的迟到 Turn 不得获得新 Run 权限。 */
  @Test
  void rejectsOldTurnMaterializedAfterNewRunStarts() {
    RunFixture fixture = designRun("迟到物化");
    UUID threadId = fixture.run().getThreadId();
    UUID oldTurn = beginHandoffTurn(threadId);
    issueRunService.finishRun(
        fixture.run().getId(),
        fixture.run().getVersion(),
        key("close"),
        IssueRunStatus.CANCELLED,
        headEntry(threadId),
        null,
        null,
        null);
    Issue stopped = issueEditingService.getIssue(fixture.issueId());
    Issue resumed =
        issueLifecycleService.resumeIssue(fixture.issueId(), stopped.getVersion(), key("resume"));
    IssueRun next = issueRunService.acceptRun(resumed.getId(), key("next"));
    assertEquals(threadId, next.getThreadId());
    assertEquals(headEntry(threadId), next.getStartEntryId());

    UUID staleAssistant = finishHandoffTurn(threadId, oldTurn);
    long version = runVersion(next.getId());
    assertRejected(threadId, staleAssistant, "REVIEW", NOT_THE_RUNS_TURN);
    assertNull(nextState(next.getId()));
    assertEquals(version, runVersion(next.getId()));
    assertEquals("DESIGN", issueEditingService.getIssue(fixture.issueId()).getState());

    UUID freshAssistant = appendAssistantTurn(threadId);
    assertEquals(
        "REVIEW", issueTransitionService.accept(threadId, freshAssistant, "REVIEW").toState());
    assertEquals("REVIEW", nextState(next.getId()));
  }

  /**
   * 测试意图：Run 接受与收尾不写入额外命令：一次 Run 初始化仅写入 SET_AGENT / SET_EXECUTION / SET_ENVIRONMENT /
   * CUSTOM_MESSAGE，任务消息本身携带 Issue 事实与阶段说明。
   */
  @Test
  void writesOnlyGeneralCommandsAndRenderedTaskMessage() {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages("任务消息", designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueLifecycleService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    UUID threadId = run.getThreadId();

    assertEquals(
        4L, count("select count(*) from harness_thread_command where thread_id = ?", threadId));
    String message = commandPayload(threadId, "CUSTOM_MESSAGE");
    assertTrue(message.contains(issue.getTitle()), message);
    assertTrue(message.contains("完成可交付方案"), message);
    assertTrue(message.contains("Allowed handoff targets: REVIEW"), message);

    UUID endEntryId = appendHistoryEntry(threadId);
    issueRunService.finishRun(
        run.getId(),
        run.getVersion(),
        key("complete"),
        IssueRunStatus.COMPLETED,
        endEntryId,
        endEntryId,
        null,
        null);

    assertEquals(
        4L, count("select count(*) from harness_thread_command where thread_id = ?", threadId));
  }

  /** 建一个 DESIGN 与 REVISE 共用同一 Agent 的项目，使同一 Issue 的相邻 Run 必然复用同一 Thread。 */
  private UUID createProjectReusingOneAgent(String title, String agent) {
    Project project = projectService.createProject(title, "描述", true);
    projectService.updateWorkflow(
        project.getId(),
        project.getVersion(),
        "{"
            + "\"states\":["
            + "{\"state\":\"INIT\",\"name\":\"待开始\",\"next\":[\"DESIGN\"]},"
            + "{\"state\":\"DESIGN\",\"name\":\"设计\",\"agent\":\""
            + agent
            + "\",\"instructions\":\"完成可交付方案\",\"maxRuns\":3,"
            + "\"next\":[\"REVISE\"]},"
            + "{\"state\":\"REVISE\",\"name\":\"返工\",\"agent\":\""
            + agent
            + "\",\"instructions\":\"按反馈返工\",\"maxRuns\":3,"
            + "\"next\":[\"DONE\"]},"
            + "{\"state\":\"BLOCKED\",\"name\":\"业务阻塞\"},"
            + "{\"state\":\"DONE\",\"name\":\"完成\"}"
            + "]}");
    return project.getId();
  }
}
