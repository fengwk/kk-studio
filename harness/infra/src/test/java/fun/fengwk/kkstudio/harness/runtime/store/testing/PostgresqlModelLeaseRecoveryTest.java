package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.ModelGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真实 PostgreSQL 上的 Model 租约失联恢复验收：跨实例沿用同一 durable attempt/retryAt 预算，下一个实例只能在到期时 claim，预算耗尽前
 * 不得重置计数、不得重放 Provider，耗尽后如实 FAILED；已提交结果在重启后复用、不重复写 Entry。
 *
 * <p>数据库时钟是 claim / due / expiry 的唯一权威：测试只改写持久化行的 {@code available_at} / {@code lease_until}
 * 来跨越到期边界，不依赖 JVM sleep。
 */
class PostgresqlModelLeaseRecoveryTest {

  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration RETRY_DELAY = Duration.ofMinutes(5);

  private HarnessStore store;
  private JdbcTemplate jdbc;
  private ScheduledExecutorService scheduler;
  private CountingModelGateway modelGateway;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    jdbc = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    scheduler = Executors.newSingleThreadScheduledExecutor();
    modelGateway = new CountingModelGateway();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  /**
   * RUNNING 尝试的租约失联由实例 A 恢复为 READY 并保留 durable attempt/retryAt；到期前任何实例都不可 claim；实例 B 在预算耗尽时
   * FAILED，且 failedAttempts 前缀与 attempt 计数不被重置。
   */
  @Test
  void expiredRunningModelRecoveryKeepsDurableBudgetUntilInstanceExhaustsIt() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID modelId = seedRunningModel(turn);
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, modelId);
    WorkTarget threadTarget = new WorkTarget(WorkTargetType.THREAD, turn.threadId());
    requestWork(modelTarget, turn.threadId());

    InvocationRetryPolicy policy = retryOnce();
    ModelProcessor instanceA = newModelProcessor(policy);
    ClaimedWork claimA = claimModel("instance-a").orElseThrow();
    assertEquals(ProcessResult.RESCHEDULED, instanceA.process(claimA));

    // 预算未被重置：RUNNING 的已确认启动记为一次已消耗 attempt，持久 attempt 保持 1，retryAt 由策略 delay 推导。
    ModelInvocation afterA = findModel(modelId);
    assertEquals(ModelInvocationStatus.READY, afterA.status());
    assertEquals(1, afterA.attempt());
    assertEquals(1, afterA.failedAttempts().size());
    ModelAttemptFailure firstFailure = afterA.failedAttempts().getFirst();
    assertEquals(1, firstFailure.attempt());
    assertEquals(firstFailure.failedAt().plus(RETRY_DELAY), firstFailure.retryAt());
    assertEquals(0, modelGateway.startCalls(), "recovery must never replay the Provider");

    // durable retryAt 之前到期边界不满足：reschedule 已释放租约且 available_at 仍在未来。
    Work rescheduled = findWork(modelTarget).orElseThrow();
    assertNull(rescheduled.leaseToken());
    assertTrue(claimModel("instance-a-again").isEmpty(), "not due before retryAt");
    assertEquals(1, findModel(modelId).attempt(), "budget must not change between dispatches");

    // 到期后由另一实例 claim：与实例 A 无内存共享，只能读同一 durable 预算。
    forceDue(modelTarget);
    ClaimedWork claimB = claimModel("instance-b").orElseThrow();
    assertEquals(modelId, claimB.target().id());
    beginDispatchAndRun(turn, modelId);
    ModelInvocation running = findModel(modelId);
    assertEquals(ModelInvocationStatus.RUNNING, running.status());
    assertEquals(2, running.attempt());
    assertEquals(
        1, running.failedAttempts().size(), "durable budget survived the instance handoff");

    // 实例 B 的本地执行同样失联；预算已到上限，恢复必须如实 FAILED 而不是再次重试。
    forceLeaseExpired(modelTarget);
    ClaimedWork claimC = claimModel("instance-c").orElseThrow();
    ModelProcessor instanceB = newModelProcessor(policy);
    assertEquals(ProcessResult.TERMINATED, instanceB.process(claimC));

    ModelInvocation failed = findModel(modelId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(2, failed.attempt());
    assertEquals(
        1, failed.failedAttempts().size(), "exhaustion must not re-charge nor reset attempts");
    assertEquals(ProviderErrorKind.TRANSIENT, failed.error().kind());
    assertEquals(0, modelGateway.startCalls(), "recovery must never replay the Provider");
    assertTrue(findWork(modelTarget).isEmpty(), "MODEL work completes on terminal recovery");
    assertTrue(findWork(threadTarget).isPresent(), "exhausted recovery wakes THREAD");
  }

  /** DISPATCHING 的未确认启动只被计费一次（{@code attempt + 1}），不会重复计费；同样跨实例共享 durable 预算并在耗尽时 FAILED。 */
  @Test
  void expiredDispatchingModelRecoveryChargesUnconfirmedAttemptExactlyOnce() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID modelId = seedDispatchingModel(turn);
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, modelId);
    requestWork(modelTarget, turn.threadId());

    InvocationRetryPolicy policy = retryOnce();
    ModelProcessor instanceA = newModelProcessor(policy);
    assertEquals(ProcessResult.RESCHEDULED, instanceA.process(claimModel("a").orElseThrow()));

    ModelInvocation afterA = findModel(modelId);
    assertEquals(ModelInvocationStatus.READY, afterA.status());
    assertEquals(1, afterA.attempt(), "the unconfirmed dispatch is charged exactly once");
    assertEquals(1, afterA.failedAttempts().size());
    ModelAttemptFailure firstFailure = afterA.failedAttempts().getFirst();
    assertEquals(1, firstFailure.attempt());
    assertEquals(firstFailure.failedAt().plus(RETRY_DELAY), firstFailure.retryAt());
    assertEquals(0, modelGateway.startCalls());

    assertTrue(claimModel("a-again").isEmpty(), "not due before retryAt");
    forceDue(modelTarget);
    claimModel("b").orElseThrow();
    beginDispatchAndRun(turn, modelId);
    assertEquals(2, findModel(modelId).attempt());

    forceLeaseExpired(modelTarget);
    ModelProcessor instanceB = newModelProcessor(policy);
    assertEquals(ProcessResult.TERMINATED, instanceB.process(claimModel("c").orElseThrow()));

    ModelInvocation failed = findModel(modelId);
    assertEquals(ModelInvocationStatus.FAILED, failed.status());
    assertEquals(2, failed.attempt());
    assertEquals(1, failed.failedAttempts().size());
    assertTrue(findWork(new WorkTarget(WorkTargetType.THREAD, turn.threadId())).isPresent());
    assertEquals(0, modelGateway.startCalls());
  }

  /** 已提交（SUCCEEDED + resultEntryId 已链接）的模型结果在重启 claim 时被直接复用：不重放 Provider、不重复写 Entry。 */
  @Test
  void committedModelResultIsReusedOnRestartWithoutDuplicateEntry() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID modelId = seedSucceededMaterializedModel(turn);
    WorkTarget modelTarget = new WorkTarget(WorkTargetType.MODEL, modelId);
    requestWork(modelTarget, turn.threadId());

    UUID headBefore =
        store.transaction(tx -> tx.findThread(turn.threadId()).orElseThrow().headEntryId());
    EntryPath before = loadPath(headBefore);
    long entriesBefore = before.entries().size();

    ModelProcessor instance = newModelProcessor(retryOnce());
    assertEquals(ProcessResult.TERMINATED, instance.process(claimModel("restart").orElseThrow()));

    ModelInvocation reused = findModel(modelId);
    assertEquals(ModelInvocationStatus.SUCCEEDED, reused.status());
    assertNotNull(reused.resultEntryId());
    assertEquals(
        entriesBefore, loadPath(headBefore).entries().size(), "restart must not append entries");
    assertEquals(
        headBefore,
        store.transaction(tx -> tx.findThread(turn.threadId()).orElseThrow().headEntryId()));
    assertEquals(0, modelGateway.startCalls(), "committed result must not re-run the Provider");
    assertTrue(findWork(modelTarget).isEmpty());
    assertTrue(findWork(new WorkTarget(WorkTargetType.THREAD, turn.threadId())).isEmpty());
  }

  private InvocationRetryPolicy retryOnce() {
    return new InvocationRetryPolicy(
        1, InvocationRetryBackoffStrategy.FIXED, RETRY_DELAY, RETRY_DELAY);
  }

  private ModelProcessor newModelProcessor(InvocationRetryPolicy policy) {
    return new ModelProcessor(
        store,
        modelGateway,
        event -> {},
        new ModelProcessorConfig(
            new ProcessorLeaseConfig(LEASE, Duration.ofSeconds(5)),
            () -> policy,
            Duration.ofSeconds(5)),
        Clock.systemUTC(),
        scheduler,
        Runnable::run,
        Runnable::run);
  }

  /** 在开放 TURN_START 之后追加 USER Entry 并推进 Thread head。 */
  private UUID seedUserEntry(StoreTestSupport.TurnBaseline turn) {
    return store.transaction(
        tx -> {
          var thread = tx.lockThread(turn.threadId()).orElseThrow();
          UUID id = tx.nextId();
          tx.insertEntry(
              new Entry(
                  id,
                  turn.sessionId(),
                  turn.turnStartEntryId(),
                  StoreTestSupport.userMessagePayload(),
                  T2));
          tx.updateThread(thread.advanceHead(id, T2));
          return id;
        });
  }

  private UUID seedReadyModel(StoreTestSupport.TurnBaseline turn, UUID requestHeadEntryId) {
    return store.transaction(
        tx -> {
          tx.lockThread(turn.threadId()).orElseThrow();
          UUID id = tx.nextId();
          tx.insertModelInvocation(
              new ModelInvocation(
                  id,
                  turn.threadId(),
                  turn.turnStartEntryId(),
                  requestHeadEntryId,
                  StoreTestSupport.modelRequest(),
                  ModelInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  null,
                  List.of(),
                  T2,
                  T2));
          return id;
        });
  }

  private UUID seedRunningModel(StoreTestSupport.TurnBaseline turn) {
    UUID modelId = seedReadyModel(turn, seedUserEntry(turn));
    store.transaction(
        tx -> {
          tx.lockThread(turn.threadId()).orElseThrow();
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().beginDispatch(T3));
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().markRunning(T3));
          return null;
        });
    return modelId;
  }

  private UUID seedDispatchingModel(StoreTestSupport.TurnBaseline turn) {
    UUID modelId = seedReadyModel(turn, seedUserEntry(turn));
    store.transaction(
        tx -> {
          tx.lockThread(turn.threadId()).orElseThrow();
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().beginDispatch(T3));
          return null;
        });
    return modelId;
  }

  /** SUCCEEDED + resultEntryId 已链接的终态模型，其 assistant Entry 已物化进历史。 */
  private UUID seedSucceededMaterializedModel(StoreTestSupport.TurnBaseline turn) {
    UUID modelId = seedReadyModel(turn, seedUserEntry(turn));
    store.transaction(
        tx -> {
          var thread = tx.lockThread(turn.threadId()).orElseThrow();
          var response = StoreTestSupport.assistantResponse();
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().beginDispatch(T3));
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().markRunning(T3));
          tx.updateModelInvocation(
              tx.lockModelInvocation(modelId).orElseThrow().succeed(response, null, null, T3));
          UUID assistantId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  assistantId,
                  turn.sessionId(),
                  thread.headEntryId(),
                  StoreTestSupport.mappedAssistant(StoreTestSupport.modelRequest(), response),
                  T3));
          tx.updateModelInvocation(
              tx.lockModelInvocation(modelId).orElseThrow().attachResultEntry(assistantId, T3));
          tx.updateThread(thread.advanceHead(assistantId, T3));
          return null;
        });
    return modelId;
  }

  /** READY -&gt; DISPATCHING -&gt; RUNNING(attempt + 1)，模拟恢复后实例真正发起的下一次尝试。 */
  private void beginDispatchAndRun(StoreTestSupport.TurnBaseline turn, UUID modelId) {
    store.transaction(
        tx -> {
          tx.lockThread(turn.threadId()).orElseThrow();
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().beginDispatch(T4));
          tx.updateModelInvocation(tx.lockModelInvocation(modelId).orElseThrow().markRunning(T4));
          return null;
        });
  }

  private void requestWork(WorkTarget target, UUID ownerThreadId) {
    store.transaction(
        tx -> {
          tx.lockThread(ownerThreadId).orElseThrow();
          tx.requestWork(target, Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(1));
          return null;
        });
  }

  private Optional<ClaimedWork> claimModel(String token) {
    return store.transaction(
        tx ->
            tx.claimNextWork(
                WorkTargetType.MODEL, Instant.now().truncatedTo(ChronoUnit.MILLIS), token, LEASE));
  }

  private ModelInvocation findModel(UUID modelId) {
    return store.transaction(tx -> tx.findModelInvocation(modelId)).orElseThrow();
  }

  private Optional<Work> findWork(WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target));
  }

  private EntryPath loadPath(UUID headEntryId) {
    return store.transaction(tx -> tx.loadEntryPath(headEntryId));
  }

  /** 直接改写持久化 available_at，跨越数据库时钟域的到期边界（无 JVM sleep）。 */
  private void forceDue(WorkTarget target) {
    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set available_at = statement_timestamp() - interval '1 second'
            where target_type = ? and target_id = ?
            """,
            target.type().name(),
            target.id()));
  }

  /** 直接改写持久化 lease_until，令另一实例可 claim 一个仍被并发持有的租约。 */
  private void forceLeaseExpired(WorkTarget target) {
    assertEquals(
        1,
        jdbc.update(
            """
            update harness_work
            set lease_until = statement_timestamp() - interval '1 second'
            where target_type = ? and target_id = ?
            """,
            target.type().name(),
            target.id()));
  }

  /** 恢复路径唯一允许出现的 Gateway 交互是 0 次：任何 replay 都会让计数断言失败。 */
  private static final class CountingModelGateway implements ModelGateway {

    private final AtomicInteger startCalls = new AtomicInteger();

    private int startCalls() {
      return startCalls.get();
    }

    @Override
    public StartResult start(Execution execution, Listener listener) {
      startCalls.incrementAndGet();
      return new ModelGateway.Busy(Duration.ofSeconds(5));
    }
  }
}
