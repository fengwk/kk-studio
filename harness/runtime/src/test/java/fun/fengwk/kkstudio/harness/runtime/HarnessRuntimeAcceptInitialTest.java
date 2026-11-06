package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.systemReminderCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.RenameThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.history.ForkMode;
import fun.fengwk.kkstudio.harness.api.history.ForkPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCapabilities;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

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

  private static void assertConflict(HarnessConflictReason reason, DomainException error) {
    assertEquals(DomainErrorCode.CONFLICT, error.code());
    assertEquals(reason.name(), error.reason());
  }

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
    return entry(sessionId, startEntryId, threadId, "branch", commands);
  }

  private static AcceptCommandsCommand entry(
      UUID sessionId,
      UUID startEntryId,
      UUID threadId,
      String name,
      List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewThread(sessionId, startEntryId, threadId, name, false),
        commands);
  }

  private static NewThreadCommand setAgent(UUID idempotencyKey) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), idempotencyKey);
  }

  /** 同身份重放在写入之前返回；重命名后也不能被名称索引误拒绝。 */
  @Test
  void sameNameRejectsAnotherRootButExactCreationReplaySurvivesRename() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptCommandsCommand command =
        entry(
            baseline.sessionId(),
            baseline.rootEntryId(),
            TestIds.id(203),
            List.of(userMessageCommand(TestIds.id(1), "hello")));
    AcceptedCommands accepted = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    runtime.renameThread(new RenameThreadCommand(accepted.thread().id(), "renamed"));
    AcceptedCommands replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(accepted.thread().id(), replay.thread().id());
    assertEquals("renamed", replay.thread().name());
    DomainException conflict =
        assertThrows(
            DomainException.class,
            () ->
                runtime.acceptCommands(
                    entry(
                        baseline.sessionId(),
                        baseline.rootEntryId(),
                        TestIds.id(204),
                        "renamed",
                        List.of(userMessageCommand(TestIds.id(2), "other"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(DomainErrorCode.CONFLICT, conflict.code());
    assertEquals("THREAD_NAME_CONFLICT", conflict.reason());
    assertNoNewThreadFacts(TestIds.id(204));
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

    Session session = store.transaction(tx -> tx.sessions().find(TestIds.id(101)).orElseThrow());
    assertEquals(T0, session.createdAt());

    Entry root =
        store.transaction(tx -> tx.entries().loadPath(result.thread().headEntryId()).head());
    assertEquals(EntryType.ROOT, root.payload().type());
    assertEquals(TestIds.id(101), root.sessionId());
    assertEquals(settings(), ((RootPayload) root.payload()).settings());

    ThreadState thread = store.transaction(tx -> tx.threads().find(TestIds.id(102)).orElseThrow());
    assertEquals(TestIds.id(101), thread.sessionId());
    assertEquals(root.id(), thread.headEntryId());
    assertEquals(CREATION_REQUEST_HASH.length(), thread.creationRequestHash().length());
    assertTrue(thread.yoloEnabled());
    // creation request hash 是服务端 deterministic 64 位小写 SHA-256。
    assertTrue(thread.creationRequestHash().matches("[0-9a-f]{64}"));
    // 初始 thread version 0，accept 后恰好 +1；next sequence 从 1 起推进 1。
    assertEquals(1L, thread.version());
    assertEquals(2L, thread.nextCommandSequence());

    List<ThreadCommand> commands =
        store.transaction(tx -> loadCommandsByThread(tx, TestIds.id(102)));
    assertEquals(1, commands.size());
    assertEquals(1L, commands.getFirst().sequence());
    assertEquals(ThreadCommandState.QUEUED, commands.getFirst().state());

    Work work =
        store.transaction(
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, TestIds.id(102)))
                    .orElseThrow());
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
                (session, commands, copiedEntries) -> {
                  assertTrue(copiedEntries.isEmpty());
                  throw new IllegalStateException("preflight failed");
                }));
    assertTrue(store.<Boolean>transaction(tx -> tx.sessions().find(TestIds.id(101)).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(TestIds.id(102)).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(tx -> loadCommandsByThread(tx, TestIds.id(102)).isEmpty()));
  }

  @Test
  void newSessionRejectsMalformedPreflightResultsAndRollsBack() {
    // Preflight 是事务内初始创建边界：null、数量变化或幂等键变化都必须 fail closed 且不留下部分事实。
    assertPreflightContractViolation(
        TestIds.id(130),
        TestIds.id(131),
        (session, commands, copiedEntries) -> null,
        "command preflight returned null");
    assertPreflightContractViolation(
        TestIds.id(132),
        TestIds.id(133),
        (session, commands, copiedEntries) -> List.of(),
        "command preflight must return exactly 1 commands, got 0");
    assertPreflightContractViolation(
        TestIds.id(134),
        TestIds.id(135),
        (session, commands, copiedEntries) -> {
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
    ThreadState thread = store.transaction(tx -> tx.threads().find(TestIds.id(102)).orElseThrow());
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
            new AcceptCommandsTarget.Thread(TestIds.id(102)),
            List.of(userMessageCommand(TestIds.id(2), "c"))),
        AcceptancePreflight.IDENTITY);
    ThreadState before = store.transaction(tx -> tx.threads().find(TestIds.id(102)).orElseThrow());
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
    assertEquals(before, store.transaction(tx -> tx.threads().find(TestIds.id(102)).orElseThrow()));
  }

  /** 同 threadId + 不同初始创建请求 → THREAD_ID_REUSED。 */
  @Test
  void newSessionThreadIdReusedWithDifferentHashConflicts() {
    runtime.acceptCommands(
        newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                runtime.acceptCommands(
                    newSession(List.of(userMessageCommand(TestIds.id(1), "different"))),
                    AcceptancePreflight.IDENTITY));
    assertConflict(HarnessConflictReason.THREAD_ID_REUSED, error);
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
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> runtime.acceptCommands(otherSession, AcceptancePreflight.IDENTITY));
    assertConflict(HarnessConflictReason.THREAD_ID_REUSED, error);
  }

  /**
   * 测试意图：initial creation replay 对 durable 初始命令做 fail-closed 校验——缺失、hash 不同或 sequence 不连续都拒绝，
   * 绝不返回不完整的接受事实。
   */
  @Test
  void initialReplayRejectsCorruptedDurableCommands() {
    List<NewThreadCommand> requests =
        List.of(setAgent(TestIds.id(1)), userMessageCommand(TestIds.id(2), "hello"));

    AcceptCommandsCommand partial = newSession(TestIds.id(160), TestIds.id(161), requests);
    seedInitialThread(partial, List.of(durable(TestIds.id(161), 1, requests.getFirst())));
    DomainException missing =
        assertThrows(
            DomainException.class,
            () -> runtime.acceptCommands(partial, AcceptancePreflight.IDENTITY));
    assertConflict(HarnessConflictReason.PARTIAL_COMMAND_REPLAY, missing);

    AcceptCommandsCommand mismatched = newSession(TestIds.id(162), TestIds.id(163), requests);
    seedInitialThread(
        mismatched,
        List.of(
            durable(TestIds.id(163), 1, requests.getFirst()),
            durable(TestIds.id(163), 2, requests.getLast(), "f".repeat(64))));
    DomainException hash =
        assertThrows(
            DomainException.class,
            () -> runtime.acceptCommands(mismatched, AcceptancePreflight.IDENTITY));
    assertConflict(HarnessConflictReason.IDEMPOTENCY_KEY_REUSED, hash);

    AcceptCommandsCommand nonContiguous = newSession(TestIds.id(164), TestIds.id(165), requests);
    seedInitialThread(
        nonContiguous,
        List.of(
            durable(TestIds.id(165), 2, requests.getFirst()),
            durable(TestIds.id(165), 3, requests.getLast())));
    DomainException order =
        assertThrows(
            DomainException.class,
            () -> runtime.acceptCommands(nonContiguous, AcceptancePreflight.IDENTITY));
    assertConflict(HarnessConflictReason.COMMAND_REPLAY_ORDER_MISMATCH, order);
  }

  /** 测试意图：fork 只允许来源 Driver 支持 fork 且保持同一 Driver；同 Driver 的 SET_EXECUTION 仍被接受。 */
  @Test
  void forkRequiresSourceDriverCapabilityAndKeepsItsDriver() {
    AcceptedCommands root =
        runtime.acceptCommands(
            newSession(
                TestIds.id(170),
                TestIds.id(171),
                List.of(userMessageCommand(TestIds.id(1), "root"))),
            AcceptancePreflight.IDENTITY);

    IllegalArgumentException changed =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime.acceptCommands(
                    entry(
                        TestIds.id(170),
                        root.rootEntry().id(),
                        TestIds.id(172),
                        List.of(
                            new NewThreadCommand(
                                new SetExecutionCommandPayload(
                                    new ExecutionSelection("builtin", "{}")),
                                TestIds.id(2)),
                            userMessageCommand(TestIds.id(3), "branch"))),
                    AcceptancePreflight.IDENTITY));
    assertTrue(changed.getMessage().contains("driver cannot be changed in fork"));

    AcceptedCommands sameDriver =
        runtime.acceptCommands(
            entry(
                TestIds.id(170),
                root.rootEntry().id(),
                TestIds.id(173),
                List.of(
                    new NewThreadCommand(
                        new SetExecutionCommandPayload(new ExecutionSelection("fake", "{}")),
                        TestIds.id(4)),
                    userMessageCommand(TestIds.id(5), "branch"))),
            AcceptancePreflight.IDENTITY);
    assertEquals(TestIds.id(173), sameDriver.thread().id());

    HarnessRuntime noForkRuntime =
        HarnessRuntimeTestSupport.runtime(
            store,
            Clock.fixed(T0, ZoneOffset.UTC),
            new FakeAgentDriver("fake").withCapabilities(new DriverCapabilities(false, true)),
            HarnessRuntimeTestSupport.DEFAULT_COMPACTION_PROVIDER);
    AcceptedCommands noForkRoot =
        noForkRuntime.acceptCommands(
            newSession(
                TestIds.id(174),
                TestIds.id(175),
                List.of(userMessageCommand(TestIds.id(6), "root"))),
            AcceptancePreflight.IDENTITY);
    IllegalArgumentException unsupported =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                noForkRuntime.acceptCommands(
                    entry(
                        TestIds.id(174),
                        noForkRoot.rootEntry().id(),
                        TestIds.id(176),
                        List.of(userMessageCommand(TestIds.id(7), "branch"))),
                    AcceptancePreflight.IDENTITY));
    assertTrue(unsupported.getMessage().contains("fork is unsupported"));
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

  /**
   * SET_* 前缀固定顺序必须是 SET_AGENT -&gt; SET_EXECUTION -&gt; SET_ENVIRONMENT：用正反请求证明该顺序（正向通过 / 反向 IAE）。
   */
  @Test
  void setPrefixOrderRequiresAgentBeforeExecutionBeforeEnvironment() {
    // 合法：全前缀 + 单条 user message。
    AcceptedCommands result =
        runtime.acceptCommands(
            newSession(
                TestIds.id(120),
                TestIds.id(121),
                List.of(
                    setAgent(TestIds.id(2)),
                    new NewThreadCommand(
                        new SetExecutionCommandPayload(new ExecutionSelection("fake", "{}")),
                        TestIds.id(3)),
                    new NewThreadCommand(new SetEnvironmentCommandPayload("local"), TestIds.id(5)),
                    userMessageCommand(TestIds.id(4), "hi"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(result.replayed());
    // 非法：SET_AGENT 出现在 SET_EXECUTION 之后（顺序不变量拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(122),
                    TestIds.id(123),
                    List.of(
                        new NewThreadCommand(
                            new SetExecutionCommandPayload(new ExecutionSelection("fake", "{}")),
                            TestIds.id(2)),
                        setAgent(TestIds.id(1)),
                        userMessageCommand(TestIds.id(3), "hi"))),
                AcceptancePreflight.IDENTITY));
    // 非法：SET_ENVIRONMENT 出现在 SET_EXECUTION 之前（顺序不变量拒绝）。
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
                            new SetExecutionCommandPayload(new ExecutionSelection("fake", "{}")),
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

  /**
   * NEW_THREAD：KEY SHARE 锁 session、验证 start Entry 同 Session、插入 FORK 事实节点 +
   * Thread+Commands+Work；不复制共享前缀。
   */
  @Test
  void entryAcceptsNewThreadUnderExistingSessionAppendingForkFactOnly() {
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
    ThreadState thread = store.transaction(tx -> tx.threads().find(TestIds.id(203)).orElseThrow());
    // head 是 FORK 事实节点：parent 指向既有 start Entry，不复制共享前缀。
    Entry fork = store.transaction(tx -> tx.entries().find(thread.headEntryId())).orElseThrow();
    ForkPayload forkPayload = assertInstanceOf(ForkPayload.class, fork.payload());
    assertEquals(ForkMode.BRANCH, forkPayload.mode());
    assertEquals(baseline.rootEntryId(), forkPayload.sourceEntryId());
    assertEquals(baseline.rootEntryId(), fork.parentEntryId());
    List<Entry> entries = store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId()));
    assertEquals(2, entries.size());
    assertEquals(1L, thread.version());
    assertEquals(2L, thread.nextCommandSequence());
    assertTrue(
        store
            .<Boolean>transaction(
                tx ->
                    tx.work()
                        .find(new WorkTarget(WorkTargetType.THREAD, TestIds.id(203)))
                        .isPresent())
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
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                runtime.acceptCommands(
                    entry(
                        missingSessionId,
                        TestIds.id(211),
                        List.of(userMessageCommand(TestIds.id(1), "hello"))),
                    AcceptancePreflight.IDENTITY));
    assertEquals(DomainErrorCode.NOT_FOUND, error.code());
    assertEquals("session", error.resource());
    assertEquals("session " + missingSessionId + " does not exist", error.getMessage());
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(TestIds.id(203)).isEmpty()));
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
              tx.sessions().insert(new Session(sessionId, "session-" + sessionId, T0));
              tx.entries()
                  .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(settings()), T0));
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
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(TestIds.id(203)).isEmpty()));
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
    ThreadState source =
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());
    assertEquals(baseline.rootEntryId(), source.headEntryId());
    assertEquals(0L, source.version());
    assertTrue(
        store.<Boolean>transaction(tx -> loadCommandsByThread(tx, baseline.threadId()).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.work()
                    .find(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .isEmpty()));
  }

  /**
   * 合法 fork 边界只有 ROOT 与已闭合 TURN_END：ROOT、多工具结果后的 COMPLETED TURN_END（即最新边界）、FAILED 与 STOPPED
   * TURN_END 都可 fork；每个 fork 只追加一个 FORK 事实节点（parent 指向该边界），绝不复制共享前缀。
   */
  @Test
  void entryForksAtRootAndClosedTurnEndBoundariesAppendingForkFacts() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    SeededTurn completed = seedCompletedTurnWithToolResults(baseline, 2);
    UUID failedBoundary = seedFailedTurn(baseline);
    UUID stoppedBoundary = seedStopTurn(baseline, true).getLast();
    long entriesBefore =
        store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId())).size();

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
                  "branch-" + boundary,
                  List.of(userMessageCommand(TestIds.id(1), "hello"))),
              AcceptancePreflight.IDENTITY);
      assertFalse(result.replayed());
      ThreadState thread = store.transaction(tx -> tx.threads().find(threadId).orElseThrow());
      Entry fork = store.transaction(tx -> tx.entries().find(thread.headEntryId())).orElseThrow();
      ForkPayload forkPayload = assertInstanceOf(ForkPayload.class, fork.payload());
      assertEquals(ForkMode.BRANCH, forkPayload.mode());
      assertEquals(boundary, forkPayload.sourceEntryId());
      assertEquals(boundary, fork.parentEntryId());
      assertEquals(1L, thread.version());
      assertEquals(1, store.transaction(tx -> loadCommandsByThread(tx, threadId)).size());
    }

    // 每次 fork 只追加一个 FORK 事实节点，绝不复制共享前缀。
    assertEquals(
        entriesBefore + boundaries.size(),
        store.transaction(tx -> tx.entries().loadBySession(baseline.sessionId())).size());
  }

  /** 正常关闭的 STOPPED 边界不是未闭合屏障：fork 到该边界照常成立，并追加 FORK 事实节点。 */
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
    ThreadState thread = store.transaction(tx -> tx.threads().find(TestIds.id(203)).orElseThrow());
    Entry fork = store.transaction(tx -> tx.entries().find(thread.headEntryId())).orElseThrow();
    ForkPayload forkPayload = assertInstanceOf(ForkPayload.class, fork.payload());
    assertEquals(ForkMode.BRANCH, forkPayload.mode());
    assertEquals(boundary, forkPayload.sourceEntryId());
    assertEquals(boundary, fork.parentEntryId());
    assertEquals(1L, thread.version());
  }

  /** 在既有 Session 内直接写入一个 STOP barrier Turn（{@code closed=false} 时停在未闭合 prefix）。 */
  private List<UUID> seedStopTurn(HarnessRuntimeTestSupport.Baseline baseline, boolean closed) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      new TurnStartPayload(TurnStartReason.STOP, settings(), baseline.threadId()),
                      T0));
          UUID barrierId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      barrierId,
                      baseline.sessionId(),
                      turnStartId,
                      new AssistantErrorPayload(
                          new AssistantError(AssistantError.CANCELLED_CODE, "Cancelled by user"),
                          null),
                      T0));
          if (!closed) {
            return List.of(turnStartId, barrierId);
          }
          UUID turnEndId = tx.nextId();
          tx.entries()
              .insert(
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

  private void seedInitialThread(AcceptCommandsCommand command, List<ThreadCommand> commands) {
    AcceptCommandsTarget.NewRootSession target =
        (AcceptCommandsTarget.NewRootSession) command.target();
    String creationRequestHash =
        ThreadCreationRequestHash.forNewSession(
            target.sessionId(),
            target.threadId(),
            target.rootSettings(),
            null,
            target.yoloEnabled(),
            SpawnTrigger.USER,
            ThreadConstraints.NONE,
            command.commands());
    store.transaction(
        tx -> {
          UUID rootEntryId = tx.nextId();
          tx.sessions().insert(new Session(target.sessionId(), "session", T0));
          tx.entries()
              .insert(
                  new Entry(
                      rootEntryId,
                      target.sessionId(),
                      null,
                      new RootPayload(target.rootSettings()),
                      T0));
          tx.threads()
              .insert(
                  new ThreadState(
                      target.threadId(),
                      target.sessionId(),
                      null,
                      rootEntryId,
                      creationRequestHash,
                      "main",
                      target.threadId(),
                      target.yoloEnabled(),
                      SpawnTrigger.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0,
                      T0,
                      T0));
          tx.threads().lock(target.threadId());
          tx.commands().insert(commands);
          return null;
        });
  }

  private static ThreadCommand durable(UUID threadId, long sequence, NewThreadCommand request) {
    return durable(threadId, sequence, request, request.requestHash());
  }

  private static ThreadCommand durable(
      UUID threadId, long sequence, NewThreadCommand request, String requestHash) {
    return new ThreadCommand(
        threadId,
        sequence,
        request.payload(),
        request.idempotencyKey(),
        requestHash,
        null,
        null,
        null,
        T0);
  }

  private void assertNoNewThreadFacts(UUID threadId) {
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(threadId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> loadCommandsByThread(tx, threadId).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.work().find(new WorkTarget(WorkTargetType.THREAD, threadId)).isEmpty()));
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
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      new TurnStartPayload(TurnStartReason.INPUT, settings(), baseline.threadId()),
                      T1));
          UUID userEntryId = tx.nextId();
          tx.entries()
              .insert(
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
          tx.entries().insert(assistant);
          UUID parentId = assistant.id();
          List<UUID> toolResultIds = new ArrayList<>();
          for (int i = 0; i < toolCallCount; i++) {
            UUID toolResultId = tx.nextId();
            tx.entries()
                .insert(
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
          tx.entries()
              .insert(
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
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      new TurnStartPayload(TurnStartReason.INPUT, settings(), baseline.threadId()),
                      T2));
          UUID userEntryId = tx.nextId();
          tx.entries()
              .insert(
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
          tx.entries()
              .insert(
                  new Entry(
                      errorId,
                      baseline.sessionId(),
                      userEntryId,
                      new AssistantErrorPayload(
                          new AssistantError("MODEL_ERROR", "model failed"), null),
                      T2));
          UUID turnEndId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnEndId,
                      baseline.sessionId(),
                      errorId,
                      new TurnEndPayload(
                          turnStartId,
                          TurnEndOutcome.FAILED,
                          false,
                          TurnEndReason.TURN_FAILED,
                          null),
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
    assertTrue(store.<Boolean>transaction(tx -> tx.sessions().find(sessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(threadId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> loadCommandsByThread(tx, threadId).isEmpty()));
  }
}
