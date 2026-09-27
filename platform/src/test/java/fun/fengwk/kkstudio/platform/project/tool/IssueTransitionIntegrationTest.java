package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.ProjectTestSupport;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;

import java.util.List;
import java.util.UUID;

/**
 * 交接登记与 Turn 事实解析的真实 PostgreSQL 验收：稳定 Thread 绑定、活动 Run 与队列阶段在真实约束下共同决定控制面。
 *
 * <p>测试意图：覆盖交接意图只落 {@code project_issue_run.next_state}（Issue 阶段不变）、同目标幂等重放不推进版本、异目标与非法边在写前
 * 拒绝且无部分写、归属按 Thread 反查（跨 Agent/跨 Session/未绑定 Thread 的确定性结论）、Run 收尾后与暂停门禁下的迟到交接被拒绝。 Harness
 * Session/Thread 行由基座受控假件在同一事务写入，因此断言的是真实行与真实外键。
 */
class IssueTransitionIntegrationTest extends ProjectTestSupport {

  @Autowired private IssueTransitionService issueTransitionService;
  @Autowired private ProjectIssueTurnResolver projectIssueTurnResolver;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private ApplicationContext applicationContext;

  /** 测试意图：生产组合根里受控组件各只有一个 Bean、事务边界真的生效（否则锁序与 CAS 都失去意义），且工具只以 INTERNAL 可见性发布 （Agent 无法自行选择）。 */
  @Test
  void composesExactlyOneControlledDefinition() {
    assertEquals(1, applicationContext.getBeanNamesForType(ProjectIssueTurnResolver.class).length);
    assertEquals(1, applicationContext.getBeanNamesForType(IssueTransitionService.class).length);
    assertEquals(1, applicationContext.getBeanNamesForType(IssueTransitionTool.class).length);
    assertTrue(AopUtils.isAopProxy(projectIssueTurnResolver), "只读事务代理必须生效");
    assertTrue(AopUtils.isAopProxy(issueTransitionService), "交接事务代理必须生效");

    ToolContribution tool = harnessCatalog.findTool(IssueTransitionTool.NAME).orElseThrow();
    assertEquals("project", tool.id().contributorId().value());
    assertEquals(ToolVisibility.INTERNAL, tool.definition().visibility());
    assertTrue(
        harnessCatalog.selectableTools().stream()
            .noneMatch(candidate -> candidate.id().equals(tool.id())));
  }

  /** 建一个 Issue 处于 DESIGN 阶段、已有活动 Run 的场景，返回该 Run 与设计 Agent 名称。 */
  private RunFixture designRun(String title) {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages(title, designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    return new RunFixture(issue.getId(), designAgent, reviewAgent, run);
  }

  private record RunFixture(UUID issueId, String designAgent, String reviewAgent, IssueRun run) {}

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

    IssueTransitionService.IssueTransitionResult accepted =
        issueTransitionService.accept(fixture.run().getThreadId(), "REVIEW");

    assertFalse(accepted.replayed());
    assertEquals("DESIGN", accepted.fromState());
    assertEquals("REVIEW", accepted.toState());
    assertEquals(fixture.run().getId(), accepted.runId());
    assertEquals("REVIEW", nextState(fixture.run().getId()));
    assertEquals("DESIGN", issueService.getIssue(fixture.issueId()).getState());
    assertEquals(IssueRunStatus.RUNNING, issueRunService.getRun(fixture.run().getId()).getStatus());
  }

  /** 测试意图：同目标重放是无写操作的幂等结果（版本不推进）；异目标在写前拒绝，不留下第二个交接意图。 */
  @Test
  void replaysSameTargetAndRejectsAConflictingOne() {
    RunFixture fixture = designRun("交接幂等");
    UUID runId = fixture.run().getId();
    issueTransitionService.accept(fixture.run().getThreadId(), "REVIEW");
    long acceptedVersion = runVersion(runId);

    IssueTransitionService.IssueTransitionResult replay =
        issueTransitionService.accept(fixture.run().getThreadId(), "REVIEW");

    assertTrue(replay.replayed());
    assertEquals(acceptedVersion, runVersion(runId));

    AiValidationException conflict =
        assertThrows(
            AiValidationException.class,
            () -> issueTransitionService.accept(fixture.run().getThreadId(), "DONE"));
    assertTrue(conflict.getMessage().contains("already accepted"), conflict.getMessage());
    assertEquals("REVIEW", nextState(runId));
    assertEquals(acceptedVersion, runVersion(runId));
  }

