package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.CompactThreadResult;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.api.StopResult;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SessionFirstThreadLockStore;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.spi.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.spi.driver.CompactionResolution;
import fun.fengwk.kkstudio.harness.spi.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HarnessRuntime 手动压缩入口：availability 与单事务 candidate 构造 / resolve / 提交。
 *
 * <p>用户显式触发的压缩冻结 {@link CompactionTrigger#USER_REQUEST}：{@link CompactionTrigger#MANUAL} 只保留给内置
 * {@code compact} 工具与既有历史。已持久 STOPPED 的 Thread 不再一概拒绝，而是只允许这类显式压缩；是否放行由复用 {@code resumeConverged}
 * 的退出/子 Join 围栏决定，压缩本身既不恢复执行控制，也不安排父 Thread 的 THREAD Work。
 */
class HarnessRuntimeManualCompactionTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final ModelUsage USAGE =
      new ModelUsage(50_000L, 2L, 0L, 0L, Map.of(), 0L, 50_002L);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  @Test
  void availabilityUsesTheCompactionCutRuleAndRejectsBusyThreads() {
    // 小历史没有越过 keepRecent，cut 落在 ROOT：没有可摘要前缀，稳定返回 NOTHING_TO_COMPACT。
    Fixture smallFixture = fixture();
    ClosedTurnBaseline small = seedSmallClosedTurn(smallFixture.store);
    ManualCompactionAvailability smallAvailability =
        smallFixture.runtime.manualCompactionAvailability(small.threadId());
    assertFalse(smallAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.NOTHING_TO_COMPACT,
        smallAvailability.disabledReason());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    ManualCompactionAvailability busyAvailability =
        busyFixture.runtime.manualCompactionAvailability(busy.threadId());
    assertFalse(busyAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY, busyAvailability.disabledReason());
  }

  @Test
  void compactThreadCommitsUserRequestTurnAndModelWorkWithoutMailboxCommand() {
    // Runtime 同步提交 TURN_START 和摘要子执行；父线程不消费邮箱，也不创建模型调用。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    assertTrue(fixture.runtime.manualCompactionAvailability(baseline.threadId()).available());

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertEquals(1L, result.thread().version());
    assertNotNull(result.childThreadId());
    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(result.turnStartEntryId(), path.head().id());
    assertEquals(CompactionTrigger.USER_REQUEST, start.compaction().trigger());
    assertEquals(HarnessRuntimeTestSupport.executionSelection(), start.compaction().execution());
    assertNotNull(start.contextWindow());
    assertNotNull(start.maxOutputTokens());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, result.childThreadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void compactThreadLocksSessionBeforeThread() {
    // availability 与单事务 compact 均经过守卫，防止 Entry 外键锁与 Session 深删除形成 Thread -> Session 逆序。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    Fixture fixture = fixture(store, SessionFirstThreadLockStore.wrap(store));
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store);
    fixture.resolver.autoConsistent = true;

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNotNull(result.childThreadId());
  }

  @Test
  void compactThreadUsesTypedVersionAndAvailabilityConflicts() {
    Fixture staleFixture = fixture();
    ClosedTurnBaseline stale = seedCompactionReadyClosedTurn(staleFixture.store);
    DomainException staleError =
        assertThrows(
            DomainException.class,
            () ->
                staleFixture.runtime.compactThread(new CompactThreadCommand(stale.threadId(), 1)));
    assertEquals(DomainErrorCode.CONFLICT, staleError.code());
    assertEquals(HarnessConflictReason.STALE_VERSION.name(), staleError.reason());
    assertEquals(9, path(staleFixture.store, stale.threadId()).entries().size());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    DomainException busyError =
        assertThrows(
            DomainException.class,
            () -> busyFixture.runtime.compactThread(new CompactThreadCommand(busy.threadId(), 0)));
    assertEquals(DomainErrorCode.CONFLICT, busyError.code());
    assertEquals(HarnessConflictReason.MANUAL_COMPACTION_UNAVAILABLE.name(), busyError.reason());
  }

  @Test
  void resolverRejectionClosesManualTurnWithoutModelInvocation() {
    // Resolver 业务拒绝仍形成完整 failed turn；没有半开的 manual intent。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.results.add(
        new TurnResolution.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable")));

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNull(result.childThreadId());
    assertEquals(1L, result.thread().version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(CompactionTrigger.USER_REQUEST, start.compaction().trigger());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }

  @Test
  void threadMustExistForAvailabilityAndCompact() {
    // Thread 不存在时，availability 与 compact 都由根控制面确定性返回 NotFound。
    Fixture fixture = fixture();
    UUID missing = UUID.randomUUID();
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(
                DomainException.class, () -> fixture.runtime.manualCompactionAvailability(missing))
            .code());
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(
                DomainException.class,
                () -> fixture.runtime.compactThread(new CompactThreadCommand(missing, 0)))
            .code());
  }

  /**
   * 已确认退出的 STOPPED Thread 只允许显式压缩：用户触发冻结 USER_REQUEST，摘要子执行照常启动，而被压缩的 Thread 保持 STOPPED、不被安排自己的
   * THREAD Work。
   */
  @Test
  void compactThreadOnStoppedThreadFreezesUserRequestAndKeepsStop() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    StopResult stopped =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), 0));
    assertEquals(ThreadExecutionControl.STOPPED, stopped.thread().executionControl());
    assertTrue(
        fixture.runtime.manualCompactionAvailability(baseline.threadId()).available(),
        "STOPPED thread with converged exit and joins still accepts an explicit compaction");

    CompactThreadResult result =
        fixture.runtime.compactThread(
            new CompactThreadCommand(baseline.threadId(), stopped.thread().version()));

    // 显式压缩只写摘要 turn：执行控制保持 STOPPED，version 只按本次 turn 提交推进一次。
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());
    assertEquals(stopped.thread().version() + 1, result.thread().version());
    assertNotNull(result.childThreadId());
    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(result.turnStartEntryId(), path.head().id());
    assertEquals(TurnStartReason.COMPACTION, start.reason());
    assertEquals(CompactionTrigger.USER_REQUEST, start.compaction().trigger());
    assertEquals(result.childThreadId(), start.compaction().childThreadId());
    assertEquals(1, fixture.resolver.calls);

    // 只有压缩子拿到执行 Work：STOPPED Thread 不会被再次催醒执行模型。
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, result.childThreadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // owner 票据仍然 open，并由当前 open 的 USER_REQUEST 压缩 turn 冻结的 invocation identity 标识。
    List<ThreadJoin> tickets = joins(fixture.store, result.childThreadId());
    assertEquals(1, tickets.size());
    ThreadJoin ticket = tickets.getFirst();
    assertEquals(JoinDelivery.SETTLE_OWNER_TURN, ticket.delivery());
    assertEquals(baseline.threadId(), ticket.parentThreadId());
    assertEquals(start.compaction().joinInvocationId(), ticket.invocationId());
    assertFalse(ticket.matched());
  }

  /**
   * STOPPED 上的显式压缩复用 {@code resumeConverged} 的真实退出围栏：退出证据未确认前必须在 resolve 与任何写入之前拒绝，证据落账后同一 Thread
   * 立即可压缩且仍保持 STOPPED。
   */
  @Test
  void stoppedThreadFenceRejectsManualCompactionUntilExitIsConfirmed() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    ExecutionExitFact pendingExit = seedUnconfirmedDriverExit(fixture.store, baseline.threadId());
    StopResult stopped =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), 0));
    assertEquals(ThreadExecutionControl.STOPPED, stopped.thread().executionControl());

    EntryPath stoppedPath = path(fixture.store, baseline.threadId());
    UUID stoppedHeadId = stoppedPath.head().id();
    long stoppedVersion = stopped.thread().version();
    int stoppedEntryCount = stoppedPath.entries().size();

    ManualCompactionAvailability fenced =
        fixture.runtime.manualCompactionAvailability(baseline.threadId());
    assertFalse(fenced.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_STOPPED, fenced.disabledReason());

    DomainException rejected =
        assertThrows(
            DomainException.class,
            () ->
                fixture.runtime.compactThread(
                    new CompactThreadCommand(baseline.threadId(), stoppedVersion)));
    assertEquals(DomainErrorCode.CONFLICT, rejected.code());
    assertEquals(HarnessConflictReason.MANUAL_COMPACTION_UNAVAILABLE.name(), rejected.reason());
    assertEquals(0, fixture.resolver.calls, "unconfirmed exit must be rejected before resolve");
    assertUnchanged(fixture, baseline.threadId(), stoppedHeadId, stoppedVersion, stoppedEntryCount);

    // 真实退出证据落账后围栏开放：显式压缩可提交，但不把 Thread 恢复为 RUNNABLE。
    confirmExit(fixture.store, pendingExit);
    assertTrue(fixture.runtime.manualCompactionAvailability(baseline.threadId()).available());
    long confirmedVersion = thread(fixture.store, baseline.threadId()).version();
    CompactThreadResult result =
        fixture.runtime.compactThread(
            new CompactThreadCommand(baseline.threadId(), confirmedVersion));
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());
    assertEquals(1, fixture.resolver.calls);
    TurnStartPayload start =
        (TurnStartPayload)
            entry(path(fixture.store, baseline.threadId()), result.turnStartEntryId()).payload();
    assertEquals(CompactionTrigger.USER_REQUEST, start.compaction().trigger());
  }

  /** 未结算的 owner 压缩票据同样属于围栏：STOPPED Thread 不因“已经停了”而绕过子 Join 收敛条件。 */
  @Test
  void stoppedThreadWithUnsettledOwnerTicketStaysFenced() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedStoppedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    seedChildThread(
        fixture.store,
        baseline.threadId(),
        ThreadKind.COMPACTION,
        COMPACTION_CONSTRAINTS,
        JoinDelivery.SETTLE_OWNER_TURN,
        false);
    assertTrue(
        fixture.store.<Boolean>transaction(
            tx -> tx.joins().hasIncompleteOwnerChild(baseline.threadId())));

    EntryPath stoppedPath = path(fixture.store, baseline.threadId());
    UUID stoppedHeadId = stoppedPath.head().id();
    long stoppedVersion = thread(fixture.store, baseline.threadId()).version();
    int stoppedEntryCount = stoppedPath.entries().size();

    ManualCompactionAvailability fenced =
        fixture.runtime.manualCompactionAvailability(baseline.threadId());
    assertFalse(fenced.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_STOPPED, fenced.disabledReason());

    DomainException rejected =
        assertThrows(
            DomainException.class,
            () ->
                fixture.runtime.compactThread(
                    new CompactThreadCommand(baseline.threadId(), stoppedVersion)));
    assertEquals(DomainErrorCode.CONFLICT, rejected.code());
    assertEquals(HarnessConflictReason.MANUAL_COMPACTION_UNAVAILABLE.name(), rejected.reason());
    assertEquals(
        0, fixture.resolver.calls, "unsettled owner ticket must be rejected before resolve");
    assertUnchanged(fixture, baseline.threadId(), stoppedHeadId, stoppedVersion, stoppedEntryCount);
  }

  /**
   * STOPPED 上 resolver 业务拒绝只落 FAILED turn：不催醒该 Thread（父模型不会被再次调用），也不产生压缩子或未结算票据。
   *
   * <p>同一 deferred 输入在 RUNNABLE 上会被唤醒（见 {@link
   * #resolverRejectionWakesThreadOnlyForDeferredUserDemand()}），这里必须保持静默。
   */
  @Test
  void stoppedThreadResolverRejectionClosesFailedTurnWithoutWakingIt() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    StopResult stopped =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), 0));
    assertEquals(ThreadExecutionControl.STOPPED, stopped.thread().executionControl());
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    fixture.resolver.results.add(rejection());
    long version = thread(fixture.store, baseline.threadId()).version();

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), version));

    assertNull(result.childThreadId());
    assertEquals(ThreadExecutionControl.STOPPED, result.thread().executionControl());
    assertEquals(1, fixture.resolver.calls);
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(TurnEndOutcome.FAILED, ((TurnEndPayload) path.head().payload()).outcome());
    assertEquals(
        CompactionTrigger.USER_REQUEST,
        ((TurnStartPayload) entry(path, result.turnStartEntryId()).payload())
            .compaction()
            .trigger());

    // 拒绝不催醒也不恢复：没有 THREAD Work、没有压缩子、没有未结算票据，排队输入继续留在邮箱。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertTrue(joins(fixture.store, baseline.threadId()).isEmpty());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(path.root().sessionId()).size()));
    List<ThreadCommand> queued =
        fixture.store.<List<ThreadCommand>>transaction(
            tx -> loadCommandsByThread(tx, baseline.threadId()));
    assertEquals(1, queued.size());
    assertEquals(ThreadCommandState.QUEUED, queued.getFirst().state());
  }

  /** open 的手动压缩 turn 与 Stop 的关系：同一 stopRequestId 的重放不动任何事实，新 Stop 取消该压缩并不留未结算票据。 */
  @Test
  void stopReplayAndNewStopGovernOpenManualCompactionOnStoppedThread() {
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    UUID stopRequestId = UUID.randomUUID();
    StopResult first = fixture.runtime.stop(new StopCommand(baseline.threadId(), stopRequestId, 0));
    assertFalse(first.replayed());
    CompactThreadResult compaction =
        fixture.runtime.compactThread(
            new CompactThreadCommand(baseline.threadId(), first.thread().version()));
    UUID compactionChild = compaction.childThreadId();
    assertNotNull(compactionChild);
    EntryPath opened = path(fixture.store, baseline.threadId());
    UUID openHeadId = opened.head().id();
    long openVersion = compaction.thread().version();
    int openEntryCount = opened.entries().size();

    // 重放旧 stopRequestId 直接返回既有回执：不写 Entry、不推进 version，open 的压缩 turn 保持原样。
    StopResult replay =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), stopRequestId, openVersion));
    assertTrue(replay.replayed());
    assertEquals(openVersion, replay.thread().version());
    EntryPath replayed = path(fixture.store, baseline.threadId());
    assertEquals(openHeadId, replayed.head().id());
    assertEquals(openEntryCount, replayed.entries().size());

    // 新 Stop 关闭压缩回合，但不得在压缩子真实退出前冻结票据。
    ExecutionExitFact pendingExit = seedUnconfirmedDriverExit(fixture.store, compactionChild);
    StopResult again =
        fixture.runtime.stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), openVersion));
    assertFalse(again.replayed());
    assertEquals(ThreadExecutionControl.STOPPED, again.thread().executionControl());
    EntryPath cancelled = path(fixture.store, baseline.threadId());
    assertTrue(cancelled.openTurnStart().isEmpty());
    assertEquals(TurnEndOutcome.STOPPED, ((TurnEndPayload) cancelled.head().payload()).outcome());
    assertEquals(1, joins(fixture.store, compactionChild).size());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));

    // 迟到的退出确认只收敛已取消的票据，不能催醒已无开放压缩回合的停止父。
    confirmExit(fixture.store, pendingExit);
    assertTrue(joins(fixture.store, compactionChild).isEmpty());
    assertFalse(
        fixture.store.<Boolean>transaction(
            tx -> tx.joins().hasIncompleteOwnerChild(baseline.threadId())));
    assertEquals(
        0,
        fixture.store.<Integer>transaction(
            tx -> tx.joins().countIncompleteChildren(baseline.threadId())));
    assertEquals(
        ThreadExecutionControl.STOPPED, thread(fixture.store, compactionChild).executionControl());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, compactionChild)));
  }

  private static void assertUnchanged(
      Fixture fixture, UUID threadId, UUID headEntryId, long version, int entryCount) {
    EntryPath after = path(fixture.store, threadId);
    assertEquals(headEntryId, after.head().id());
    assertEquals(entryCount, after.entries().size());
    assertEquals(version, thread(fixture.store, threadId).version());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(after.root().sessionId()).size()),
        "rejected manual compaction must not create a COMPACTION child thread");
  }

  @Test
  void compactThreadRejectsNullResolverResult() {
    // Resolver 返回 null 违背契约，必须零 durable mutation。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));
    // FakeAgentDriver 未配置 resolution 时返回 null，Runtime 必须直接拒绝。
    assertEquals("turn resolver returned null for manual compaction", error.getMessage());
    // 零 durable mutation：Thread 版本、Entry 历史与 COMPACTION 子线程都不变。
    assertEquals(0L, thread(fixture.store, baseline.threadId()).version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(9, path.entries().size());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(path.root().sessionId()).size()),
        "rejected manual compaction must not create a COMPACTION child thread");
  }

  @Test
  void resolverRunsInsideTheSameCompactionTransaction() {
    // 嵌套 store transaction 会被明确拒绝：resolver 能在 onResolve 中观察到活动事务，证明 candidate 构造与 resolve 同事务。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () ->
            assertThrows(IllegalStateException.class, () -> fixture.store.transaction(tx -> null));

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNotNull(result.childThreadId());
    assertEquals(
        CompactionTrigger.USER_REQUEST,
        ((TurnStartPayload) path(fixture.store, baseline.threadId()).head().payload())
            .compaction()
            .trigger());
  }

  @Test
  void typedSqlFailureRollsBackCompactionTurnChildThreadAndCommands() {
    // SQL 失败可能已污染当前事务：typed transient 整体回滚，绝不落 COMPACTION/child/Command marker/Work。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    long version = thread(fixture.store, baseline.threadId()).version();
    fixture.resolver.onResolve =
        () -> {
          throw new TurnResolveTransientException("sql failed", new RuntimeException("db down"));
        };

    assertThrows(
        TurnResolveTransientException.class,
        () ->
            fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), version)));

    EntryPath after = path(fixture.store, baseline.threadId());
    assertEquals(9, after.entries().size());
    assertEquals(baseline.turnEndEntryId(), after.head().id());
    ThreadState thread = thread(fixture.store, baseline.threadId());
    assertEquals(version, thread.version());
    assertEquals(
        1,
        fixture.store.<Integer>transaction(
            tx -> tx.threads().listBySession(after.root().sessionId()).size()));
    List<ThreadCommand> commands =
        fixture.store.<List<ThreadCommand>>transaction(
            tx -> loadCommandsByThread(tx, baseline.threadId()));
    assertEquals(1, commands.size());
    assertEquals(ThreadCommandState.QUEUED, commands.getFirst().state());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void resolverRejectionWakesThreadOnlyForDeferredUserDemand() {
    // 任何 queued 消息命令（USER_MESSAGE 或 USER CUSTOM_MESSAGE，含运行时 reminder）都保留 INPUT obligation；
    // SET_* 配置命令不构成 user demand。
    Fixture userFixture = fixture();
    ClosedTurnBaseline userBaseline = seedCompactionReadyClosedTurn(userFixture.store);
    seedCommand(
        userFixture.store,
        userBaseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    userFixture.resolver.results.add(rejection());
    long userVersion = thread(userFixture.store, userBaseline.threadId()).version();
    userFixture.runtime.compactThread(
        new CompactThreadCommand(userBaseline.threadId(), userVersion));
    assertNotNull(
        work(userFixture.store, new WorkTarget(WorkTargetType.THREAD, userBaseline.threadId())));

    Fixture configFixture = fixture();
    ClosedTurnBaseline configBaseline = seedCompactionReadyClosedTurn(configFixture.store);
    seedCommand(
        configFixture.store,
        configBaseline.threadId(),
        new SetExecutionCommandPayload(new ExecutionSelection("fake", "{\"m\":\"b\"}")));
    configFixture.resolver.results.add(rejection());
    long configVersion = thread(configFixture.store, configBaseline.threadId()).version();
    configFixture.runtime.compactThread(
        new CompactThreadCommand(configBaseline.threadId(), configVersion));
    assertNull(
        work(
            configFixture.store, new WorkTarget(WorkTargetType.THREAD, configBaseline.threadId())));
  }

  private static Fixture fixture() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    return fixture(store, store);
  }

  private static Fixture fixture(InMemoryHarnessStore store, HarnessStore runtimeStore) {
    FakeTurnResolver resolver = new FakeTurnResolver();
    HarnessRuntime runtime =
        new HarnessRuntime(
            runtimeStore,
            new DriverRegistry(List.of(new FakeAgentDriver(resolver::resolve))),
            () -> new CompactionConfig(20_000));
    return new Fixture(store, resolver, runtime);
  }

  private static TurnResolution.Rejected rejection() {
    return new TurnResolution.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable"));
  }

  private static UUID seedCommand(
      InMemoryHarnessStore store, UUID threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          tx.commands()
              .insert(
                  List.of(
                      new ThreadCommand(
                          threadId,
                          thread.nextCommandSequence(),
                          payload,
                          idempotencyKey,
                          ThreadCommandPayloadJsonCodec.requestHash(payload),
                          null,
                          null,
                          null,
                          NOW)));
          tx.threads().update(thread.reserveCommandSequences(1, NOW));
          return idempotencyKey;
        });
  }

  private static ClosedTurnBaseline seedCompactionReadyClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 50_000, 90_000);
  }

  /** 已持久 STOPPED 且退出/子 Join 围栏干净的可压缩历史：把“停止事实”本身与 Stop 控制面行为隔开。 */
  private static ClosedTurnBaseline seedStoppedCompactionReadyClosedTurn(
      InMemoryHarnessStore store) {
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(store);
    HarnessRuntimeTestSupport.inTransaction(
        store,
        tx -> {
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads()
              .update(
                  thread.reserveCommandSequencesAndSetControl(
                      1, ThreadExecutionControl.STOPPED, NOW));
        });
    return baseline;
  }

  /** 登记一次尚未确认退出的本地 Driver 执行：STOPPED Thread 的真实退出围栏依赖它才能收敛。 */
  private static ExecutionExitFact seedUnconfirmedDriverExit(
      InMemoryHarnessStore store, UUID threadId) {
    ExecutionExitFact fact =
        new ExecutionExitFact(
            UUID.randomUUID(),
            threadId,
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            ExecutionExitKind.DRIVER,
            null,
            null,
            ExecutionExitState.UNCONFIRMED,
            NOW,
            null,
            null);
    store.transaction(
        tx -> {
          tx.threads().lock(threadId);
          return tx.exits().register(fact);
        });
    return fact;
  }

  private static void confirmExit(InMemoryHarnessStore store, ExecutionExitFact fact) {
    HarnessRuntimeTestSupport.inTransaction(
        store,
        tx -> {
          ExecutionExitLedger.lockAncestorChain(tx, fact.threadId());
          ExecutionExitLedger.advanceAfterConfirmation(
              tx,
              fact.threadId(),
              ExecutionExitLedger.confirm(tx, fact, ExecutionExitState.STUDIO_EXITED, NOW)
                  .orElseThrow());
        });
  }

  private static List<ThreadJoin> joins(InMemoryHarnessStore store, UUID childThreadId) {
    return store.transaction(tx -> tx.joins().loadIncomplete(childThreadId));
  }

  private static Entry entry(EntryPath path, UUID entryId) {
    return path.entries().stream()
        .filter(candidate -> candidate.id().equals(entryId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("entry " + entryId + " is not on the path"));
  }

  private static ClosedTurnBaseline seedSmallClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 10, 10);
  }

  private static ClosedTurnBaseline seedClosedTurn(
      InMemoryHarnessStore store, int historicalChars, int currentChars) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID firstStartId = tx.nextId();
          UUID firstUserId = tx.nextId();
          UUID firstAssistantId = tx.nextId();
          UUID firstEndId = tx.nextId();
          UUID secondStartId = tx.nextId();
          UUID secondUserId = tx.nextId();
          UUID secondAssistantId = tx.nextId();
          UUID secondEndId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
          tx.entries()
              .insert(
                  new Entry(
                      rootId,
                      sessionId,
                      null,
                      new RootPayload(HarnessRuntimeTestSupport.settings()),
                      NOW));
          tx.entries().insert(turnStart(firstStartId, sessionId, rootId, threadId));
          tx.entries()
              .insert(
                  message(
                      firstUserId,
                      sessionId,
                      firstStartId,
                      userMessage("historical user " + "h".repeat(historicalChars)),
                      null));
          tx.entries()
              .insert(
                  message(
                      firstAssistantId,
                      sessionId,
                      firstUserId,
                      assistantMessage("historical assistant " + "a".repeat(historicalChars)),
                      metadata()));
          tx.entries().insert(turnEnd(firstEndId, sessionId, firstAssistantId, firstStartId));
          tx.entries().insert(turnStart(secondStartId, sessionId, firstEndId, threadId));
          tx.entries()
              .insert(
                  message(
                      secondUserId,
                      sessionId,
                      secondStartId,
                      userMessage("user asks a very long question" + "x".repeat(currentChars)),
                      null));
          tx.entries()
              .insert(
                  message(
                      secondAssistantId,
                      sessionId,
                      secondUserId,
                      assistantMessage("assistant reply"),
                      metadata()));
          tx.entries().insert(turnEnd(secondEndId, sessionId, secondAssistantId, secondStartId));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      null,
                      secondEndId,
                      HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                      "main",
                      threadId,
                      false,
                      ThreadKind.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1,
                      0,
                      NOW,
                      NOW));
          return new ClosedTurnBaseline(threadId, secondEndId);
        });
  }

  private static Entry turnStart(UUID id, UUID sessionId, UUID parentId, UUID threadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT,
            HarnessRuntimeTestSupport.settings(),
            threadId,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            null,
            null),
        NOW);
  }

  private static Entry turnEnd(UUID id, UUID sessionId, UUID parentId, UUID turnStartEntryId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        NOW);
  }

  private static Entry message(
      UUID id,
      UUID sessionId,
      UUID parentId,
      AgentMessage message,
      AssistantMessageMetadata metadata) {
    return new Entry(id, sessionId, parentId, new MessagePayload(message, metadata, null), NOW);
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage assistantMessage(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE);
  }

  private static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    ThreadState thread = thread(store, threadId);
    return store.transaction(tx -> tx.entries().loadPath(thread.headEntryId()));
  }

  /**
   * 手动入口与自动压缩共用同一条约束 guard：创建约束不允许自动压缩的 Thread（压缩子及其经约束合并继承的后代）一律不可手动压缩，即使该 Thread 自己的委派已经结算（{@code
   * loadIncompleteJoins} 为空）。runtime primitive 自身拒绝并返回明确 receipt，不依赖上层调用方的 root guard。
   */
  @Test
  void availabilityRejectsThreadsInsideCompactionChildTree() {
    Fixture fixture = fixture();
    UUID parentThreadId = HarnessRuntimeTestSupport.seedBaseline(fixture.store).threadId();
    // 压缩子自身（其 COMPACTION Join 未结算）。
    UUID compactionChild =
        seedChildThread(
            fixture.store,
            parentThreadId,
            ThreadKind.COMPACTION,
            COMPACTION_CONSTRAINTS,
            JoinDelivery.SETTLE_OWNER_TURN,
            false);
    // 压缩子的后代：自己的 task Join 已结算，只有继承的创建约束能挡住它。
    UUID descendant =
        seedChildThread(
            fixture.store,
            compactionChild,
            ThreadKind.SUBAGENT,
            COMPACTION_CONSTRAINTS,
            JoinDelivery.NOTIFY_PARENT,
            true);
    assertTrue(
        fixture.store.<Boolean>transaction(tx -> tx.joins().loadIncomplete(descendant).isEmpty()));

    ManualCompactionAvailability descendantAvailability =
        fixture.runtime.manualCompactionAvailability(descendant);
    assertFalse(descendantAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY,
        descendantAvailability.disabledReason());
    DomainException rejected =
        assertThrows(
            DomainException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(descendant, 0)));
    assertEquals(DomainErrorCode.CONFLICT, rejected.code());
    assertEquals(HarnessConflictReason.MANUAL_COMPACTION_UNAVAILABLE.name(), rejected.reason());

    assertFalse(fixture.runtime.manualCompactionAvailability(compactionChild).available());

    // 该压缩树的父 Thread 只是 Join owner，不在压缩子执行树内：其可用性仍由自身历史决定，不被压缩树牵连。
    ManualCompactionAvailability ownerAvailability =
        fixture.runtime.manualCompactionAvailability(parentThreadId);
    assertFalse(ownerAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.NO_RESOLVED_CONTEXT,
        ownerAvailability.disabledReason());
  }

  /** 压缩子冻结的创建约束：输出上限、禁用 cache、不允许自动压缩；后代经约束合并继承。 */
  private static final ThreadConstraints COMPACTION_CONSTRAINTS = new ThreadConstraints(100, false);

  /** 手工创建带 source command 与 Join 的 child Thread，用于按创建约束事实验证手动入口 guard。 */
  private static UUID seedChildThread(
      InMemoryHarnessStore store,
      UUID parentThreadId,
      ThreadKind trigger,
      ThreadConstraints constraints,
      JoinDelivery delivery,
      boolean matchedJoin) {
    return store.transaction(
        tx -> {
          tx.threads().lock(parentThreadId);
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID commandId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "child-" + sessionId, NOW));
          tx.entries()
              .insert(
                  new Entry(
                      rootEntryId,
                      sessionId,
                      null,
                      new RootPayload(HarnessRuntimeTestSupport.settings()),
                      NOW));
          List<UUID> ancestors = tx.threads().findAncestorChain(parentThreadId);
          UUID rootThreadId = ancestors.isEmpty() ? parentThreadId : ancestors.getLast();
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      parentThreadId,
                      rootEntryId,
                      HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                      "child-" + delivery.name().toLowerCase(),
                      rootThreadId,
                      false,
                      trigger,
                      constraints,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      2L,
                      0L,
                      NOW,
                      NOW));
          CustomMessageCommandPayload payload =
              new CustomMessageCommandPayload(AgentMessage.user("child instruction"));
          String requestHash = ThreadCommandPayloadJsonCodec.requestHash(payload);
          tx.commands()
              .insert(
                  List.of(
                      new ThreadCommand(
                          threadId, 1L, payload, commandId, requestHash, null, null, null, NOW)));
          ThreadJoin join =
              new ThreadJoin(
                  commandId,
                  requestHash,
                  parentThreadId,
                  threadId,
                  1L,
                  "child",
                  null,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW,
                  delivery,
                  null);
          tx.joins().insert(join);
          if (matchedJoin) {
            ThreadJoin matched = join.match(rootEntryId, null, NOW);
            if (delivery == JoinDelivery.NOTIFY_PARENT) {
              matched = matched.delivered(1L, NOW);
            }
            tx.joins().update(matched);
          }
          return threadId;
        });
  }

  private static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.threads().find(threadId).orElseThrow());
  }

  private static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.work().find(target).orElse(null));
  }

  private record Fixture(
      InMemoryHarnessStore store, FakeTurnResolver resolver, HarnessRuntime runtime) {}

  private record ClosedTurnBaseline(UUID threadId, UUID turnEndEntryId) {}

  /** Scripted resolver：可返回显式结果、在 resolve 中触发竞态，或按冻结 compaction facts 生成一致请求。 */
  private static final class FakeTurnResolver {
    private final Deque<TurnResolution> results = new ArrayDeque<>();
    private Runnable onResolve;
    private boolean autoConsistent;
    private int calls;

    TurnResolution resolve() {
      calls++;
      if (onResolve != null) {
        onResolve.run();
      }
      if (autoConsistent) {
        return new TurnResolution.Compaction(
            new CompactionResolution(
                HarnessRuntimeTestSupport.executionSelection(),
                4096L,
                CONTEXT_WINDOW,
                MAX_OUTPUT_TOKENS,
                new BranchSettings(
                    "compaction",
                    HarnessRuntimeTestSupport.executionSelection(),
                    HarnessRuntimeTestSupport.settings().environmentName())));
      }
      return results.poll();
    }
  }
}
