package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;

import java.util.ArrayList;
import java.util.List;

/** 根控制面 API 的 Value-record 校验分支、类型化异常与最终 record 形状。 */
class HarnessRuntimeApiRecordsTest {

  @Test
  void manualCompactionRecordsValidateTheirShapes() {
    assertThrows(NullPointerException.class, () -> new CompactThreadCommand(null, 0));
    assertThrows(IllegalArgumentException.class, () -> new CompactThreadCommand(TestIds.id(1), -1));
    assertTrue(ManualCompactionAvailability.enabled().available());
    ManualCompactionAvailability disabled =
        ManualCompactionAvailability.disabled(
            ManualCompactionAvailability.DisabledReason.THREAD_BUSY);
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY, disabled.disabledReason());
    assertThrows(NullPointerException.class, () -> ManualCompactionAvailability.disabled(null));

    ThreadState thread =
        HarnessRuntimeTestSupport.thread(TestIds.id(1), TestIds.id(2), TestIds.id(3));
    CompactThreadResult result = new CompactThreadResult(thread, TestIds.id(4), null);
    assertEquals(thread, result.thread());
    assertThrows(
        NullPointerException.class, () -> new CompactThreadResult(null, TestIds.id(4), null));
  }

  @Test
  void acceptCommandsTargetsValidateTheirShapes() {
    // NEW_SESSION 必需字段。
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.NewRootSession(null, TestIds.id(2), settings(), false));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.NewRootSession(TestIds.id(1), null, settings(), false));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.NewRootSession(TestIds.id(1), TestIds.id(2), null, false));

    // NEW_THREAD 必需字段（含必填 threadName）。
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewThread(
                null, TestIds.id(3), TestIds.id(4), "branch", false));
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewThread(
                TestIds.id(1), null, TestIds.id(4), "branch", false));
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewThread(
                TestIds.id(1), TestIds.id(3), TestIds.id(4), null, false));
    // blank threadName 是非法名称（经 Names.normalize 拒绝）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsTarget.NewThread(
                TestIds.id(1), TestIds.id(3), TestIds.id(4), "   ", false));

    // THREAD cursor 必须为正。
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptCommandsTarget.Thread(TestIds.id(1), TestIds.id(2), 0));
    assertThrows(
        NullPointerException.class, () -> new AcceptCommandsTarget.Thread(null, TestIds.id(2), 1));
    assertThrows(
        NullPointerException.class, () -> new AcceptCommandsTarget.Thread(TestIds.id(1), null, 1));

    // target 只保存定位 / materialization 语义，不携带 commands。
    AcceptCommandsTarget.NewRootSession target =
        new AcceptCommandsTarget.NewRootSession(TestIds.id(1), TestIds.id(2), settings(), true);
    assertEquals(TestIds.id(1), target.sessionId());
    assertEquals(TestIds.id(2), target.threadId());
    assertTrue(target.yoloEnabled());
  }

  @Test
  void acceptCommandsCommandRequiresATargetAndCommandBatch() {
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsCommand(null, List.of(userMessageCommand(TestIds.id(1), "a"))));
    // 空命令 batch 被拒。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(TestIds.id(1), TestIds.id(2), 5), List.of()));
    // 重复 idempotencyKey 被拒。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(TestIds.id(1), TestIds.id(2), 5),
                List.of(
                    userMessageCommand(TestIds.id(1), "a"),
                    userMessageCommand(TestIds.id(1), "b"))));
    // 防御性拷贝：外部 List 变更不影响已构造 command。
    List<NewThreadCommand> mutable =
        new ArrayList<>(List.of(userMessageCommand(TestIds.id(1), "a")));
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(TestIds.id(1), TestIds.id(2), 5), mutable);
    mutable.clear();
    assertEquals(1, command.commands().size());
    AcceptCommandsTarget.Thread target = (AcceptCommandsTarget.Thread) command.target();
    assertEquals(5L, target.expectedNextCommandSequence());
    assertEquals(TestIds.id(2), target.expectedHeadEntryId());
  }

  @Test
  void acceptedCommandsValidatesConsistencyAndCopiesCommands() {
    Session session = new Session(TestIds.id(1), "session-00000000000000000000000000000001", T0);
    Entry root = new Entry(TestIds.id(2), TestIds.id(1), null, new RootPayload(settings()), T0);
    ThreadState thread =
        HarnessRuntimeTestSupport.thread(TestIds.id(3), TestIds.id(1), TestIds.id(2));
    ThreadCommand inserted =
        new ThreadCommand(
            thread.id(),
            1,
            userMessagePayload("a"),
            TestIds.id(1),
            ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("a")),
            null,
            null,
            null,
            T0);
    List<ThreadCommand> commands = new ArrayList<>(List.of(inserted));
    AcceptedCommands result = new AcceptedCommands(session, root, thread, commands, false, false);
    commands.clear();
    // 防御性拷贝：外部 List 变更不影响结果。
    assertEquals(1, result.acceptedCommands().size());
    assertEquals(session, result.session());
    assertEquals(root, result.rootEntry());
    assertEquals(thread, result.thread());
    assertThrows(
        NullPointerException.class,
        () -> new AcceptedCommands(null, root, thread, List.of(inserted), false, false));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptedCommands(session, null, thread, List.of(inserted), false, false));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptedCommands(session, root, null, List.of(inserted), false, false));
    // 空 acceptedCommands 被拒。
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptedCommands(session, root, thread, List.of(), false, false));
    // 不属于返回 thread 的 command 被拒。
    ThreadCommand foreign =
        new ThreadCommand(
            TestIds.id(99),
            1,
            userMessagePayload("a"),
            TestIds.id(1),
            ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("a")),
            null,
            null,
            null,
            T0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptedCommands(session, root, thread, List.of(foreign), false, false));
    // root 必须属于结果 session。
    Session other = new Session(TestIds.id(50), "session-00000000000000000000000000000050", T0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptedCommands(other, root, thread, List.of(inserted), false, false));
    // root 必须是 ROOT payload（非 ROOT entry 被拒）。
    Entry nonRoot =
        HarnessRuntimeTestSupport.userMessageEntry(TestIds.id(5), TestIds.id(1), TestIds.id(2), T0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptedCommands(session, nonRoot, thread, List.of(inserted), false, false));
    // thread 必须属于结果 session。
    ThreadState foreignSessionThread =
        HarnessRuntimeTestSupport.thread(TestIds.id(3), TestIds.id(51), TestIds.id(2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptedCommands(
                session, root, foreignSessionThread, List.of(inserted), false, false));
    // acceptedCommands sequence 必须严格递增。
    ThreadCommand later =
        new ThreadCommand(
            thread.id(),
            5,
            userMessagePayload("later"),
            TestIds.id(2),
            ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("later")),
            null,
            null,
            null,
            T0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptedCommands(session, root, thread, List.of(later, inserted), false, false));
  }

  /** CancelledThreadInput：sequence 必须为正，identity 与 payload 非空。 */
  @Test
  void cancelledThreadInputValidatesShape() {
    ThreadCommandPayload payload = userMessagePayload("hi");
    CancelledThreadInput input = new CancelledThreadInput(3L, TestIds.id(7), payload);
    assertEquals(3L, input.sequence());
    assertEquals(TestIds.id(7), input.idempotencyKey());
    assertEquals(payload, input.payload());
    assertThrows(
        IllegalArgumentException.class, () -> new CancelledThreadInput(0L, TestIds.id(7), payload));
    assertThrows(NullPointerException.class, () -> new CancelledThreadInput(1L, null, payload));
    assertThrows(
        NullPointerException.class, () -> new CancelledThreadInput(1L, TestIds.id(7), null));
  }

  /** StopResult / StoppedThreadReceipt 最终形状：replayed + 权威 Thread + 每节点持久回执。 */
  @Test
  void stopResultFinalShapeRoundTrips() {
    ThreadState thread = storeThread();
    StoppedThreadReceipt receipt =
        new StoppedThreadReceipt(
            thread.id(),
            TestIds.id(2),
            TestIds.id(3),
            1,
            List.of(new CancelledThreadInput(1L, TestIds.id(9), userMessagePayload("x"))));
    StopResult stopped = new StopResult(false, thread, List.of(receipt));
    assertFalse(stopped.replayed());
    assertEquals(thread.id(), stopped.thread().id());
    assertEquals(TestIds.id(2), stopped.stoppedThreads().getFirst().stopRequestId());
    assertEquals(1, stopped.stoppedThreads().getFirst().cancelledCommandCount());

    StopResult replay = new StopResult(true, thread, List.of());
    assertTrue(replay.replayed());

    // cancelledCommandCount 必须非负。
    assertThrows(
        IllegalArgumentException.class,
        () -> new StoppedThreadReceipt(thread.id(), TestIds.id(2), null, -1, List.of()));
    // cancelledInputs 必须 sequence 严格递增（不变量拒绝乱序/非单调）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StoppedThreadReceipt(
                thread.id(),
                TestIds.id(2),
                null,
                2,
                List.of(
                    new CancelledThreadInput(2L, TestIds.id(9), userMessagePayload("a")),
                    new CancelledThreadInput(1L, TestIds.id(9), userMessagePayload("b")))));
    // 输入数量不得超过 cancelledCommandCount。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StoppedThreadReceipt(
                thread.id(),
                TestIds.id(2),
                null,
                0,
                List.of(new CancelledThreadInput(1L, TestIds.id(9), userMessagePayload("a")))));
  }

  /** ThreadState 最终形状：creationRequestHash/sessionId 不可变，任何可见变更 version 严格 +1。 */
  @Test
  void threadStateFinalShapeIsImmutableAndHashGuarded() {
    ThreadState thread = storeThread();
    // 非法 hash 被拒。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadState(
                thread.id(),
                thread.sessionId(),
                thread.parentThreadId(),
                thread.headEntryId(),
                "not-a-hash",
                thread.name(),
                ThreadYoloPolicy.root(false),
                thread.executionControl(),
                0L,
                1,
                0,
                thread.createdAt(),
                thread.updatedAt()));
    // exact replay 被接受，身份字段变更被拒。
    ThreadState equal =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.parentThreadId(),
            thread.headEntryId(),
            thread.creationRequestHash(),
            thread.name(),
            thread.yoloPolicy(),
            thread.executionControl(),
            0L,
            1,
            0,
            thread.createdAt(),
            thread.updatedAt());
    ThreadState.validateTransition(thread, equal);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                thread,
                new ThreadState(
                    thread.id(),
                    TestIds.id(99),
                    thread.parentThreadId(),
                    thread.headEntryId(),
                    thread.creationRequestHash(),
                    thread.name(),
                    thread.yoloPolicy(),
                    thread.executionControl(),
                    0L,
                    1,
                    0,
                    thread.createdAt(),
                    thread.updatedAt())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                thread,
                new ThreadState(
                    thread.id(),
                    thread.sessionId(),
                    thread.parentThreadId(),
                    thread.headEntryId(),
                    "9999999999999999999999999999999999999999999999999999999999999999",
                    thread.name(),
                    thread.yoloPolicy(),
                    thread.executionControl(),
                    0L,
                    1,
                    0,
                    thread.createdAt(),
                    thread.updatedAt())));
    // 任何可见变更（YOLO/head/seq）都必须 version +1；setRootYolo 自身恰好 bump 一次是可接受的精确迁移。
    ThreadState bumped = thread.setRootYolo(true, T0.plusMillis(1));
    assertEquals(1L, bumped.version());
    ThreadState.validateTransition(thread, bumped);
    // 显式构造 yolo 变更但 version 未 +1 的候选必须被拒。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                thread,
                new ThreadState(
                    thread.id(),
                    thread.sessionId(),
                    thread.parentThreadId(),
                    thread.headEntryId(),
                    thread.creationRequestHash(),
                    thread.name(),
                    ThreadYoloPolicy.root(true),
                    thread.executionControl(),
                    0L,
                    1,
                    0,
                    thread.createdAt(),
                    T0.plusMillis(1))));
    assertEquals(thread.sessionId(), bumped.sessionId());
    assertEquals(thread.creationRequestHash(), bumped.creationRequestHash());
  }

  /** ThreadCommand 最终形状：stopRequestId 取消回单与 QUEUED→CANCELLED 迁移。 */
  @Test
  void threadCommandFinalShapePairsCancelReceipt() {
    ThreadCommand queued =
        new ThreadCommand(
            TestIds.id(1),
            1,
            userMessagePayload("a"),
            TestIds.id(2),
            ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("a")),
            null,
            null,
            null,
            T0);
    assertEquals(ThreadCommandState.QUEUED, queued.state());
    assertNotEquals(queued.requestHash(), "");

    ThreadCommand cancelled = queued.cancel(TestIds.id(9), T0.plusMillis(1));
    assertEquals(ThreadCommandState.CANCELLED, cancelled.state());
    assertEquals(TestIds.id(9), cancelled.stopRequestId());
    assertEquals(T0.plusMillis(1), cancelled.cancelledAt());

    // 取消与消费 marker 互斥。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                TestIds.id(1),
                1,
                userMessagePayload("a"),
                TestIds.id(2),
                queued.requestHash(),
                TestIds.id(3),
                TestIds.id(9),
                T0.plusMillis(1),
                T0));
    // stopRequestId 必须与 cancelledAt 成对。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                TestIds.id(1),
                1,
                userMessagePayload("a"),
                TestIds.id(2),
                queued.requestHash(),
                null,
                TestIds.id(9),
                null,
                T0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadCommand(
                TestIds.id(1),
                1,
                userMessagePayload("a"),
                TestIds.id(2),
                queued.requestHash(),
                null,
                null,
                T0.plusMillis(1),
                T0));
  }

  @Test
  void threadSnapshotDefensiveCopiesAndValidates() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    List<ThreadCommand> queued =
        List.of(
            new ThreadCommand(
                thread.id(),
                1,
                userMessagePayload("a"),
                TestIds.id(1),
                ThreadCommandPayloadJsonCodec.requestHash(userMessagePayload("a")),
                null,
                null,
                null,
                T0));
    ThreadSnapshot snapshot =
        new ThreadSnapshot(thread, path, queued, null, List.of(), List.of(), List.of());
    assertEquals(1, snapshot.queuedCommands().size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.queuedCommands().add(null));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.toolSiblings().add(null));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(null, path, queued, null, List.of(), List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, null, queued, null, List.of(), List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, path, null, null, List.of(), List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, path, queued, null, null, List.of(), List.of()));
  }

  @Test
  void conflictExceptionCarriesTypedReasonAndMessage() {
    HarnessRuntimeConflictException error =
        new HarnessRuntimeConflictException(Reason.STALE_VERSION, "stale");
    assertEquals(Reason.STALE_VERSION, error.reason());
    assertEquals("stale", error.getMessage());
    assertThrows(NullPointerException.class, () -> new HarnessRuntimeConflictException(null, "x"));
  }

  @Test
  void notFoundExceptionCarriesMessage() {
    assertEquals("gone", new HarnessRuntimeNotFoundException("gone").getMessage());
  }

  private static ThreadState storeThread() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    return store.transaction(
        tx -> {
          tx.insertSession(
              new Session(TestIds.id(1), "session-00000000000000000000000000000001", T0));
          tx.insertEntry(
              new Entry(TestIds.id(2), TestIds.id(1), null, new RootPayload(settings()), T0));
          ThreadState thread =
              HarnessRuntimeTestSupport.thread(TestIds.id(3), TestIds.id(1), TestIds.id(2));
          tx.insertThread(thread);
          return thread;
        });
  }
}
