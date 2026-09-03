package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedModel;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadAt;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.systemCustomMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
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
 * SET_ENVIRONMENT 静止 admission 与 batch shape（steering vs user batch）。
 */
class HarnessRuntimeAcceptThreadTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand thread(
      UUID threadId, UUID expectedHead, long expectedNext, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.Thread(threadId, expectedHead, expectedNext), commands);
  }

  private static NewThreadCommand setAgentCommand(UUID idempotencyKey) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), idempotencyKey);
  }

  @Test
  void acceptsNewBatchWithCursorGuardsAndThreadWork() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    AcceptedCommands result =
        runtime.acceptCommands(
            thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
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
  void staleCursorConflictsBeforeAnyWrite() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        5,
                        List.of(userMessageCommand(TestIds.id(1), "hello"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.STALE_COMMAND_CURSOR, error.reason());
    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(baseline.threadId()).isEmpty()));
  }

  /** exact ordered replay：全 id 存在、hash 相同、sequence 连续且首 sequence 等于 expected → replayed，不写新行。 */
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
                        new ResourceMessageContent(TestIds.id(88), "photo.png", null),
                        new TextMessageContent("please summarize")))));
    AcceptancePreflight materialize = (tx, session, commands) -> List.of(durable);

    AcceptedCommands first =
        runtime.acceptCommands(
            thread(baseline.threadId(), baseline.rootEntryId(), 1, List.of(raw)), materialize);
    assertFalse(first.replayed());
    // durable 命令落库：payload 已是 RESOURCE 形态（durable codec 可持久化），requestHash 仍是 raw hash。
    assertTrue(
        first.acceptedCommands().getFirst().payload() instanceof UserMessageCommandPayload stored
            && stored.message().contents().stream()
                .anyMatch(content -> content instanceof ResourceMessageContent));
    assertEquals(raw.requestHash(), first.acceptedCommands().getFirst().requestHash());

    // 相同 raw 请求重试：按 idempotencyKey + raw requestHash 命中 ordered replay（不调用 preflight）。
    AcceptedCommands replay =
        runtime.acceptCommands(
            thread(baseline.threadId(), baseline.rootEntryId(), 1, List.of(raw)), materialize);
    assertTrue(replay.replayed());
    assertEquals(first.acceptedCommands(), replay.acceptedCommands());
    ThreadState threadState =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(1L, threadState.version());
  }

  /** replay 校验首 sequence 必须等于 expected next：期望不匹配是 order 冲突。 */
  @Test
  void orderedReplayRequiresFirstSequenceToMatchExpectedNext() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.acceptCommands(
        thread(
            baseline.threadId(),
            baseline.rootEntryId(),
            1,
            List.of(userMessageCommand(TestIds.id(1), "a"))),
        AcceptancePreflight.IDENTITY);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    thread(
                        baseline.threadId(),
                        baseline.rootEntryId(),
                        2,
                        List.of(userMessageCommand(TestIds.id(1), "a"))),
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
   * SET_ENVIRONMENT 无 quiescent admission：live Model / THREAD Work 期间接受 {@code SET_ENVIRONMENT +
   * 恰一条 user message}，只入队 / 推进 cursor，不改当前 open Turn（SET_* 由 Reducer 于下一个 INPUT 边界收割）。
   */
  @Test
  void setEnvironmentDuringLiveOrWorkIsAcceptedAndOnlyAdvancesCursor() {
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
                List.of(
                    new NewThreadCommand(new SetEnvironmentCommandPayload(null), TestIds.id(1)),
                    userMessageCommand(TestIds.id(2), "hi"))),
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
                List.of(
                    new NewThreadCommand(new SetEnvironmentCommandPayload(null), TestIds.id(3)),
                    userMessageCommand(TestIds.id(4), "hi"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(idleResult.replayed());
    // 只入队 + 推进 cursor：version +1、nextSeq 1->3、head 不变。
    ThreadState idleThread = store.transaction(tx -> tx.lockThread(idle.threadId()).orElseThrow());
    assertEquals(idle.rootEntryId(), idleThread.headEntryId());
    assertEquals(3L, idleThread.nextCommandSequence());
    assertEquals(1L, idleThread.version());
  }

  /**
   * THREAD user batch 禁止 SYSTEM CUSTOM_MESSAGE 且<b>恰一条</b>末尾 user-like；steering 必须是恰一条 SYSTEM
   * CUSTOM_MESSAGE。
   */
  @Test
  void threadShapeRejectsSystemInUserBatchAndRequiresExactSteering() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    // 非法（请求校验错误，抛 IAE）：user batch 携带 SYSTEM CUSTOM_MESSAGE。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    1,
                    List.of(
                        systemCustomMessageCommand(TestIds.id(1), "steer"),
                        userMessageCommand(TestIds.id(2), "hi"))),
                AcceptancePreflight.IDENTITY));

    // 合法：恰一条 SYSTEM steering。
    AcceptedCommands steering =
        runtime.acceptCommands(
            thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(systemCustomMessageCommand(TestIds.id(3), "steer"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(steering.replayed());
    assertEquals(ThreadCommandState.QUEUED, steering.acceptedCommands().getFirst().state());

    // 非法（IAE）：SYSTEM steering + 多余命令。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                thread(
                    baseline.threadId(),
                    baseline.rootEntryId(),
                    2,
                    List.of(
                        systemCustomMessageCommand(TestIds.id(4), "steer"),
                        userMessageCommand(TestIds.id(5), "hi"))),
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
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(baseline.threadId()).isEmpty()));
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
            thread(
                baseline.threadId(),
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(1), "a"))),
            AcceptancePreflight.IDENTITY);
    AcceptedCommands second =
        runtime.acceptCommands(
            thread(
                sibling,
                baseline.rootEntryId(),
                1,
                List.of(userMessageCommand(TestIds.id(2), "b"))),
            AcceptancePreflight.IDENTITY);
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
}
