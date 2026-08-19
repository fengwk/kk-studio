package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;

import java.util.ArrayList;
import java.util.List;

/** 根控制面 API 的 Value-record 校验分支、类型化异常与最终 record 形状。 */
class HarnessRuntimeApiRecordsTest {

  @Test
  void acceptCommandsTargetsValidateTheirShapes() {
    List<NewThreadCommand> commands = List.of(userMessageCommand(TestIds.id(1), "hello"));
    // NEW_SESSION 必需字段与空/重复 clientCommandId 拒绝。
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewSession(
                null, TestIds.id(2), settings(), null, false, commands));
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewSession(
                TestIds.id(1), null, settings(), null, false, commands));
    assertThrows(
        NullPointerException.class,
        () ->
            new AcceptCommandsTarget.NewSession(
                TestIds.id(1), TestIds.id(2), null, null, false, commands));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsTarget.NewSession(
                TestIds.id(1), TestIds.id(2), settings(), null, false, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsTarget.NewSession(
                TestIds.id(1),
                TestIds.id(2),
                settings(),
                null,
                false,
                List.of(
                    userMessageCommand(TestIds.id(1), "a"),
                    userMessageCommand(TestIds.id(1), "b"))));

    // ENTRY 必需字段。
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.Entry(null, TestIds.id(3), TestIds.id(4), false, commands));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.Entry(TestIds.id(1), null, TestIds.id(4), false, commands));

    // THREAD cursor 必须为正。
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptCommandsTarget.Thread(TestIds.id(1), TestIds.id(2), 0, commands));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.Thread(null, TestIds.id(2), 1, commands));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsTarget.Thread(TestIds.id(1), null, 1, commands));

    // 防御性拷贝：外部 List 变更不影响已构造 target。
    List<NewThreadCommand> mutable = new ArrayList<>(commands);
    AcceptCommandsTarget.NewSession target =
        new AcceptCommandsTarget.NewSession(
            TestIds.id(1), TestIds.id(2), settings(), null, true, mutable);
    mutable.clear();
    assertEquals(1, target.commands().size());
    assertEquals(TestIds.id(1), target.sessionId());
    assertEquals(TestIds.id(2), target.threadId());
    assertTrue(target.yoloEnabled());
  }

  @Test
  void acceptCommandsCommandRequiresATarget() {
    assertThrows(NullPointerException.class, () -> new AcceptCommandsCommand(null));
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                TestIds.id(1), TestIds.id(2), 5, List.of(userMessageCommand(TestIds.id(1), "a"))));
    AcceptCommandsTarget.Thread target = (AcceptCommandsTarget.Thread) command.target();
    assertEquals(5L, target.expectedNextCommandSequence());
    assertEquals(TestIds.id(2), target.expectedHeadEntryId());
  }

  @Test
  void acceptCommandsResultValidatesConsistencyAndCopiesCommands() {
    ThreadState thread = storeThread();
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
    AcceptCommandsResult result =
        new AcceptCommandsResult(thread.sessionId(), thread.id(), thread, commands, false);
    commands.clear();
    // 防御性拷贝：外部 List 变更不影响结果。
    assertEquals(1, result.commands().size());
    assertEquals(thread.sessionId(), result.sessionId());
    assertEquals(thread.id(), result.threadId());
    assertEquals(thread, result.thread());
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsResult(null, thread.id(), thread, List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AcceptCommandsResult(TestIds.id(99), thread.id(), thread, List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AcceptCommandsResult(thread.sessionId(), TestIds.id(99), thread, List.of(), false));
    assertThrows(
        NullPointerException.class,
        () -> new AcceptCommandsResult(thread.sessionId(), thread.id(), null, List.of(), false));
  }

  /** CancelledUserMessage：sequence 必须为正，内容防御性拷贝。 */
  @Test
  void cancelledUserMessageValidatesShape() {
    List<AgentMessageContent> contents = new ArrayList<>(List.of(new TextMessageContent("hi")));
    CancelledUserMessage message = new CancelledUserMessage(3L, TestIds.id(7), contents);
    contents.clear();
    assertEquals(3L, message.sequence());
    assertEquals(TestIds.id(7), message.clientCommandId());
    assertEquals(1, message.contents().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new CancelledUserMessage(0L, TestIds.id(7), List.of()));
    assertThrows(NullPointerException.class, () -> new CancelledUserMessage(1L, null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new CancelledUserMessage(1L, TestIds.id(7), null));
  }

  /** StopResult 最终形状：replayed + 可空 stoppedTurnEndEntryId + 取消回单。 */
  @Test
  void stopResultFinalShapeRoundTrips() {
    ThreadState thread = storeThread();
    StopResult stopped = new StopResult(false, thread, TestIds.id(2), 1, List.of());
    assertEquals(false, stopped.replayed());
    assertEquals(TestIds.id(2), stopped.stoppedTurnEndEntryId());
    assertEquals(1, stopped.cancelledCommandCount());

    StopResult queuedReplay =
        new StopResult(
            true,
            thread,
            null,
            2,
            List.of(
                new CancelledUserMessage(
                    1L, TestIds.id(9), List.<AgentMessageContent>of(new TextMessageContent("x")))));
    assertTrue(queuedReplay.replayed());
    assertEquals(2, queuedReplay.cancelledCommandCount());
    assertEquals(TestIds.id(9), queuedReplay.cancelledUserMessages().getFirst().clientCommandId());
    assertThrows(
        IllegalArgumentException.class, () -> new StopResult(false, thread, null, -1, List.of()));
  }

  /** ThreadState 最终形状：materializationHash/sessionId 不可变，任何可见变更 revision 严格 +1。 */
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
                thread.headEntryId(),
                "not-a-hash",
                false,
                1,
                0,
                thread.createdAt(),
                thread.updatedAt()));
    // exact replay 被接受，身份字段变更被拒。
    ThreadState equal =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.headEntryId(),
            thread.materializationHash(),
            false,
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
                    thread.headEntryId(),
                    thread.materializationHash(),
                    false,
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
                    thread.headEntryId(),
                    "9999999999999999999999999999999999999999999999999999999999999999",
                    false,
                    1,
                    0,
                    thread.createdAt(),
                    thread.updatedAt())));
    // 任何可见变更（YOLO/head/seq）都必须 revision +1；setYoloEnabled 自身恰好 bump 一次是可接受的精确迁移。
    ThreadState bumped = thread.setYoloEnabled(true, T0.plusMillis(1));
    assertEquals(1L, bumped.revision());
    ThreadState.validateTransition(thread, bumped);
    // 显式构造 yolo 变更但 revision 未 +1 的候选必须被拒。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ThreadState.validateTransition(
                thread,
                new ThreadState(
                    thread.id(),
                    thread.sessionId(),
                    thread.headEntryId(),
                    thread.materializationHash(),
                    true,
                    1,
                    0,
                    thread.createdAt(),
                    T0.plusMillis(1))));
    assertEquals(thread.sessionId(), bumped.sessionId());
    assertEquals(thread.materializationHash(), bumped.materializationHash());
  }

  /** ThreadCommand 最终形状：cancelRequestId 取消回单与 QUEUED→CANCELLED 迁移。 */
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
    assertEquals(TestIds.id(9), cancelled.cancelRequestId());
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
    // cancelRequestId 必须与 cancelledAt 成对。
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
    ThreadSnapshot snapshot = new ThreadSnapshot(thread, path, queued, null, List.of(), List.of());
    assertEquals(1, snapshot.queuedCommands().size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.queuedCommands().add(null));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.toolSiblings().add(null));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(null, path, queued, null, List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, null, queued, null, List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, path, null, null, List.of(), List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, path, queued, null, null, List.of()));
  }

  @Test
  void conflictExceptionCarriesTypedReasonAndMessage() {
    HarnessRuntimeConflictException error =
        new HarnessRuntimeConflictException(Reason.STALE_REVISION, "stale");
    assertEquals(Reason.STALE_REVISION, error.reason());
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
          tx.insertSession(new Session(TestIds.id(1), T0));
          tx.insertEntry(
              new Entry(TestIds.id(2), TestIds.id(1), null, new RootPayload(settings()), T0));
          ThreadState thread =
              HarnessRuntimeTestSupport.thread(TestIds.id(3), TestIds.id(1), TestIds.id(2));
          tx.insertThread(thread);
          return thread;
        });
  }
}