  /** 测试意图：workflow {@code next} 白名单外的目标（含保留态）被拒绝，且不产生任何写入。 */
  @Test
  void rejectsTargetOutsideTheWorkflowEdges() {
    RunFixture fixture = designRun("非法交接");

    AiValidationException rejected =
        assertThrows(
            AiValidationException.class,
            () -> issueTransitionService.accept(fixture.run().getThreadId(), "DONE"));

    assertTrue(rejected.getMessage().contains("cannot transition to DONE"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
  }

  /** 测试意图：Turn 事实按 Harness Thread 反查稳定归属；跨 Agent、跨 Session 与未绑定 Thread 都有确定性结论。 */
  @Test
  void resolvesTurnFactsByStableThreadBinding() {
    RunFixture fixture = designRun("Turn 事实");

    ProjectIssueTurnFacts facts =
        projectIssueTurnResolver
            .resolve(
                fixture.run().getThreadId(), fixture.designAgent(), fixture.run().getSessionId())
            .orElseThrow();

    assertEquals(fixture.issueId(), facts.issueId());
    assertEquals(fixture.run().getId(), facts.runId());
    assertEquals("DESIGN", facts.stage());
    assertEquals("设计", facts.stageName());
    assertEquals("完成可交付方案", facts.stageInstructions());
    assertEquals(List.of("REVIEW"), facts.nextStates());
    assertNull(facts.environmentName());
    assertEquals(fixture.designAgent(), facts.agentName());

    // 同一 Thread 不能被另一个 Agent 借用。
    assertThrows(
        ProjectIssueTurnRejection.class,
        () ->
            projectIssueTurnResolver.resolve(
                fixture.run().getThreadId(), fixture.reviewAgent(), fixture.run().getSessionId()));
    // 同一 Thread 也不能被另一个 Session 上下文解析。
    assertThrows(
        ProjectIssueTurnRejection.class,
        () ->
            projectIssueTurnResolver.resolve(
                fixture.run().getThreadId(), fixture.designAgent(), UUID.randomUUID()));
    // 未绑定归属的 Thread 是普通 branch。
    assertTrue(
        projectIssueTurnResolver
            .resolve(UUID.randomUUID(), fixture.designAgent(), fixture.run().getSessionId())
            .isEmpty());
  }

  /** 测试意图：Run 收尾后不再接受交接（旧 Run 的迟到调用不借新一 Run 的权限）。 */
  @Test
  void rejectsHandoffAfterTheRunIsClosedOut() {
    RunFixture fixture = designRun("收尾后交接");
    UUID endEntryId = appendHistoryEntry(fixture.run().getThreadId());
    IssueRun completed =
        issueRunService.completeRun(
            fixture.run().getId(),
            fixture.run().getVersion(),
            key("complete"),
            endEntryId,
            null,
            null);
    assertEquals(IssueRunStatus.COMPLETED, completed.getStatus());

    AiValidationException rejected =
        assertThrows(
            AiValidationException.class,
            () -> issueTransitionService.accept(fixture.run().getThreadId(), "REVIEW"));

    assertTrue(rejected.getMessage().contains("no active run"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
  }

  /** 测试意图：控制暂停门禁关闭时不接受新的交接意图，暂停期间已登记的目标由收尾路径保留。 */
  @Test
  void rejectsHandoffWhileThePauseGateIsClosed() {
    RunFixture fixture = designRun("暂停交接");
    Issue current = issueService.getIssue(fixture.issueId());
    issueService.pauseIssue(
        fixture.issueId(), current.getVersion(), key("pause"), PauseReason.USER, "human stop");

    AiValidationException rejected =
        assertThrows(
            AiValidationException.class,
            () -> issueTransitionService.accept(fixture.run().getThreadId(), "REVIEW"));

    assertTrue(rejected.getMessage().contains("paused"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
  }
}
