package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.systemReminderCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * acceptCommands 的初始 target（NEW_SESSION / ENTRY）：同事务原子接受、initial creation replay 与 ID reuse、batch
 * shape 与前缀 SYSTEM steering 规则。
 */
class HarnessRuntimeAcceptInitialTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand newSession(List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewRootSession(TestIds.id(101), TestIds.id(102), settings(), true),
        commands);
  }

  /** 每次 shape 校验用例使用独立预分配 id，避免与上一用例的初始创建冲突。 */
  private static AcceptCommandsCommand newSession(
      UUID sessionId, UUID threadId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewRootSession(sessionId, threadId, settings(), false), commands);
  }

  private static AcceptCommandsCommand entry(
      UUID sessionId, UUID startEntryId, List<NewThreadCommand> commands) {
    return entry(sessionId, startEntryId, TestIds.id(203), commands);
  }

  private static AcceptCommandsCommand entry(
      UUID sessionId, UUID startEntryId, UUID threadId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewThread(sessionId, startEntryId, threadId, "branch", false),
        commands);
  }

  private static NewThreadCommand setAgent(UUID idempotencyKey) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), idempotencyKey);
  }

  @Test
  void newSessionAcceptsSessionRootThreadAndInitialCommandsAtomically() {
    AcceptedCommands result =
        runtime.acceptCommands(
            newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(TestIds.id(101), result.session().id());
    assertEquals(TestIds.id(102), result.thread().id());

    Session session = store.transaction(tx -> tx.findSession(TestIds.id(101)).orElseThrow());
    assertEquals(T0, session.createdAt());

    Entry root = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()).head());
    assertEquals(EntryType.ROOT, root.payload().type());
    assertEquals(TestIds.id(101), root.sessionId());
    assertEquals(settings(), ((RootPayload) root.payload()).settings());

    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow());
    assertEquals(TestIds.id(101), thread.sessionId());
    assertEquals(root.id(), thread.headEntryId());
    assertEquals(CREATION_REQUEST_HASH.length(), thread.creationRequestHash().length());
    assertTrue(thread.yoloPolicy().isEnabled());
    // creation request hash 是服务端 deterministic 64 位小写 SHA-256。
    assertTrue(thread.creationRequestHash().matches("[0-9a-f]{64}"));
    // 初始 thread version 0，accept 后恰好 +1；next sequence 从 1 起推进 1。
    assertEquals(1L, thread.version());
    assertEquals(2L, thread.nextCommandSequence());

    List<ThreadCommand> commands =
        store.transaction(tx -> tx.loadCommandsByThread(TestIds.id(102)));
    assertEquals(1, commands.size());
    assertEquals(1L, commands.getFirst().sequence());
    assertEquals(ThreadCommandState.QUEUED, commands.getFirst().state());

    Work work =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, TestIds.id(102))).orElseThrow());
    assertEquals(1L, work.wakeVersion());
    assertEquals(T0, work.availableAt());
  }

  /** 任一步失败（preflight 抛异常）完整回滚：Session/ROOT/Thread/Commands/Work 都不落盘。 */
  @Test
  void newSessionPreflightFailureRollsBackEverything() {
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.acceptCommands(
                newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
                (tx, session, commands) -> {
                  throw new IllegalStateException("preflight failed");
                }));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(TestIds.id(101)).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(102)).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(TestIds.id(102)).isEmpty()));
  }

  @Test
  void newSessionRejectsMalformedPreflightResultsAndRollsBack() {
    // Preflight 是事务内初始创建边界：null、数量变化或幂等键变化都必须 fail closed 且不留下部分事实。
    assertPreflightContractViolation(
        TestIds.id(130),
        TestIds.id(131),
        (tx, session, commands) -> null,
        "command preflight returned null");
    assertPreflightContractViolation(
        TestIds.id(132),
        TestIds.id(133),
        (tx, session, commands) -> List.of(),
        "command preflight must return exactly 1 commands, got 0");
    assertPreflightContractViolation(
        TestIds.id(134),
        TestIds.id(135),
        (tx, session, commands) -> {
          NewThreadCommand command = commands.getFirst();
          return List.of(
              new NewThreadCommand(command.payload(), TestIds.id(999), command.requestHash()));
        },
        "command preflight must preserve idempotencyKey and requestHash at index 0");
  }

  /**
   * exact initial creation replay：同 session + 同 hash 返回现有接受事实（replayed=true），不写新行、不 bump version。
   */
  @Test
  void newSessionExactReplayReturnsExistingAcceptanceFacts() {
    AcceptCommandsCommand command = newSession(List.of(userMessageCommand(TestIds.id(1), "hello")));
    AcceptedCommands first = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    AcceptedCommands replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);

    assertTrue(replay.replayed());
    assertEquals(first.session(), replay.session());
    assertEquals(first.thread().id(), replay.thread().id());
    assertEquals(first.thread().headEntryId(), replay.thread().headEntryId());
    assertEquals(first.thread().version(), replay.thread().version());
    // replay 不创建第二条命令。
    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow());
    assertEquals(1L, thread.version());
    assertEquals(2L, thread.nextCommandSequence());
  }

  /**
   * 初始 replay：初始创建第二批后，重放初始请求只按 idempotencyKey 返回原始初始命令（sequence 从 1 连续），顺序与 terminal 状态为当前值，且不产生
   * version mutation（replay 只命中初始批次，不返回第二批）。
   */
  @Test
  void newSessionReplayAfterSecondBatchReturnsOnlyTheInitialCommands() {
    AcceptCommandsCommand initial =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(
                TestIds.id(101), TestIds.id(102), settings(), false),
            List.of(setAgent(TestIds.id(9)), userMessageCommand(TestIds.id(1), "a")));
    AcceptedCommands first = runtime.acceptCommands(initial, AcceptancePreflight.IDENTITY);
    // 同一 Thread 上再接受第二批（THREAD target，cursor: head=root, next=3）。
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(TestIds.id(102), first.thread().headEntryId(), 3),
            List.of(userMessageCommand(TestIds.id(2), "c"))),
        AcceptancePreflight.IDENTITY);
    ThreadState before = store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow());
    assertEquals(2L, before.version());

    AcceptedCommands replay = runtime.acceptCommands(initial, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    // 只返回原始初始命令（SET_AGENT + user），顺序为请求顺序、sequence 从 1 连续、状态为当前 QUEUED，第二批不混入。
    assertEquals(
        List.of(TestIds.id(9), TestIds.id(1)),
        replay.acceptedCommands().stream().map(ThreadCommand::idempotencyKey).toList());
    assertEquals(
        List.of(1L, 2L), replay.acceptedCommands().stream().map(ThreadCommand::sequence).toList());
    assertTrue(
        replay.acceptedCommands().stream()
            .allMatch(command -> command.state() == ThreadCommandState.QUEUED));
    // 无 version mutation：replay 后 projection 与 stored 均未推进。
    assertEquals(before, replay.thread());
    assertEquals(before, store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow()));
  }

  /** 同 threadId + 不同初始创建请求 → THREAD_ID_REUSED。 */
  @Test
  void newSessionThreadIdReusedWithDifferentHashConflicts() {
    runtime.acceptCommands(
        newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    newSession(List.of(userMessageCommand(TestIds.id(1), "different"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(Reason.THREAD_ID_REUSED, error.reason());
  }

  /** 同 threadId + 不同 Session → 仍属 ID reuse，绝不静默重建。 */
  @Test
  void newSessionThreadIdReusedAcrossSessionsConflicts() {
    runtime.acceptCommands(
        newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);
    AcceptCommandsCommand otherSession =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(
                TestIds.id(105), TestIds.id(102), settings(), false),
            List.of(userMessageCommand(TestIds.id(1), "hello")));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(otherSession, AcceptancePreflight.IDENTITY));
    assertEquals(Reason.THREAD_ID_REUSED, error.reason());
  }

  /**
   * 初始 batch shape：恰一条末尾 USER 消息（USER_MESSAGE 或 USER CUSTOM_MESSAGE，含运行时 reminder）；允许固定顺序 SET_* 前缀。
   */
  @Test
  void initialBatchShapeRulesAreEnforced() {
    // 合法：SET_AGENT 前缀 + 单条 user message。
    runtime.acceptCommands(
        newSession(
            TestIds.id(110),
            TestIds.id(111),
            List.of(setAgent(TestIds.id(2)), userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);

    // 非法（请求校验错误，抛 IAE 而非业务冲突）：没有 user-like message。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(TestIds.id(112), TestIds.id(113), List.of(setAgent(TestIds.id(3)))),
                AcceptancePreflight.IDENTITY));

    // 非法（IAE）：SET_* 出现在消息之后（user-like 必须恰好一条且结尾）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(114),
                    TestIds.id(115),
                    List.of(userMessageCommand(TestIds.id(4), "hello"), setAgent(TestIds.id(5)))),
                AcceptancePreflight.IDENTITY));

    // 合法：单条运行时 reminder（USER CUSTOM_MESSAGE）本身就是合法的末尾 USER 消息。
    AcceptedCommands reminderOnly =
        runtime.acceptCommands(
            newSession(
                TestIds.id(116),
                TestIds.id(117),
                List.of(systemReminderCommand(TestIds.id(6), "steer"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(reminderOnly.replayed());

    // 非法（IAE）：reminder + user message 是两条 user-like，违反「恰一条末尾 USER 消息」。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(118),
                    TestIds.id(119),
                    List.of(
                        systemReminderCommand(TestIds.id(7), "steer"),
                        userMessageCommand(TestIds.id(8), "hello"))),
                AcceptancePreflight.IDENTITY));
  }

  /** 测试意图：typed GOAL 是 user-like 终止输入——可作为唯一末尾命令（可跟在 SET_* 前缀之后）；与 USER_MESSAGE 同批或不在末尾都被拒。 */
  @Test
  void goalBatchShapeAllowsOnlySoleTerminalGoalCommand() {
    AcceptedCommands result =
        runtime.acceptCommands(
            newSession(
                TestIds.id(130),
                TestIds.id(131),
                List.of(new NewThreadCommand(new GoalCommandPayload("finish"), TestIds.id(2)))),
            AcceptancePreflight.IDENTITY);
    assertFalse(result.replayed());
    // 合法：SET_* 前缀 + 末尾 GOAL（显式 null 表示清除）。
    runtime.acceptCommands(
        newSession(
            TestIds.id(132),
            TestIds.id(133),
            List.of(
                setAgent(TestIds.id(3)),
                new NewThreadCommand(new GoalCommandPayload(null), TestIds.id(4)))),
        AcceptancePreflight.IDENTITY);
    // 非法：GOAL 与 USER_MESSAGE 同批是两条 user-like。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(134),
                    TestIds.id(135),
                    List.of(
                        new NewThreadCommand(new GoalCommandPayload("finish"), TestIds.id(5)),
                        userMessageCommand(TestIds.id(6), "hi"))),
                AcceptancePreflight.IDENTITY));
    // 非法：GOAL 之后还有命令。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(136),
                    TestIds.id(137),
                    List.of(
                        new NewThreadCommand(new GoalCommandPayload("finish"), TestIds.id(7)),
                        setAgent(TestIds.id(8)))),
                AcceptancePreflight.IDENTITY));
  }

  /** SET_* 前缀固定顺序必须是 SET_AGENT -&gt; SET_MODEL -&gt; SET_ENVIRONMENT：用正反请求证明该顺序（正向通过 / 反向 IAE）。 */
  @Test
  void setPrefixOrderRequiresAgentBeforeModelBeforeEnvironment() {
    // 合法：全前缀 + 单条 user message。
    AcceptedCommands result =
        runtime.acceptCommands(
            newSession(
                TestIds.id(120),
                TestIds.id(121),
                List.of(
                    setAgent(TestIds.id(2)),
                    new NewThreadCommand(
                        new SetModelCommandPayload(new ModelSelection("acme", "gpt-x", "default")),
                        TestIds.id(3)),
                    new NewThreadCommand(new SetEnvironmentCommandPayload("local"), TestIds.id(5)),
                    userMessageCommand(TestIds.id(4), "hi"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(result.replayed());
    // 非法：SET_AGENT 出现在 SET_MODEL 之后（顺序不变量拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(122),
                    TestIds.id(123),
                    List.of(
                        new NewThreadCommand(
                            new SetModelCommandPayload(
                                new ModelSelection("acme", "gpt-x", "default")),
                            TestIds.id(2)),
                        setAgent(TestIds.id(1)),
                        userMessageCommand(TestIds.id(3), "hi"))),
                AcceptancePreflight.IDENTITY));
    // 非法：SET_ENVIRONMENT 出现在 SET_MODEL 之前（顺序不变量拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(126),
                    TestIds.id(127),
                    List.of(
                        new NewThreadCommand(
                            new SetEnvironmentCommandPayload("local"), TestIds.id(2)),
                        new NewThreadCommand(
                            new SetModelCommandPayload(
                                new ModelSelection("acme", "gpt-x", "default")),
                            TestIds.id(3)),
                        userMessageCommand(TestIds.id(1), "hi"))),
                AcceptancePreflight.IDENTITY));
    // 非法：同类型 SET_* 出现两次。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(124),
                    TestIds.id(125),
                    List.of(
                        setAgent(TestIds.id(1)),
                        setAgent(TestIds.id(2)),
                        userMessageCommand(TestIds.id(3), "hi"))),
                AcceptancePreflight.IDENTITY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(128),
                    TestIds.id(129),
                    List.of(
                        new NewThreadCommand(
                            new SetEnvironmentCommandPayload("local"), TestIds.id(2)),
                        new NewThreadCommand(new SetEnvironmentCommandPayload(null), TestIds.id(3)),
                        userMessageCommand(TestIds.id(1), "hi"))),
                AcceptancePreflight.IDENTITY));
    // 非法：SET_* 出现在消息之后。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(130),
                    TestIds.id(131),
                    List.of(
                        userMessageCommand(TestIds.id(1), "hi"),
                        new NewThreadCommand(
                            new SetEnvironmentCommandPayload("local"), TestIds.id(2)))),
                AcceptancePreflight.IDENTITY));
  }

  /** ENTRY：KEY SHARE 锁 session、验证 start Entry 同 Session、插入 Thread+Commands+Work；不复制 Entry。 */
  @Test
  void entryAcceptsNewThreadUnderExistingSessionWithoutCopyingEntries() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptedCommands result =
        runtime.acceptCommands(
            entry(
                baseline.sessionId(),
                baseline.rootEntryId(),
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(baseline.sessionId(), result.session().id());
    assertEquals(baseline.rootEntryId(), result.rootEntry().id());
    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(203)).orElseThrow());
    // head 直接指向既有 start Entry：不复制 Entry，Session 内仍只有 ROOT。
    assertEquals(baseline.rootEntryId(), thread.headEntryId());
    List<Entry> entries = store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId()));
    assertEquals(1, entries.size());
    assertEquals(1L, thread.version());
    assertEquals(2L, thread.nextCommandSequence());
    assertTrue(
        store
            .<Boolean>transaction(
                tx ->
                    tx.findWork(new WorkTarget(WorkTargetType.THREAD, TestIds.id(203))).isPresent())
            .equals(Boolean.TRUE));
  }

  /** ENTRY exact replay：同 hash + 同 Session 返回 replayed，不写第二套事实。 */
  @Test
  void entryExactReplayReturnsExistingFacts() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptCommandsCommand command =
        entry(
            baseline.sessionId(),
            baseline.rootEntryId(),
            List.of(userMessageCommand(TestIds.id(1), "hello")));
    AcceptedCommands first = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    AcceptedCommands replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(baseline.rootEntryId(), first.rootEntry().id());
    assertEquals(baseline.rootEntryId(), replay.rootEntry().id());
    assertEquals(first.thread().version(), replay.thread().version());
    assertEquals(first.thread().nextCommandSequence(), replay.thread().nextCommandSequence());
  }

  @Test
  void entryRejectsMissingSessionWithoutCreatingThread() {
    // ENTRY 必须锚定现存 Session；缺失时在创建 Thread/Command/Work 前确定性失败。
    UUID missingSessionId = TestIds.id(210);
    HarnessRuntimeNotFoundException error =
        assertThrows(
            HarnessRuntimeNotFoundException.class,
            () ->
                runtime.acceptCommands(
                    entry(
                        missingSessionId,
                        TestIds.id(211),
                        List.of(userMessageCommand(TestIds.id(1), "hello"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals("session " + missingSessionId + " does not exist", error.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(203)).isEmpty()));
  }

  /** ENTRY：start Entry 不在给定 Session 内是非法请求。 */
  @Test
  void entryRejectsStartEntryInAnotherSession() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    UUID foreignRoot =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootEntryId = tx.nextId();
              tx.insertSession(new Session(sessionId, "session-" + sessionId, T0));
              tx.insertEntry(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(settings()), T0));
              return rootEntryId;
            });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                entry(
                    baseline.sessionId(),
                    foreignRoot,
                    List.of(userMessageCommand(TestIds.id(1), "hello"))),
                AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(203)).isEmpty()));
  }

  /**
   * active / 半轮前缀不是合法 fork 边界：USER、ASSISTANT、TOOL 结果、仍在进行的 TURN_START，以及未闭合 STOP Turn 内部的任何
   * prefix，都必须原子拒绝且零写入（不留 Thread / Command / Work）。
   */
  @Test
  void entryRejectsActiveMidTurnAndUnclosedStopPrefixesWithoutCreatingThread() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    SeededTurn turn = seedCompletedTurnWithToolResults(baseline, 2);
    List<UUID> illegalPrefixes = new ArrayList<>();
    illegalPrefixes.add(turn.turnStartId());
    illegalPrefixes.add(turn.userEntryId());
    illegalPrefixes.add(turn.assistantId());
    illegalPrefixes.addAll(turn.toolResultIds());
    illegalPrefixes.addAll(seedStopTurn(baseline, false));

    for (UUID startEntryId : illegalPrefixes) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  runtime.acceptCommands(
                      entry(
                          baseline.sessionId(),
                          startEntryId,
                          List.of(userMessageCommand(TestIds.id(1), "hello"))),
                      AcceptancePreflight.IDENTITY));
      assertEquals(
          "start entry "
              + startEntryId
              + " is not a legal NEW_THREAD fork boundary (ROOT or a closed TURN_END)",
          error.getMessage());
      assertNoNewThreadFacts(TestIds.id(203));
    }

    // 源 Thread 的 head / version / commands / Work 完全不变。
    ThreadState source = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(baseline.rootEntryId(), source.headEntryId());
    assertEquals(0L, source.version());
    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(baseline.threadId()).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId())).isEmpty()));
  }

  /**
   * 合法 fork 边界只有 ROOT 与已闭合 TURN_END：ROOT、多工具结果后的 COMPLETED TURN_END（即最新边界）、FAILED 与 STOPPED
   * TURN_END 都可 fork；新 Thread head 直接指向边界，不复制 Entry。
   */
  @Test
  void entryForksAtRootAndClosedTurnEndBoundariesWithoutCopyingEntries() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    SeededTurn completed = seedCompletedTurnWithToolResults(baseline, 2);
    UUID failedBoundary = seedFailedTurn(baseline);
    UUID stoppedBoundary = seedStopTurn(baseline, true).getLast();
    long entriesBefore =
        store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId())).size();

    List<UUID> boundaries =
        List.of(baseline.rootEntryId(), completed.turnEndId(), failedBoundary, stoppedBoundary);
    for (UUID boundary : boundaries) {
      UUID threadId = UUID.randomUUID();
      AcceptedCommands result =
          runtime.acceptCommands(
              entry(
                  baseline.sessionId(),
                  boundary,
                  threadId,
                  List.of(userMessageCommand(TestIds.id(1), "hello"))),
              AcceptancePreflight.IDENTITY);
      assertFalse(result.replayed());
      ThreadState thread = store.transaction(tx -> tx.findThread(threadId).orElseThrow());
      assertEquals(boundary, thread.headEntryId());
      assertEquals(1L, thread.version());
      assertEquals(1, store.transaction(tx -> tx.loadCommandsByThread(threadId)).size());
    }

    // fork 不复制任何 Entry。
    assertEquals(
        entriesBefore,
        store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId())).size());
  }

  /** 正常关闭的 STOPPED 边界不是未闭合屏障：fork 到该边界照常成立，新 Thread head 直接指向它。 */
  @Test
  void entryForksAtAClosedStopBoundary() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    UUID boundary = seedStopTurn(baseline, true).getLast();

    AcceptedCommands result =
        runtime.acceptCommands(
            entry(
                baseline.sessionId(),
                boundary,
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(203)).orElseThrow());
    assertEquals(boundary, thread.headEntryId());
    assertEquals(1L, thread.version());
  }

  /** 在既有 Session 内直接写入一个 STOP barrier Turn（{@code closed=false} 时停在未闭合 prefix）。 */
  private List<UUID> seedStopTurn(HarnessRuntimeTestSupport.Baseline baseline, boolean closed) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  baseline.sessionId(),
                  baseline.rootEntryId(),
                  new TurnStartPayload(TurnStartReason.STOP, settings(), baseline.threadId()),
                  T0));
          UUID barrierId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  barrierId,
                  baseline.sessionId(),
                  turnStartId,
                  new AssistantErrorPayload(
                      new AssistantError(AssistantError.CANCELLED_CODE, "Cancelled by user"), null),
                  T0));
          if (!closed) {
            return List.of(turnStartId, barrierId);
          }
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  baseline.sessionId(),
                  barrierId,
                  new TurnEndPayload(
                      turnStartId,
                      TurnEndOutcome.STOPPED,
                      false,
                      TurnEndReason.USER_STOP,
                      TestIds.id(9)),
                  T0));
          return List.of(turnStartId, barrierId, turnEndId);
        });
  }

  private void assertNoNewThreadFacts(UUID threadId) {
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(threadId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.loadCommandsByThread(threadId).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)).isEmpty()));
  }

  private record SeededTurn(
      UUID turnStartId,
      UUID userEntryId,
      UUID assistantId,
      List<UUID> toolResultIds,
      UUID turnEndId) {}

  /**
   * 追加一个完整闭合 Turn：TURN_START(INPUT) → USER → ASSISTANT(toolCalls) → 各 TOOL 结果 →
   * TURN_END(COMPLETED)。
   */
  private SeededTurn seedCompletedTurnWithToolResults(
      HarnessRuntimeTestSupport.Baseline baseline, int toolCallCount) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  baseline.sessionId(),
                  baseline.rootEntryId(),
                  new TurnStartPayload(TurnStartReason.INPUT, settings(), baseline.threadId()),
                  T1));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  baseline.sessionId(),
                  turnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                      null,
                      null),
                  T1));
          List<String> toolCallIds = new ArrayList<>();
          for (int i = 0; i < toolCallCount; i++) {
            toolCallIds.add("call-" + i);
          }
          Entry assistant =
              HarnessRuntimeTestSupport.assistantEntry(
                  tx.nextId(),
                  baseline.sessionId(),
                  userEntryId,
                  T1,
                  toolCallIds.toArray(String[]::new));
          tx.insertEntry(assistant);
          UUID parentId = assistant.id();
          List<UUID> toolResultIds = new ArrayList<>();
          for (int i = 0; i < toolCallCount; i++) {
            UUID toolResultId = tx.nextId();
            tx.insertEntry(
                new Entry(
                    toolResultId,
                    baseline.sessionId(),
                    parentId,
                    toolResultPayload(tx.nextId(), assistant.id(), i, "call-" + i),
                    T1));
            toolResultIds.add(toolResultId);
            parentId = toolResultId;
          }
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  baseline.sessionId(),
                  parentId,
                  new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          return new SeededTurn(turnStartId, userEntryId, assistant.id(), toolResultIds, turnEndId);
        });
  }

  /** 追加一个 FAILED 闭合 Turn：TURN_START(INPUT) → USER → ASSISTANT_ERROR → TURN_END(FAILED)。 */
  private UUID seedFailedTurn(HarnessRuntimeTestSupport.Baseline baseline) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  baseline.sessionId(),
                  baseline.rootEntryId(),
                  new TurnStartPayload(TurnStartReason.INPUT, settings(), baseline.threadId()),
                  T2));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  userEntryId,
                  baseline.sessionId(),
                  turnStartId,
                  new MessagePayload(
                      new AgentMessage(
                          AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                      null,
                      null),
                  T2));
          UUID errorId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  errorId,
                  baseline.sessionId(),
                  userEntryId,
                  new AssistantErrorPayload(
                      new AssistantError("MODEL_ERROR", "model failed"), null),
                  T2));
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  baseline.sessionId(),
                  errorId,
                  new TurnEndPayload(
                      turnStartId, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
                  T2));
          return turnEndId;
        });
  }

  private static EntryPayload toolResultPayload(
      UUID toolInvocationId, UUID assistantEntryId, int callIndex, String toolCallId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            toolInvocationId,
            assistantEntryId,
            toolCallId,
            callIndex,
            ToolResultStatus.SUCCEEDED,
            false,
            null,
            null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  private void assertPreflightContractViolation(
      UUID sessionId, UUID threadId, AcceptancePreflight preflight, String expectedMessage) {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                runtime.acceptCommands(
                    newSession(
                        sessionId, threadId, List.of(userMessageCommand(TestIds.id(1), "hello"))),
                    preflight));
    assertEquals(expectedMessage, error.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(sessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(threadId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.loadCommandsByThread(threadId).isEmpty()));
  }
}
