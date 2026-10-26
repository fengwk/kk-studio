package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.systemReminderCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.Ticket;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * acceptCommands 的 THREAD target：cursor 原子接受、ordered exact replay（hash / partial / order）、
 * SET_AGENT 静止 admission 与 batch shape（steering vs user batch）。
 */
class HarnessRuntimeAcceptThreadTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand thread(
      UUID threadId, UUID expectedHead, long expectedNext, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.Thread(threadId, expectedHead, expectedNext), commands);
  }

  /** 单条纯 USER_MESSAGE 批次：无 cursor。 */
  private static AcceptCommandsCommand pure(UUID threadId, NewThreadCommand command) {
    return new AcceptCommandsCommand(new AcceptCommandsTarget.Thread(threadId), List.of(command));
  }

  private static NewThreadCommand setAgentCommand(UUID idempotencyKey) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), idempotencyKey);
  }

  @Test
  void acceptsNewBatchWithCursorGuardsAndThreadWork() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    AcceptedCommands result =
        runtime.acceptCommands(
            pure(baseline.threadId(), userMessageCommand(TestIds.id(1), "hello")),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(baseline.sessionId(), result.session().id());
    assertEquals(baseline.rootEntryId(), result.rootEntry().id());
    assertEquals(1, result.acceptedCommands().size());
    assertEquals(1L, result.acceptedCommands().getFirst().sequence());

    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(2L, thread.nextCommandSequence());
    assertEquals(1L, thread.version());
    assertTrue(
        store
            .<Boolean>transaction(
                tx ->
                    tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                        .isPresent())
            .equals(Boolean.TRUE));
  }

  @Test
  void pureUserMessageRejectsAnyCursorWhileCursorBoundBatchStaysStaleChecked() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // 单条纯 USER_MESSAGE 只有无 cursor 一条路径：携带 cursor（即使精确）是请求错误且零写入。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    1,
                    List.of(userMessageCommand(TestIds.id(1), "hello"))),
                AcceptancePreflight.IDENTITY));
    assertEquals(
        0, store.<Integer>transaction(tx -> loadCommandsByThread(tx, baseline.threadId()).size()));
    AcceptedCommands accepted =
        runtime.acceptCommands(
            pure(baseline.threadId(), userMessageCommand(TestIds.id(1), "hello")),
            AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());
    assertEquals(1L, accepted.acceptedCommands().getFirst().sequence());

    ThreadState advanced =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(2L, advanced.nextCommandSequence());

    // SET 前缀混合批次仍是 cursor-bound：stale sequence 确定性拒绝且零写入。
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        9,
                        List.of(
                            setAgentCommand(TestIds.id(2)),
                            userMessageCommand(TestIds.id(3), "next"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.STALE_COMMAND_CURSOR, error.reason());
    assertEquals(
        1, store.<Integer>transaction(tx -> loadCommandsByThread(tx, baseline.threadId()).size()));
  }

  /** exact ordered replay：全 id 存在、hash 相同、sequence 连续且首 sequence 等于 expected → replayed，不写新行。 */
  @Test
  void acceptsAndReplaysPureUserMessageWithoutCursor() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    AcceptCommandsCommand cursorless =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(baseline.threadId()),
            List.of(userMessageCommand(TestIds.id(1), "cursorless")));
    AcceptedCommands first = runtime.acceptCommands(cursorless, AcceptancePreflight.IDENTITY);
    assertFalse(first.replayed());
    assertEquals(1L, first.acceptedCommands().getFirst().sequence());

    // head 由后续命令推进后，不带 cursor 的 exact replay 仍返回同一源命令。
    runtime.acceptCommands(
        pure(baseline.threadId(), userMessageCommand(TestIds.id(2), "after")),
        AcceptancePreflight.IDENTITY);
    AcceptedCommands replay = runtime.acceptCommands(cursorless, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(first.acceptedCommands(), replay.acceptedCommands());

    // 非纯批次缺少 cursor 是受控请求错误。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(baseline.threadId(), null, null),
                    List.of(
                        setAgentCommand(TestIds.id(3)), userMessageCommand(TestIds.id(4), "x"))),
                AcceptancePreflight.IDENTITY));
  }

  @Test
  void exactOrderedReplayReturnsExistingCommands() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // THREAD user batch 只能含恰一条 user-like：多命令 batch 用 SET_* 前缀 + 单条 user 构造。
    List<NewThreadCommand> batch =
        List.of(setAgentCommand(TestIds.id(9)), userMessageCommand(TestIds.id(1), "a"));
    AcceptedCommands first =
        runtime.acceptCommands(
            thread(baseline.threadId(), baseline.rootEntryId(), 1, batch),
            AcceptancePreflight.IDENTITY);
    AcceptedCommands replay =
        runtime.acceptCommands(
            thread(baseline.threadId(), baseline.rootEntryId(), 1, batch),
            AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(baseline.rootEntryId(), first.rootEntry().id());
    assertEquals(baseline.rootEntryId(), replay.rootEntry().id());
    assertEquals(first.acceptedCommands(), replay.acceptedCommands());
    ThreadState threadState =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, threadState.version());
  }

  /**
   * preflight 把 raw ATTACHMENT 物化为 durable RESOURCE 后，durable command 的 requestHash 仍是 raw hash： 相同
   * raw 请求重试能按 idempotencyKey + requestHash 命中 ordered replay，且 durable payload hash 与 raw 不同。
   */
  @Test
  void materializedPreflightReplayMatchesRawRequestHash() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    NewThreadCommand raw =
        new NewThreadCommand(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        new AttachmentMessageContent(TestIds.id(77)),
                        new TextMessageContent("please summarize")))),
            TestIds.id(1));
    // 模拟 attachment 物化：ATTACHMENT -> RESOURCE，保留原始幂等键（下游 platform 应对 preflight 使用 withPayload）。
    NewThreadCommand durable =
        raw.withPayload(
            new UserMessageCommandPayload(
                new AgentMessage(
                    AgentMessageRole.USER,
                    List.of(
                        ResourceMessageContent.media(TestIds.id(88), "photo.png"),
                        new TextMessageContent("please summarize")))));
    AcceptancePreflight materialize = (tx, session, commands) -> List.of(durable);

    AcceptedCommands first = runtime.acceptCommands(pure(baseline.threadId(), raw), materialize);
    assertFalse(first.replayed());
    // durable 命令落库：payload 已是 RESOURCE 形态（durable codec 可持久化），requestHash 仍是 raw hash。
    assertTrue(
        first.acceptedCommands().getFirst().payload() instanceof UserMessageCommandPayload stored
            && stored.message().contents().stream()
                .anyMatch(content -> content instanceof ResourceMessageContent));
    assertEquals(raw.requestHash(), first.acceptedCommands().getFirst().requestHash());

    // 相同 raw 请求重试：按 idempotencyKey + raw requestHash 命中 ordered replay（不调用 preflight）。
    AcceptedCommands replay = runtime.acceptCommands(pure(baseline.threadId(), raw), materialize);
    assertTrue(replay.replayed());
    assertEquals(first.acceptedCommands(), replay.acceptedCommands());
    ThreadState threadState =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, threadState.version());
  }

  /** replay 校验首 sequence 必须等于 expected next：cursor-bound 批次期望不匹配是 order 冲突。 */
  @Test
  void orderedReplayRequiresFirstSequenceToMatchExpectedNext() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    List<NewThreadCommand> batch =
        List.of(setAgentCommand(TestIds.id(9)), userMessageCommand(TestIds.id(1), "a"));
    runtime.acceptCommands(
        thread(baseline.threadId(), baseline.rootEntryId(), 1, batch),
        AcceptancePreflight.IDENTITY);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(baseline.threadId(), baseline.rootEntryId(), 2, batch),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.COMMAND_REPLAY_ORDER_MISMATCH, error.reason());
  }

  /** 部分 id 已存在 → PARTIAL_COMMAND_REPLAY；同 id 不同 hash → IDEMPOTENCY_KEY_REUSED。 */
  @Test
  void partialAndHashConflictsUseStableReasons() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.acceptCommands(
        thread(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(setAgentCommand(TestIds.id(9)), userMessageCommand(TestIds.id(1), "a"))),
        AcceptancePreflight.IDENTITY);

    HarnessRuntimeConflictException partial =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(
                            setAgentCommand(TestIds.id(9)),
                            userMessageCommand(TestIds.id(1), "a"),
                            userMessageCommand(TestIds.id(2), "b"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.PARTIAL_COMMAND_REPLAY, partial.reason());

    HarnessRuntimeConflictException reused =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(
                            setAgentCommand(TestIds.id(9)),
                            userMessageCommand(TestIds.id(1), "DIFFERENT"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.IDEMPOTENCY_KEY_REUSED, reused.reason());
  }

  /**
   * SET_AGENT 无 quiescent admission：live Model / THREAD Work 期间接受 {@code SET_AGENT + 恰一条 user
   * message}，只入队 / 推进 cursor，不改当前 open Turn（SET_* 由 Reducer 于下一个 INPUT 边界收割）。
   */
  @Test
  void setAgentDuringLiveOrWorkIsAcceptedAndOnlyAdvancesCursor() {
    // 场景一：live MODEL_ACTIVE（当前 open Turn 在跑）+ THREAD Work 行 —— 接受且只推进 cursor。
    HarnessRuntimeTestSupport.ModelBaseline live = seedModel(store, ModelInvocationStatus.RUNNING);
    seedThreadWork(store, live.threadId());
    UUID liveHead =
        store.transaction(tx -> tx.findThread(live.threadId()).orElseThrow()).headEntryId();
    AcceptedCommands liveResult =
        runtime.acceptCommands(
            thread(
                live.threadId(),
                liveHead,
                1,
                List.of(setAgentCommand(TestIds.id(1)), userMessageCommand(TestIds.id(2), "hi"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(liveResult.replayed());
    assertEquals(
        List.of(1L, 2L), liveResult.acceptedCommands().stream().map(c -> c.sequence()).toList());
    // 当前 open Turn 不被修改：head 仍是原 user entry，RUNNING Model 不受影响。
    ModelInvocation model =
        store.transaction(tx -> tx.findModelInvocation(live.modelId()).orElseThrow());
    assertEquals(ModelInvocationStatus.RUNNING, model.status());

    // 场景二：IDLE 但有 THREAD Work 行 —— 接受且只推进 cursor。
    HarnessRuntimeTestSupport.Baseline idle = seedBaseline(store);
    seedThreadWork(store, idle.threadId());
    AcceptedCommands idleResult =
        runtime.acceptCommands(
            thread(
                idle.threadId(),
                idle.rootEntryId(),
                1,
                List.of(setAgentCommand(TestIds.id(3)), userMessageCommand(TestIds.id(4), "hi"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(idleResult.replayed());
    // 只入队 + 推进 cursor：version +1、nextSeq 1->3、head 不变。
    ThreadState idleThread = store.transaction(tx -> tx.lockThread(idle.threadId()).orElseThrow());
    assertEquals(idle.rootEntryId(), idleThread.headEntryId());
    assertEquals(3L, idleThread.nextCommandSequence());
    assertEquals(1L, idleThread.version());
  }

  /**
   * THREAD batch 形状与初始 batch 一致：<b>恰一条</b>末尾 USER 消息（USER_MESSAGE 或 USER CUSTOM_MESSAGE，含运行时
   * reminder）；多条 user-like 或 user-like 之后还有额外命令都必须被拒。
   */
  @Test
  void threadShapeRequiresExactlyOneTrailingUserMessage() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // 非法（请求校验错误，抛 IAE）：两条 user-like 命令（运行时 reminder + 真实用户消息）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    1,
                    List.of(
                        systemReminderCommand(TestIds.id(1), "steer"),
                        userMessageCommand(TestIds.id(2), "hi"))),
                AcceptancePreflight.IDENTITY));

    // 合法：恰一条 user-like，且它本身可以是运行时注入的 reminder CUSTOM_MESSAGE。
    AcceptedCommands reminderOnly =
        runtime.acceptCommands(
            thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(systemReminderCommand(TestIds.id(3), "steer"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(reminderOnly.replayed());
    assertEquals(ThreadCommandState.QUEUED, reminderOnly.acceptedCommands().getFirst().state());

    // 非法（IAE）：SET_* 出现在 user-like 之后。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    2,
                    List.of(
                        userMessageCommand(TestIds.id(5), "hi"), setAgentCommand(TestIds.id(4)))),
                AcceptancePreflight.IDENTITY));
  }

  /** THREAD user batch 必须恰有一条末尾 user-like：多条 user message 同类合并被拒。 */
  @Test
  void threadUserBatchRejectsMultipleUserMessages() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    1,
                    List.of(
                        userMessageCommand(TestIds.id(1), "a"),
                        userMessageCommand(TestIds.id(2), "b"))),
                AcceptancePreflight.IDENTITY));
    // 没有写入任何命令。
    assertTrue(
        store.<Boolean>transaction(tx -> loadCommandsByThread(tx, baseline.threadId()).isEmpty()));
  }

  /**
   * Session 内 sibling Thread 的正常执行互不阻塞：两个 Thread 各自 KEY SHARE 同一 Session 后 FOR UPDATE 自己的 Thread
   * 均可完成（Store 锁序 Session -&gt; Thread，不做 Session 级 FOR UPDATE 串行化）。
   */
  @Test
  void siblingThreadsInSameSessionDoNotSerializeEachOther() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID sibling = seedThreadAt(store, baseline.rootEntryId());
    AcceptedCommands first =
        runtime.acceptCommands(
            pure(baseline.threadId(), userMessageCommand(TestIds.id(1), "a")),
            AcceptancePreflight.IDENTITY);
    AcceptedCommands second =
        runtime.acceptCommands(
            pure(sibling, userMessageCommand(TestIds.id(2), "b")), AcceptancePreflight.IDENTITY);
    assertEquals(1L, first.acceptedCommands().getFirst().sequence());
    assertEquals(1L, second.acceptedCommands().getFirst().sequence());
    // 两个 sibling Thread 都在同一 Session 内建立，listThreadsBySession 返回两者（按确定 id 序）。
    assertEquals(
        List.of(baseline.threadId(), sibling),
        store.transaction(
            tx -> {
              tx.lockSessionForKeyShare(baseline.sessionId());
              return tx.listThreadsBySession(baseline.sessionId()).stream()
                  .map(ThreadState::id)
                  .toList();
            }));
  }

  private static NewThreadCommand setModelCommand(UUID idempotencyKey) {
    return new NewThreadCommand(
        new SetModelCommandPayload(ModelSelection.model("model", "v1")), idempotencyKey);
  }

  private static Ticket rootTicket(UUID invocationId) {
    return Ticket.task(invocationId, null, null, "a".repeat(64), "assistant", 3, 3, 1, 1);
  }

  /** 纯设置 batch 在 STOPPED 既有 Thread 上被接受：不复活为 RUNNABLE、不打开 INPUT turn / 不启动模型，仅入队等待下一 INPUT 边界收割。 */
  @Test
  void acceptsSettingsOnlyBatchWithoutResumingStoppedThreadOrOpeningTurn() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.stop(new StopCommand(baseline.threadId(), TestIds.id(9), 0));
    ThreadState stopped = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertTrue(stopped.executionControl().isStopped());

    AcceptedCommands result =
        runtime.acceptCommands(
            thread(
                baseline.threadId(),
                stopped.headEntryId(),
                stopped.nextCommandSequence(),
                List.of(setAgentCommand(TestIds.id(1)), setModelCommand(TestIds.id(2)))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(2, result.acceptedCommands().size());
    assertTrue(result.thread().executionControl().isStopped());
    ThreadState after = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertTrue(after.executionControl().isStopped());
    // 未打开 INPUT turn：head 不变，设置命令仅入队等待下一个 INPUT。
    assertEquals(stopped.headEntryId(), after.headEntryId());
    assertEquals(stopped.nextCommandSequence() + 2, after.nextCommandSequence());
    List<ThreadCommand> queued = runtime.getThreadSnapshot(baseline.threadId()).queuedCommands();
    assertEquals(2, queued.size());
    assertEquals(ThreadCommandState.QUEUED, queued.getFirst().state());
    // 纯设置不单独调用模型：STOPPED 线程仍无 active ModelInvocation。
    assertNull(runtime.getThreadSnapshot(baseline.threadId()).model());
  }

  /** RUNNABLE 既有 Thread 上的纯设置 batch 同样只入队、不打开 INPUT turn、不推进 head。 */
  @Test
  void settingsOnlyBatchOnRunnableThreadDoesNotOpenTurn() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    AcceptedCommands result =
        runtime.acceptCommands(
            thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(setModelCommand(TestIds.id(1)))),
            AcceptancePreflight.IDENTITY);
    assertFalse(result.replayed());
    ThreadState after = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(baseline.rootEntryId(), after.headEntryId());
    assertEquals(1L, after.version());
    assertEquals(ThreadExecutionControl.RUNNABLE, after.executionControl());
    assertEquals(1, runtime.getThreadSnapshot(baseline.threadId()).queuedCommands().size());
  }

  /** Join 交付只追加 NOTIFICATION：既有 Thread 的 Join 接受必须携带真实末尾任务输入。 */
  @Test
  void rejectsJoinAcceptanceWithoutTrailingTaskInput() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    Ticket join = rootTicket(TestIds.id(60));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SpawnTestSupport.acceptAndJoin(
                    runtime,
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        1,
                        List.of(setModelCommand(TestIds.id(1)))),
                    join,
                    AcceptancePreflight.IDENTITY));
    assertTrue(error.getMessage().contains("batches must end with exactly one user-like input"));
    assertTrue(runtime.findJoin(join.invocationId()).isEmpty());
  }
}
