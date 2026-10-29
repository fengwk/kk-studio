package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.RESOLVE_FAILURE_DELAY;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.builtinExecution;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedClosedTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.threadState;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SessionFirstThreadLockStore.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolveTransientException;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/** ThreadProcessor continuation / input planning 与同事务 resolve 提交、reschedule：每个 claim 恰执行一个动作。 */
class ThreadProcessorPlanningTest extends ThreadProcessorTestBase {
  private static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);

  @Test
  void entryMutationsLockSessionBeforeThread() {
    // 单事务路径经过 Session-first 守卫：先锁 Thread 会确定性失败，而不是等待偶发数据库死锁。
    Fixture fixture = fixture();
    InMemoryHarnessStore store = fixture.store;
    HarnessStore wrapped = wrap(store);
    ThreadProcessor processor =
        new ThreadProcessor(
            wrapped,
            HarnessRuntimeTestSupport.driver(wrapped, fixture.resolver),
            new ThreadProcessorConfig(
                RESOLVE_FAILURE_DELAY, () -> ThreadProcessorTestSupport.COMPACTION_CONFIG));
    var baseline = seedBaseline(store);
    seedCommand(
        store, baseline.threadId(), new UserMessageCommandPayload(userMessage("session-first")));
    requestThreadWork(store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    ClaimedWork claim = claimThreadWork(store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, processor.process(claim));
  }

  /** 测试意图：INPUT 收获快照内全部 queued Command（不再在首条 user-like 后截断），按 sequence 有序物化，且顺序稳定。 */
  @Test
  void inputConsumesWholeQueuedSnapshotInOrder() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID first =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("first")));
    UUID second =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("second")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(4, path.entries().size());
    MessagePayload user = (MessagePayload) path.head().payload();
    assertEquals("second", ((TextMessageContent) user.message().contents().getFirst()).text());
    assertEquals(
        ThreadCommandState.APPLIED, command(fixture.store, baseline.threadId(), first).state());
    assertEquals(
        ThreadCommandState.APPLIED, command(fixture.store, baseline.threadId(), second).state());
  }

  /**
   * 测试意图：Resolver 在 Turn 提交事务内部同步调用，candidate Entry 只存在于内存（预分配 ID 无需先落库）。 通过“resolver 持有 store
   * 事务期间再开事务会因 nested transaction 被拒绝”证明调用发生在同一事务内。
   */
  @Test
  void resolverRunsInsideTurnTransactionWithUnpersistedCandidate() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hello")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () -> {
          // 同一 store transaction 仍处于活动状态：嵌套事务被明确拒绝，证明 resolve 不在事务外。
          assertThrows(IllegalStateException.class, () -> fixture.store.transaction(tx -> null));
          EntryPath candidate = fixture.resolver.lastPath;
          assertEquals(3, candidate.entries().size());
          assertEquals(
              TurnStartReason.INPUT,
              ((TurnStartPayload) candidate.entries().get(1).payload()).reason());
          // resolver 消费的是尚未持久化的 candidate suffix；其 ID 在提交前不可能是 committed head。
          assertFalse(candidate.entries().get(1).id().equals(baseline.rootEntryId()));
          assertEquals(
              "hello",
              ((TextMessageContent)
                      ((MessagePayload) candidate.head().payload()).message().contents().get(0))
                  .text());
        };
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath committed = path(fixture.store, baseline.threadId());
    assertEquals(3, committed.entries().size());
    assertEquals(
        TurnStartReason.INPUT, ((TurnStartPayload) committed.entries().get(1).payload()).reason());
    assertEquals(committed.head().id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(1, fixture.resolver.calls);
  }

  /** 已关闭的 continuation 边界优先消费 queued user-like：claim1 直接启动 INPUT，不先空转一次 CONTINUATION。 */
  @Test
  void continuationDueWithQueuedUserStartsInputInsteadOfDeferringMessage() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(7, path.entries().size());
    TurnStartPayload input = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.INPUT, input.reason());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * 测试意图：INPUT 全快照有序消费 setup 前缀、USER、CUSTOM、GOAL（每条恰好一次，包含消息之后的 settings）；无 queued 消息时仍启动
   * CONTINUATION。
   */
  @Test
  void continuationDueConsumesOrderedPrefixExactlyOnceAndKeepsPlainContinuation() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    UUID userCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("steer")));
    UUID trailingEnvironment =
        seedCommand(
            fixture.store, baseline.threadId(), new SetEnvironmentCommandPayload("after-message"));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：安全边界先应用 SET_MODEL，append SETTINGS 快照并标记命令 applied（不调度模型）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    // claim2：INPUT 消费 USER 与消息之后的 SET_ENVIRONMENT（命令顺序保持）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(EntryType.SETTINGS, path.entries().get(5).payload().type());
    TurnStartPayload input = (TurnStartPayload) path.entries().get(6).payload();
    assertEquals(TurnStartReason.INPUT, input.reason());
    assertEquals("model-b", builtinExecution(input.settings()).modelName());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), trailingEnvironment).state());

    Fixture customFixture = fixture();
    var customBaseline = seedClosedTurn(customFixture.store, true);
    UUID customCommand =
        seedCommand(
            customFixture.store,
            customBaseline.threadId(),
            new CustomMessageCommandPayload(userMessage("custom steer")));
    requestThreadWork(customFixture.store, customBaseline.threadId());
    customFixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, customFixture.nextClaim(customBaseline.threadId()));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(customFixture.store, customBaseline.threadId(), customCommand).state());
    assertEquals(
        TurnStartReason.INPUT,
        ((TurnStartPayload)
                path(customFixture.store, customBaseline.threadId()).entries().get(5).payload())
            .reason());

    Fixture goalFixture = fixture();
    var goalBaseline = seedClosedTurn(goalFixture.store, true);
    UUID goalCommand =
        seedCommand(goalFixture.store, goalBaseline.threadId(), new GoalCommandPayload("ship it"));
    requestThreadWork(goalFixture.store, goalBaseline.threadId());
    goalFixture.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, goalFixture.nextClaim(goalBaseline.threadId()));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(goalFixture.store, goalBaseline.threadId(), goalCommand).state());

    Fixture plain = fixture();
    var plainBaseline = seedClosedTurn(plain.store, true);
    requestThreadWork(plain.store, plainBaseline.threadId());
    plain.resolver.autoConsistent = true;
    assertEquals(ThreadProcessResult.COMPLETED, plain.nextClaim(plainBaseline.threadId()));
    TurnStartPayload continuation =
        (TurnStartPayload) path(plain.store, plainBaseline.threadId()).entries().get(5).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
  }

  /**
   * 测试意图：settings 排在 USER 之后时仍在同一安全边界一次性应用（消息水位不前进，USER 保持 queued）；同一字段的多次设置按 snapshot 原序
   * 归约，最后一条生效，随后 INPUT 才消费 USER 并继承该 settings。
   */
  @Test
  void interleavedSettingsApplyBeforeInputAndPreserveInternalOrder() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    UUID environmentCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new SetEnvironmentCommandPayload("after-message"));
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new SetModelCommandPayload(ModelSelection.model("model-a", "v1")));
    seedCommand(
        fixture.store,
        baseline.threadId(),
        new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：安全边界一次性应用全部 settings（USER 保持 queued、水位不前进、不调度模型）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath settled = path(fixture.store, baseline.threadId());
    // ROOT + SETTINGS：没有任何 turn，也没有消费 USER。
    assertEquals(2, settled.entries().size());
    assertEquals(EntryType.SETTINGS, settled.entries().get(1).payload().type());
    BranchSettings applied = ((SettingsPayload) settled.entries().get(1).payload()).settings();
    assertEquals("model-b", builtinExecution(applied).modelName());
    assertEquals("after-message", applied.environmentName());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), environmentCommand).state());

    // claim2：INPUT 才消费 USER，并继承已应用的 settings。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    TurnStartPayload input =
        (TurnStartPayload) path(fixture.store, baseline.threadId()).entries().get(2).payload();
    assertEquals(TurnStartReason.INPUT, input.reason());
    assertEquals("model-b", builtinExecution(input.settings()).modelName());
    assertEquals("after-message", input.settings().environmentName());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(1, fixture.resolver.calls);
  }

  @Test
  void continuationConsumesOnlyOrdinaryConfigCommandsAndAppliesSettings() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    // final branch 事实 = 消费 SET_MODEL 后的 candidate settings；auto 模式按同源事实构造一致请求。
    fixture.resolver.autoConsistent = true;

    // claim1：安全边界先应用 SET_MODEL（append SETTINGS 快照，不调度模型）；claim2：INPUT 消费 USER。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(6).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    assertEquals("model-b", builtinExecution(turnStart.settings()).modelName());
    assertEquals("v2", builtinExecution(turnStart.settings()).variant());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(5).id(),
        command(fixture.store, baseline.threadId(), modelCommand).appliedEntryId());
    assertEquals(1, fixture.resolver.calls);
  }

  /** 测试意图：无 continuation / 无 message 时 standalone SET_* 仍被安全边界应用为 SETTINGS 快照，但不创建 turn、不调度模型。 */
  @Test
  void configOnlyWithoutContinuationCreatesNoTurnAndNeverCallsResolver() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID configCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());

    // claim1：应用 SET_MODEL 为 SETTINGS 快照；claim2：无 continuation / 无 message，quiesce。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(2, path.entries().size());
    assertEquals(EntryType.SETTINGS, path.entries().get(1).payload().type());
    assertEquals("model-b", builtinExecution(path.baseSettings()).modelName());
    assertEquals(0, fixture.resolver.calls);
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), configCommand).state());
  }

  /** 测试意图：SET_* 先在安全边界 append SETTINGS 快照，CONTINUATION 续写只读该快照、不生成模型可见消息。 */
  @Test
  void continuationAppliesSettingsWithoutInjectingMessages() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    // 关闭的原 turn 5 条 Entry + SETTINGS 快照 + TURN_START(CONTINUATION)。
    assertEquals(7, path.entries().size());
    assertEquals(EntryType.SETTINGS, path.entries().get(5).payload().type());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(6).payload();
    assertEquals(TurnStartReason.CONTINUATION, turnStart.reason());
    assertEquals("model-b", builtinExecution(turnStart.settings()).modelName());
    assertEquals("v2", builtinExecution(turnStart.settings()).variant());
    assertEquals(turnStart, path.head().payload());
    // SET_MODEL 已被安全边界消费并记录在 SETTINGS 快照上；无 deferred user demand。
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        path.entries().get(5).id(),
        command(fixture.store, baseline.threadId(), modelCommand).appliedEntryId());
  }

  @Test
  void inputTurnAppliesSettingsAndMessagesInOneAtomicTurn() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID modelCommand =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new SetModelCommandPayload(ModelSelection.model("model-b", "v2")));
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：安全边界应用 SET_MODEL 为 SETTINGS 快照；claim2：INPUT turn 一次构建并提交（Model Work 驱动）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    // ROOT + SETTINGS 快照 + TURN_START(INPUT) + USER message。
    assertEquals(4, path.entries().size());
    assertEquals(EntryType.SETTINGS, path.entries().get(1).payload().type());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(2).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    assertEquals("model-b", builtinExecution(turnStart.settings()).modelName());
    MessagePayload userPayload = (MessagePayload) path.entries().get(3).payload();
    assertEquals(AgentMessageRole.USER, userPayload.message().role());
    assertEquals("hi", ((TextMessageContent) userPayload.message().contents().get(0)).text());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), modelCommand).state());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(3).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    // ModelInvocation：requestHead == candidate head，turnStart 指向新 TURN_START，MODEL Work 已请求。
    ModelInvocation invocation =
        inTx(
                fixture,
                tx ->
                    builtin(tx)
                        .findModelInvocationByTurn(baseline.threadId(), path.entries().get(2).id()))
            .orElseThrow();
    assertEquals(path.entries().get(3).id(), invocation.requestHeadEntryId());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.MODEL, invocation.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  /** 确定性 typed rejection 写入 durable AssistantError + FAILED TURN_END，且不产生 ModelInvocation。 */
  @Test
  void typedRejectionWritesAssistantErrorAndFailedTurnEndWithoutInvocation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));

    // 一个 claim：rejected（message 已消费，无 deferred）直接完成，不请求 THREAD。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(1).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(3).payload();
    assertEquals("CONFIG_ERROR", error.error().code());
    assertEquals("bad config", error.error().message());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(4).payload();
    assertEquals(path.entries().get(1).id(), end.turnStartEntryId());
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertFalse(end.continueModel());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    assertEquals(
        path.entries().get(4).id(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertTrue(
        inTx(
                fixture,
                tx ->
                    builtin(tx)
                        .findModelInvocationByTurn(baseline.threadId(), path.entries().get(1).id()))
            .isEmpty());
    // 无 deferred message、闭合也未触发 compaction（ROOT 无历史）-> 不保留 THREAD Work。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /**
   * 续作边界的 INPUT 被 reject 时已消费整个 queued 快照：两条 USER 都标记 APPLIED，无 deferred demand 也不触发 compaction
   * 时不保留 THREAD Work，且 commit 仍以 claim fence 收尾。
   */
  @Test
  void rejectedInputConsumesWholeSnapshotAndLeavesNoWake() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, true);
    UUID first =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("first")));
    UUID second =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new UserMessageCommandPayload(userMessage("second")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "bad config")));

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(10, path.entries().size());
    TurnStartPayload turnStart = (TurnStartPayload) path.entries().get(5).payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(8).payload();
    assertEquals("CONFIG_ERROR", error.error().code());
    TurnEndPayload end = (TurnEndPayload) path.entries().get(9).payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
    assertEquals(TurnEndReason.TURN_FAILED, end.reason());
    assertEquals(
        ThreadCommandState.APPLIED, command(fixture.store, baseline.threadId(), first).state());
    assertEquals(
        ThreadCommandState.APPLIED, command(fixture.store, baseline.threadId(), second).state());
    // 全快照已消费且无 compaction obligation -> 不保留 THREAD Work。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
    assertEquals(1, fixture.resolver.calls);
  }

  /**
   * 普通 RuntimeException 是确定性 resolver 失败：落 durable AssistantError + FAILED TURN_END，绝不无限
   * reschedule。
   */
  @Test
  void resolverDeterministicFailureWritesDurableFailedTurn() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.failure = new IllegalStateException("resolver db down");

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    assertEquals(
        TurnStartReason.INPUT, ((TurnStartPayload) path.entries().get(1).payload()).reason());
    AssistantErrorPayload error = (AssistantErrorPayload) path.entries().get(3).payload();
    assertEquals("TURN_RESOLVE_FAILED", error.error().code());
    // durable 文本稳定：绝不泄露 raw cause。
    assertFalse(error.error().message().contains("resolver db down"));
    assertEquals(
        TurnEndOutcome.FAILED, ((TurnEndPayload) path.entries().get(4).payload()).outcome());
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    // 无 deferred demand：确定性失败不保留 THREAD Work。
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /** null 结果违反 Resolver 契约：同样落 durable FAILED，绝不按失败延迟重排。 */
  @Test
  void resolverNullResultWritesDurableFailedTurn() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));

    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(5, path.entries().size());
    assertEquals(
        "TURN_RESOLVE_FAILED",
        ((AssistantErrorPayload) path.entries().get(3).payload()).error().code());
    assertEquals(
        TurnEndOutcome.FAILED, ((TurnEndPayload) path.entries().get(4).payload()).outcome());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  /** 只有显式 typed transient 基础设施失败才 reschedule：零 durable mutation，按失败延迟重排同一 Work。 */
  @Test
  void resolverTransientFailureReschedulesWithZeroDurableMutation() {
    Fixture fixture = fixture();
    var baseline = seedBaseline(fixture.store);
    UUID userCommand =
        seedCommand(
            fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.failure =
        new TurnResolveTransientException(
            "infrastructure unavailable", new RuntimeException("db outage"));

    assertEquals(ThreadProcessResult.RESCHEDULED, fixture.nextClaim(baseline.threadId()));

    assertEquals(1, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(
        ThreadCommandState.QUEUED,
        command(fixture.store, baseline.threadId(), userCommand).state());
    Work threadWork =
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId()));
    assertNotNull(threadWork);
    assertNull(threadWork.leaseToken());
    assertEquals(NOW.plus(RESOLVE_FAILURE_DELAY), threadWork.availableAt());
  }

  /** N1：自有邮箱中的 NOTIFICATION 命令可独立规划 INPUT——从 READY 起步跑到 terminal；输入水位只由 INPUT 推进。 */
  @Test
  void notificationOnlyInputRunsFromReadyToTerminalAndAdvancesWatermarkOnInputOnly() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, false);
    UUID notification =
        seedCommand(
            fixture.store,
            baseline.threadId(),
            new NotificationCommandPayload(
                UUID.randomUUID(),
                NotificationKind.SUBAGENT_RESULT,
                UUID.randomUUID(),
                userMessage("child done")));
    requestThreadWork(fixture.store, baseline.threadId());
    fixture.resolver.autoConsistent = true;

    // claim1：notification-only INPUT 规划并物化通知 Entry，模型 invocation 以 READY 起步。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath afterPlan = path(fixture.store, baseline.threadId());
    TurnStartPayload turnStart =
        (TurnStartPayload) afterPlan.openTurnStart().orElseThrow().payload();
    assertEquals(TurnStartReason.INPUT, turnStart.reason());
    assertTrue(
        afterPlan.entries().stream().anyMatch(e -> e.payload() instanceof NotificationPayload));
    assertEquals(
        ThreadCommandState.APPLIED,
        command(fixture.store, baseline.threadId(), notification).state());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).inputThroughSequence());
    ModelInvocation planned =
        fixture.store.transaction(
            tx ->
                builtin(tx)
                    .findModelInvocationByTurn(
                        baseline.threadId(), afterPlan.openTurnStart().orElseThrow().id())
                    .orElseThrow());
    assertEquals(ModelInvocationStatus.READY, planned.status());

    // claim2：模型 SUCCEEDED 后一个 claim 应用 terminal，跑到 COMPLETED 终止 turn。
    transitionModel(fixture.store, planned.id(), model -> model.markRunning(NOW));
    transitionModel(
        fixture.store,
        planned.id(),
        model -> model.succeed(successResponse(List.of(), "bash"), NOW));
    requestThreadWork(fixture.store, baseline.threadId());
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(baseline.threadId()));
    EntryPath finalPath = path(fixture.store, baseline.threadId());
    assertEquals(TurnEndOutcome.COMPLETED, ((TurnEndPayload) finalPath.head().payload()).outcome());
    // terminal apply 不改变输入水位：仍停在 INPUT 的 cutoff。
    assertEquals(1L, thread(fixture.store, baseline.threadId()).inputThroughSequence());
  }

  /** N1：fork 继承含通知的历史但自有邮箱为空时，不产生输入需求、不调用 resolver、不误唤醒。 */
  @Test
  void forkedThreadWithoutOwnMailboxDoesNotSpuriouslyWake() {
    Fixture fixture = fixture();
    var baseline = seedClosedTurn(fixture.store, false);
    UUID notificationEntryId =
        fixture.store.transaction(
            tx -> {
              UUID entryId = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      entryId,
                      baseline.sessionId(),
                      baseline.turnEndEntryId(),
                      new NotificationPayload(
                          UUID.randomUUID(),
                          NotificationKind.SUBAGENT_RESULT,
                          UUID.randomUUID(),
                          userMessage("inherited")),
                      NOW));
              UUID forkedId = tx.nextId();
              tx.insertThread(threadState(forkedId, baseline.sessionId(), entryId, "forked", NOW));
              return entryId;
            });

    UUID forkedThreadId =
        fixture.store.transaction(
            tx -> {
              ThreadState state =
                  tx.listThreadsBySession(baseline.sessionId()).stream()
                      .filter(candidate -> candidate.headEntryId().equals(notificationEntryId))
                      .findFirst()
                      .orElseThrow();
              tx.lockThread(state.id());
              tx.requestWork(new WorkTarget(WorkTargetType.THREAD, state.id()));
              return state.id();
            });

    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(forkedThreadId));
    assertEquals(0, fixture.resolver.calls);
    // head 未推进、未新增 Entry、未启动模型。
    EntryPath path = path(fixture.store, forkedThreadId);
    assertEquals(notificationEntryId, path.head().id());
    assertEquals(0L, thread(fixture.store, forkedThreadId).inputThroughSequence());
    assertTrue(
        fixture.store.<Boolean>transaction(
            tx ->
                builtin(tx)
                    .findModelInvocationByTurn(forkedThreadId, notificationEntryId)
                    .isEmpty()));
  }
}
