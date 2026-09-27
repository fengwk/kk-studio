package fun.fengwk.kkstudio.platform.harness.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.port.WorkDispatchRequest;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.project.ProjectTestSupport;
import fun.fengwk.kkstudio.platform.project.tool.IssueTransitionService;
import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.PauseReason;
import fun.fengwk.kkstudio.project.repo.IssueRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 派发门禁的真实 PostgreSQL 线性化验收：宿主行锁与 Harness 的 READY -&gt; DISPATCHING 意图共享同一个物理事务。
 *
 * <p>测试意图：产品语义的强一致来自「暂停」与「首次对外派发」争用同一把 Issue 行锁。这里用两个真实连接与 latch 把两种交错都钉死：
 *
 * <ol>
 *   <li>暂停先取得 Issue 行锁：派发事务在取锁处等待，暂停提交后派发重读到已提交的暂停事实并被拒绝，意图一次都没有执行（无 TOCTOU）；
 *   <li>派发先提交：暂停只能在派发完成后推进，已提交的对外执行不被回滚，随后新的派发才被拒绝（在途执行仍能收尾）；
 *   <li>liveness：两种交错都在有限时间内完成，不出现反向锁序导致的死锁。
 * </ol>
 *
 * <p>同一物理事务的判定不是结构假说而是实测：调用方的 {@code REQUIRED} 事务边界与生产 {@code PostgresqlHarnessStore} 完全一致（同一
 * {@code PlatformTransactionManager} 的 {@code
 * TransactionTemplate}），因此断言「意图内读到的事务号与调用方相同」「外层回滚时意图的写入一并回滚」即证明宿主锁、产品判定与 Harness 状态转换共用一个连接、
 * 一次提交；外部调用（Provider / Tool Gateway）只在提交之后发生。
 *
 * <p>其余判定矩阵同样在真实行上验证：收尾阶段（活动 Run 已登记 {@code nextState}）只放行模型与只读工具、拒绝业务写工具；等待态 Run 恢复不消耗新的阶段额度；未绑定
 * Thread（Chat / 内部委派）放行且不读写任何产品事实。
 */
class IssueAgentWorkDispatchAdmissionPostgresIntegrationTest extends ProjectTestSupport {

  private static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration BLOCKED_PROBE = Duration.ofSeconds(1);

  @Autowired private IssueAgentWorkDispatchAdmission admission;
  @Autowired private IssueRepository issueRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private IssueTransitionService issueTransitionService;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdownExecutor() {
    executor.shutdownNow();
  }

  /** 一个已有 DESIGN 活动 Run 的 Issue：Run 的 Thread/Session 才是可派发坐标。 */
  private record Fixture(
      UUID issueId, UUID runId, UUID threadId, UUID sessionId, long issueVersion) {

    WorkDispatchRequest modelRequest() {
      return new WorkDispatchRequest(
          WorkTargetType.MODEL, UUID.randomUUID(), threadId, sessionId, null);
    }

    WorkDispatchRequest toolRequest(ToolSideEffect sideEffect) {
      return new WorkDispatchRequest(
          WorkTargetType.TOOL, UUID.randomUUID(), threadId, sessionId, binding(sideEffect));
    }
  }

  private Fixture designRun(String title) {
    String designAgent = createAgent();
    String reviewAgent = createAgent();
    UUID projectId = createProjectWithStages(title, designAgent, reviewAgent, 3);
    Issue issue = createIssue(projectId);
    issueService.transition(issue.getId(), issue.getVersion(), key("start"), "DESIGN");
    IssueRun run = issueRunService.acceptRun(issue.getId(), key("accept"));
    return new Fixture(
        issue.getId(),
        run.getId(),
        run.getThreadId(),
        run.getSessionId(),
        issueService.getIssue(issue.getId()).getVersion());
  }

