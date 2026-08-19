package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.MATERIALIZATION_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.systemCustomMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * acceptCommands 的初始 target（NEW_SESSION / ENTRY）：同事务原子接受、materialization replay 与 ID reuse、batch
 * shape 与前缀 SYSTEM steering 规则。
 */
class HarnessRuntimeAcceptInitialTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand newSession(List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            TestIds.id(101), TestIds.id(102), settings(), null, true, commands));
  }

  /** 每次 shape 校验用例使用独立预分配 id，避免与上一用例的 materialization 冲突。 */
  private static AcceptCommandsCommand newSession(
      UUID sessionId, UUID threadId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            sessionId, threadId, settings(), null, false, commands));
  }

  private static AcceptCommandsCommand entry(
      UUID sessionId, UUID startEntryId, List<NewThreadCommand> commands) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.Entry(sessionId, startEntryId, TestIds.id(203), false, commands));
  }

  private static NewThreadCommand setAgent(UUID clientCommandId) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), clientCommandId);
  }

  @Test
  void newSessionAcceptsSessionRootThreadAndInitialCommandsAtomically() {
    AcceptCommandsResult result =
        runtime.acceptCommands(
            newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(TestIds.id(101), result.sessionId());
    assertEquals(TestIds.id(102), result.threadId());

    Session session = store.transaction(tx -> tx.findSession(TestIds.id(101)).orElseThrow());
    assertEquals(T0, session.createdAt());

    Entry root = store.transaction(tx -> tx.loadEntryPath(result.thread().headEntryId()).head());
    assertEquals(EntryType.ROOT, root.payload().type());
    assertEquals(TestIds.id(101), root.sessionId());
    assertEquals(settings(), ((RootPayload) root.payload()).settings());

    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow());
    assertEquals(TestIds.id(101), thread.sessionId());
    assertEquals(root.id(), thread.headEntryId());
    assertEquals(MATERIALIZATION_HASH.length(), thread.materializationHash().length());
    assertTrue(thread.yoloEnabled());
    // materialization hash 是服务端 deterministic 64 位小写 SHA-256。
    assertTrue(thread.materializationHash().matches("[0-9a-f]{64}"));
    // 初始 thread revision 0，accept 后恰好 +1；next sequence 从 1 起推进 1。
    assertEquals(1L, thread.revision());
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
                (tx, sessionId, commands) -> {
                  throw new IllegalStateException("preflight failed");
                }));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(TestIds.id(101)).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(102)).isEmpty()));
    assertTrue(
        store.<Boolean>transaction(tx -> tx.loadCommandsByThread(TestIds.id(102)).isEmpty()));
  }

  /**
   * exact materialization replay：同 session + 同 hash 返回现有接受事实（replayed=true），不写新行、不 bump revision。
   */
  @Test
  void newSessionExactReplayReturnsExistingAcceptanceFacts() {
    AcceptCommandsCommand command = newSession(List.of(userMessageCommand(TestIds.id(1), "hello")));
    AcceptCommandsResult first = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    AcceptCommandsResult replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);

    assertTrue(replay.replayed());
    assertEquals(first.sessionId(), replay.sessionId());
    assertEquals(first.threadId(), replay.threadId());
    assertEquals(first.thread().headEntryId(), replay.thread().headEntryId());
    assertEquals(first.thread().revision(), replay.thread().revision());
    // replay 不创建第二条命令。
    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(102)).orElseThrow());
    assertEquals(1L, thread.revision());
    assertEquals(2L, thread.nextCommandSequence());
  }

  /** 同 threadId + 不同 materialization（更改为不同文本）→ MATERIALIZATION_ID_REUSED。 */
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
    assertEquals(Reason.MATERIALIZATION_ID_REUSED, error.reason());
  }

  /** 同 threadId + 不同 Session → 仍属 ID reuse，绝不静默重建。 */
  @Test
  void newSessionThreadIdReusedAcrossSessionsConflicts() {
    runtime.acceptCommands(
        newSession(List.of(userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);
    AcceptCommandsCommand otherSession =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(
                TestIds.id(105),
                TestIds.id(102),
                settings(),
                null,
                false,
                List.of(userMessageCommand(TestIds.id(1), "hello"))));
    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.acceptCommands(otherSession, AcceptancePreflight.IDENTITY));
    assertEquals(Reason.MATERIALIZATION_ID_REUSED, error.reason());
  }

  /** 初始 batch shape：恰一条 user-like message 结尾；允许固定顺序 SET_* 前缀；SYSTEM CUSTOM_MESSAGE 只允许在前缀。 */
  @Test
  void initialBatchShapeRulesAreEnforced() {
    // 合法：SET_AGENT 前缀 + 单条 user message。
    runtime.acceptCommands(
        newSession(
            TestIds.id(110),
            TestIds.id(111),
            List.of(setAgent(TestIds.id(2)), userMessageCommand(TestIds.id(1), "hello"))),
        AcceptancePreflight.IDENTITY);

    // 非法：没有 user-like message。
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                newSession(TestIds.id(112), TestIds.id(113), List.of(setAgent(TestIds.id(3)))),
                AcceptancePreflight.IDENTITY));

    // 非法：SET_* 出现在消息之后（user-like 必须恰好一条且结尾）。
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                newSession(
                    TestIds.id(114),
                    TestIds.id(115),
                    List.of(userMessageCommand(TestIds.id(4), "hello"), setAgent(TestIds.id(5)))),
                AcceptancePreflight.IDENTITY));

    // 合法：前缀 SYSTEM steering + 单条 user message。
    AcceptCommandsResult withSystem =
        runtime.acceptCommands(
            newSession(
                TestIds.id(116),
                TestIds.id(117),
                List.of(
                    systemCustomMessageCommand(TestIds.id(6), "steer"),
                    userMessageCommand(TestIds.id(7), "hello"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(withSystem.replayed());
  }

  /** ENTRY：锁 session、验证 start Entry 同 Session、插入 Thread+Commands+Work；不复制 Entry。 */
  @Test
  void entryAcceptsNewThreadUnderExistingSessionWithoutCopyingEntries() {
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    AcceptCommandsResult result =
        runtime.acceptCommands(
            entry(
                baseline.sessionId(),
                baseline.rootEntryId(),
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(result.replayed());
    assertEquals(baseline.sessionId(), result.sessionId());
    ThreadState thread = store.transaction(tx -> tx.findThread(TestIds.id(203)).orElseThrow());
    // head 直接指向既有 start Entry：不复制 Entry，Session 内仍只有 ROOT。
    assertEquals(baseline.rootEntryId(), thread.headEntryId());
    List<Entry> entries = store.transaction(tx -> tx.loadEntriesBySessionId(baseline.sessionId()));
    assertEquals(1, entries.size());
    assertEquals(1L, thread.revision());
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
    AcceptCommandsResult first = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    AcceptCommandsResult replay = runtime.acceptCommands(command, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertEquals(first.thread().revision(), replay.thread().revision());
    assertEquals(first.thread().nextCommandSequence(), replay.thread().nextCommandSequence());
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
              tx.insertSession(new Session(sessionId, T0));
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
}
