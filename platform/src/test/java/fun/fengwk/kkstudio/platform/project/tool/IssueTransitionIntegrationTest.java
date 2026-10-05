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
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;

import java.util.UUID;

/**
 * 交接登记的真实 PostgreSQL 验收：只以 Run 冻结在本 Thread 上的 {@code project/run} 身份为入口，活动 Run 与当前 Issue 阶段
 * 在真实约束下共同决定控制面。
 *
 * <p>测试意图：覆盖交接意图只落 {@code project_issue_run.next_state}（Issue 阶段不变）、同目标幂等重放不推进版本、异目标与非法边在
 * 写前拒绝且无部分写、调用 Thread 必须是 Run 自己的 Thread（fork/旧 Run 被拒绝）、Run 收尾后迟到交接被拒绝。 Harness Session/Thread
 * 行由基座受控假件在同一事务写入，因此断言的是真实行与真实外键。
 */
class IssueTransitionIntegrationTest extends ProjectTestSupport {

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
  }

  /** 建一个 Issue 处于 DESIGN 阶段、已有活动 Run 的场景，返回该 Run。 */
  private RunFixture designRun(String title) {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages(title, designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    return new RunFixture(issue.getId(), run);
  }

  private record RunFixture(UUID issueId, IssueRun run) {}

  /** 以 Run 自己 Thread 上真实冻结的 scope 调用业务工具。 */
  private IssueTransitionService.IssueTransitionResult accept(RunFixture fixture, String toState) {
    UUID threadId = fixture.run().getThreadId();
    return issueTransitionService.accept(
        threadId, ProjectRunScope.SCHEMA_VERSION, frozenRunScopeJson(threadId), toState);
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

    IssueTransitionService.IssueTransitionResult accepted = accept(fixture, "REVIEW");

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
    accept(fixture, "REVIEW");
    long acceptedVersion = runVersion(runId);

    IssueTransitionService.IssueTransitionResult replay = accept(fixture, "REVIEW");

    assertTrue(replay.replayed());
    assertEquals(acceptedVersion, runVersion(runId));

    AiValidationException conflict =
        assertThrows(AiValidationException.class, () -> accept(fixture, "DONE"));
    assertTrue(conflict.getMessage().contains("already accepted"), conflict.getMessage());
    assertEquals("REVIEW", nextState(runId));
    assertEquals(acceptedVersion, runVersion(runId));
  }

  /** 测试意图：workflow {@code next} 白名单外的目标（含保留态）被拒绝，且不产生任何写入。 */
  @Test
  void rejectsTargetOutsideTheWorkflowEdges() {
    RunFixture fixture = designRun("非法交接");

    AiValidationException rejected =
        assertThrows(AiValidationException.class, () -> accept(fixture, "DONE"));

    assertTrue(rejected.getMessage().contains("cannot transition to DONE"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
  }

  /** 测试意图：继承来的 scope 不能在别的 Thread 上使用——fork 出来的分支即使持有同一 scope 也被拒绝。 */
  @Test
  void rejectsHandoffFromAForkedThread() {
    RunFixture fixture = designRun("fork 拒交接");
    UUID runThreadId = fixture.run().getThreadId();

    AiValidationException rejected =
        assertThrows(
            AiValidationException.class,
            () ->
                issueTransitionService.accept(
                    UUID.randomUUID(),
                    ProjectRunScope.SCHEMA_VERSION,
                    frozenRunScopeJson(runThreadId),
                    "REVIEW"));

    assertTrue(rejected.getMessage().contains("run's own thread"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
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
        assertThrows(AiValidationException.class, () -> accept(fixture, "REVIEW"));

    assertTrue(rejected.getMessage().contains("no active run"), rejected.getMessage());
    assertNull(nextState(fixture.run().getId()));
  }
}