  private static ToolBinding binding(ToolSideEffect sideEffect) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "run a command",
            "bash",
            new InputSchema("arguments", Map.of(), Set.of(), false),
            sideEffect,
            Duration.ofSeconds(30));
    return new ToolBinding(
        new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
        new ContributorBinding("test", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private long currentTransactionId() {
    return Long.parseLong(jdbc.queryForObject("select pg_current_xact_id()::text", String.class));
  }

  /** 在独立事务边界内询问宿主一次，返回是否放行，并记录意图是否真的执行。 */
  private boolean admitted(WorkDispatchRequest request, AtomicInteger intents) {
    Optional<Boolean> outcome =
        transaction()
            .execute(
                status ->
                    admission.executeIfAdmitted(
                        request,
                        () -> {
                          intents.incrementAndGet();
                          return Boolean.TRUE;
                        }));
    return outcome.orElse(Boolean.FALSE);
  }

  /** 探测一个任务在给定窗口内是否仍未完成（用于断言它正阻塞在行锁上）。 */
  private static boolean stillBlocked(Future<?> future, Duration window) throws Exception {
    try {
      future.get(window.toMillis(), TimeUnit.MILLISECONDS);
      return false;
    } catch (TimeoutException expected) {
      return true;
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("latch not released in time");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Test
  void pauseHoldingTheIssueLockFirstBlocksTheReadyDispatchAndThenDeniesIt() throws Exception {
    Fixture fixture = designRun("暂停先取得 Issue 锁");
    CountDownLatch pauseHoldsIssueLock = new CountDownLatch(1);
    CountDownLatch releasePause = new CountDownLatch(1);
    CountDownLatch dispatchEnteredTransaction = new CountDownLatch(1);
    AtomicInteger intents = new AtomicInteger();

    // 暂停事务先取 Issue FOR UPDATE 并持有，直到测试显式放行才写入暂停事实并提交。
    Future<?> pause =
        executor.submit(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          Issue locked = issueRepository.lockById(fixture.issueId());
                          assertNotNull(locked);
                          pauseHoldsIssueLock.countDown();
                          awaitQuietly(releasePause);
                          issueService.pauseIssue(
                              fixture.issueId(),
                              locked.getVersion(),
                              key("pause"),
                              PauseReason.USER,
                              "人工暂停");
                        }));
    assertTrue(pauseHoldsIssueLock.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

    Future<Optional<Boolean>> dispatch =
        executor.submit(
            () ->
                transaction()
                    .execute(
                        status -> {
                          dispatchEnteredTransaction.countDown();
                          return admission.executeIfAdmitted(
                              fixture.modelRequest(),
                              () -> {
                                intents.incrementAndGet();
                                return Boolean.TRUE;
                              });
                        }));
    assertTrue(
        dispatchEnteredTransaction.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

    // 派发事务已进入，但必须阻塞在暂停持有的 Issue 行锁上：暂停未提交前绝不能先读出「未暂停」并开始新的对外执行。
    assertTrue(stillBlocked(dispatch, BLOCKED_PROBE));
    assertEquals(0, intents.get());

    releasePause.countDown();
    pause.get(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    // 暂停提交后派发拿到锁并重读：提交后的暂停事实可见，因此拒绝，且意图一次都没有执行。
    assertEquals(
        Optional.empty(), dispatch.get(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    assertEquals(0, intents.get());
    assertTrue(issueService.getIssue(fixture.issueId()).isPaused());
  }

  @Test
  void dispatchCommittingFirstKeepsTheInFlightExecutionAndOnlyLaterPauseDeniesNewDispatch()
      throws Exception {
    Fixture fixture = designRun("派发先提交");
    CountDownLatch dispatchHoldsIssueLock = new CountDownLatch(1);
    CountDownLatch releaseDispatch = new CountDownLatch(1);
    CountDownLatch pauseEnteredTransaction = new CountDownLatch(1);
    AtomicInteger intents = new AtomicInteger();

    // 派发事务在宿主判定通过后持有 Issue 行锁并写入意图（模拟 READY -> DISPATCHING 的持久事实），直到测试放行才提交。
    Future<Optional<Boolean>> dispatch =
        executor.submit(
            () ->
                transaction()
                    .execute(
                        status -> {
                          long callerTransaction = currentTransactionId();
                          return admission.executeIfAdmitted(
                              fixture.modelRequest(),
                              () -> {
                                // 同一物理事务：意图里看到的事务号与调用方（Harness Store 边界）完全一致。
                                assertEquals(callerTransaction, currentTransactionId());
                                jdbc.update(
                                    "update harness_thread set version = version + 1 where id = ?",
                                    fixture.threadId());
                                dispatchHoldsIssueLock.countDown();
                                awaitQuietly(releaseDispatch);
                                return Boolean.TRUE;
                              });
                        }));
    assertTrue(dispatchHoldsIssueLock.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

    Future<?> pause =
        executor.submit(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          pauseEnteredTransaction.countDown();
                          issueService.pauseIssue(
                              fixture.issueId(),
                              fixture.issueVersion(),
                              key("pause"),
                              PauseReason.USER,
                              "人工暂停");
                        }));
    assertTrue(
        pauseEnteredTransaction.await(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

    // 暂停必须等待已提交的派发：它阻塞在同一个 Issue 行锁上，不可能抢先取消一次已经开始的对外执行。
    assertTrue(stillBlocked(pause, BLOCKED_PROBE));

    releaseDispatch.countDown();
    assertEquals(
        Optional.of(Boolean.TRUE),
        dispatch.get(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
    // liveness：派发提交后暂停立即推进，没有反向锁序导致的死锁。
    pause.get(CONCURRENCY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertTrue(issueService.getIssue(fixture.issueId()).isPaused());
    // 先提交的派发事实保留：意图的写入随调用方事务提交。
    assertEquals(1L, count("select version from harness_thread where id = ?", fixture.threadId()));

    // 暂停生效后，同一坐标的新派发被拒绝且不执行意图；在途执行不受影响（本门禁只被首次派发询问）。
    assertFalse(admitted(fixture.modelRequest(), intents));
    assertEquals(0, intents.get());
  }

  @Test
  void handoffToNextStateAllowsModelAndReadOnlyToolsButDeniesBusinessWrites() {
    Fixture fixture = designRun("收尾阶段");
    issueTransitionService.accept(fixture.threadId(), "REVIEW");
    assertEquals(
        "REVIEW",
        jdbc.queryForObject(
            "select next_state from project_issue_run where id = ?",
            String.class,
            fixture.runId()));
    AtomicInteger intents = new AtomicInteger();

    assertTrue(admitted(fixture.modelRequest(), intents));
    assertTrue(admitted(fixture.toolRequest(ToolSideEffect.READ_ONLY), intents));
    assertFalse(admitted(fixture.toolRequest(ToolSideEffect.IDEMPOTENT), intents));
    assertFalse(admitted(fixture.toolRequest(ToolSideEffect.NON_IDEMPOTENT), intents));
    assertEquals(2, intents.get());
  }

  @Test
  void waitingRunResumeIsAdmittedWithoutConsumingAnotherStageBudgetSlot() {
    Fixture fixture = designRun("等待恢复");
    String budget =
        "select budget_after_ordinal from project_issue_stage_budget"
            + " where issue_id = ? and state = 'DESIGN'";
    long budgetBefore = count(budget, fixture.issueId());
    AtomicInteger intents = new AtomicInteger();

    IssueRun waiting = issueRunService.waitRun(fixture.runId(), runVersion(fixture.runId()));
    assertEquals(IssueRunStatus.WAITING, waiting.getStatus());
    // 等待态 Run 仍是活动 Run：恢复前记录回答/审批的那次派发仍按同一坐标判定（这里只验证门禁不因 WAITING 而改变归属判定）。
    assertTrue(admitted(fixture.modelRequest(), intents));

    IssueRun resumed = issueRunService.resumeRun(fixture.runId(), waiting.getVersion());
    assertEquals(IssueRunStatus.RUNNING, resumed.getStatus());
    assertTrue(admitted(fixture.modelRequest(), intents));

    // 恢复不新建 Run、不消耗新的阶段额度：门禁只是只读判定，绝不写产品事实。
    assertEquals(2, intents.get());
    assertEquals(budgetBefore, count(budget, fixture.issueId()));
    assertEquals(
        1L, count("select count(*) from project_issue_run where issue_id = ?", fixture.issueId()));
    assertEquals(
        1L, count("select ordinal from project_issue_run where issue_id = ?", fixture.issueId()));
  }

  @Test
  void threadsWithoutProductBindingAreAdmittedWithoutTouchingProductFacts() {
    Fixture fixture = designRun("未绑定 Thread");
    issueService.pauseIssue(
        fixture.issueId(), fixture.issueVersion(), key("pause"), PauseReason.USER, "人工暂停");
    // 已暂停的 Issue 不影响没有产品绑定的 Thread：Chat 与内部委派没有产品暂停语义。
    UUID chatThreadId = UUID.randomUUID();
    AtomicInteger intents = new AtomicInteger();

    assertTrue(
        admitted(
            new WorkDispatchRequest(
                WorkTargetType.MODEL, UUID.randomUUID(), chatThreadId, UUID.randomUUID(), null),
            intents));
    assertTrue(
        admitted(
            new WorkDispatchRequest(
                WorkTargetType.THREAD, UUID.randomUUID(), chatThreadId, UUID.randomUUID(), null),
            intents));
    assertEquals(2, intents.get());
    assertEquals(
        0L,
        count("select count(*) from project_issue_agent_thread where thread_id = ?", chatThreadId));
  }

  @Test
  void admissionAndIntentShareOnePhysicalTransactionSoIntentRollsBackWithTheCaller() {
    Fixture fixture = designRun("同一物理事务");
    Boolean admitted =
        transaction()
            .execute(
                status -> {
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  long callerTransaction = currentTransactionId();
                  Optional<Boolean> outcome =
                      admission.executeIfAdmitted(
                          fixture.modelRequest(),
                          () -> {
                            // 同一物理事务：宿主行锁、产品判定与 Harness 意图读到的都是调用方（Harness Store）的连接。
                            assertEquals(callerTransaction, currentTransactionId());
                            jdbc.update(
                                "update harness_thread set version = version + 1 where id = ?",
                                fixture.threadId());
                            return Boolean.TRUE;
                          });
                  assertEquals(Optional.of(Boolean.TRUE), outcome);
                  // Harness Store 回滚：意图必须一并回滚，绝不单独提交。
                  status.setRollbackOnly();
                  return Boolean.TRUE;
                });
    assertEquals(Boolean.TRUE, admitted);
    assertEquals(0L, count("select version from harness_thread where id = ?", fixture.threadId()));
  }

  private long runVersion(UUID runId) {
    Long version =
        jdbc.queryForObject(
            "select version from project_issue_run where id = ?", Long.class, runId);
    return version == null ? 0L : version;
  }
}
