package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertReplayed;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assertStopped;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 两态执行控制、子树 Stop 与系统通知物化的定向场景用例（对应方案验收矩阵 J2/S1/S3/N1）。
 *
 * <p>覆盖：Stop 覆盖深层运行后代且不触碰祖先/兄弟（S1）；已冻结 Join 结果不被其后子对话或 fork 改写、不重复通知（J2）； 结果在父已 STOPPED
 * 时物化入历史且跨实例重启仍在、不重复（S3）；已物化通知与输入水位一致，新输入只恢复目标且不重复触发（N1）。
 */
class HarnessRuntimeThreadSemanticsScenarioTest {

  private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(5_000), ZoneOffset.UTC);

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = runtime(store, CLOCK);
  }

  /** S1：Stop 覆盖目标子树（含已冻结 Join 的深层运行后代），祖先与兄弟保持 RUNNABLE 且零 mutation。 */
  @Test
  void stopCoversRunningSubtreeWithCompletedJoinWithoutTouchingAncestorOrSibling() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID middle =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID deep = createChildThread(middle, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID sibling =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());

    // deep 的 Join 已冻结结果（matched），Stop 不得改写它。
    seedQueuedCommand(store, deep, 1L, userMessagePayload("deep-task"), TestIds.id(30));
    UUID joinId = UUID.randomUUID();
    insertMatchedJoin(joinId, middle, deep, root.rootEntryId());
    long rootVersion = version(root.threadId());
    long siblingVersion = version(sibling);

    StopResult result = runtime.stop(new StopCommand(middle, TestIds.id(11), 0L));

    assertEquals(2, result.stoppedThreads().size());
    assertEquals(ThreadExecutionControl.STOPPED, executionControl(middle));
    assertEquals(ThreadExecutionControl.STOPPED, executionControl(deep));
    assertEquals(ThreadExecutionControl.RUNNABLE, executionControl(root.threadId()));
    assertEquals(ThreadExecutionControl.RUNNABLE, executionControl(sibling));
    assertEquals(rootVersion, version(root.threadId()));
    assertEquals(siblingVersion, version(sibling));
    assertEquals(
        root.rootEntryId(),
        store.transaction(tx -> tx.findJoin(joinId).orElseThrow()).terminalEntryId());
  }

  /** J2：已冻结 Join 结果不因之后的新子线程/fork 改写，也不重复通知。 */
  @Test
  void frozenJoinResultIsNotRewrittenByLaterChildActivityOrFork() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("task"), TestIds.id(21));
    insertJoin(joinId, root.threadId(), child, 1L);

    StopResult stop = runtime.stop(new StopCommand(child, TestIds.id(12), 0L));
    assertStopped(stop);
    ThreadJoin frozen = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertNotNull(frozen.terminalEntryId());
    UUID frozenTerminal = frozen.terminalEntryId();
    Integer deliveriesBefore =
        store.transaction(tx -> tx.loadCommandsByThread(root.threadId())).size();

    // 之后的 fork（新子线程）与重放旧 Stop 都不改写冻结结果，也不产生第二条通知。
    UUID forked =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    assertNotNull(forked);
    StopResult replay = runtime.stop(new StopCommand(child, TestIds.id(12), 0L));
    assertReplayed(replay);
    assertEquals(
        frozenTerminal,
        store.transaction(tx -> tx.findJoin(joinId).orElseThrow()).terminalEntryId());
    assertEquals(
        deliveriesBefore, store.transaction(tx -> tx.loadCommandsByThread(root.threadId())).size());
  }

  /** S3：父已 STOPPED 时子结果物化入历史；新实例重启后仍在且只有一条，绝不唤醒已停止模型。 */
  @Test
  void childResultMaterializedIntoStoppedParentSurvivesRestartWithoutDuplicate() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("task"), TestIds.id(22));
    insertJoin(joinId, root.threadId(), child, 1L);

    runtime.stop(new StopCommand(root.threadId(), TestIds.id(13), 0L));

    ThreadJoin join = store.transaction(tx -> tx.findJoin(joinId).orElseThrow());
    assertTrue(join.matched());
    assertNotNull(join.deliveryCommandSequence());
    assertEquals(1, notificationCount(root.threadId()));

    // 新实例（模拟重启）复用同一 store：历史与停止事实不变，且没有再次交付。
    HarnessRuntime restarted = runtime(store, CLOCK);
    assertNotNull(restarted.findJoin(joinId).orElseThrow().terminalEntryId());
    assertEquals(1, notificationCount(root.threadId()));
    boolean noQueuedCommands =
        store.transaction(
            tx -> {
              tx.lockThread(root.threadId());
              return tx.loadQueuedCommands(root.threadId()).isEmpty();
            });
    assertTrue(noQueuedCommands);
  }

  /** N1：物化通知与输入水位一致；显式新输入只恢复目标线程，历史通知不重复且不再触发第二次交付。 */
  @Test
  void materializedNotificationStaysConsistentWithInputThroughSequenceAcrossResume() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        createChildThread(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID joinId = UUID.randomUUID();
    seedQueuedCommand(store, child, 1L, userMessagePayload("task"), TestIds.id(23));
    insertJoin(joinId, root.threadId(), child, 1L);

    runtime.stop(new StopCommand(root.threadId(), TestIds.id(14), 0L));
    ThreadState stopped = thread(root.threadId());
    assertEquals(ThreadExecutionControl.STOPPED, stopped.executionControl());
    // Stop 不推进输入水位。
    assertEquals(0L, stopped.inputThroughSequence());
    assertEquals(1, notificationCount(root.threadId()));

    // 显式新 USER 输入恢复目标；通知历史不重复、水位仍只在普通 INPUT 提交时推进。
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    root.threadId(), stopped.headEntryId(), stopped.nextCommandSequence()),
                List.of(userMessageCommand(TestIds.id(24), "resume"))),
            AcceptancePreflight.IDENTITY);
    assertFalse(accepted.replayed());
    assertEquals(ThreadExecutionControl.RUNNABLE, thread(root.threadId()).executionControl());
    assertEquals(1, notificationCount(root.threadId()));
    assertEquals(0L, thread(root.threadId()).inputThroughSequence());

    // 后代随根 Stop 一次性停止，不因显式恢复目标而被复活。
    assertEquals(ThreadExecutionControl.STOPPED, executionControl(child));
  }

  private void insertMatchedJoin(UUID joinId, UUID parentId, UUID childId, UUID terminalEntryId) {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          ThreadJoin join =
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0,
                  JoinPurpose.TASK,
                  null);
          // terminalEntryId 指向已存在的 Entry；仅验证冻结结果不被 Stop 改写。
          tx.insertJoin(join);
          tx.updateJoin(join.match(terminalEntryId, null, T0));
          return null;
        });
  }

  private void insertJoin(UUID joinId, UUID parentId, UUID childId, long sourceSequence) {
    store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  sourceSequence,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0,
                  JoinPurpose.TASK,
                  null));
          return null;
        });
  }

  private UUID createChildThread(
      UUID parentThreadId, UUID rootThreadId, UUID sessionId, UUID headEntryId) {
    UUID childId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.insertThread(
              new ThreadState(
                  childId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  CREATION_REQUEST_HASH,
                  "child-branch",
                  ThreadYoloPolicy.follow(rootThreadId),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  T0,
                  T0));
          return null;
        });
    return childId;
  }

  private ThreadExecutionControl executionControl(UUID threadId) {
    return thread(threadId).executionControl();
  }

  private ThreadState thread(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  private long version(UUID threadId) {
    return thread(threadId).version();
  }

  private int notificationCount(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState state = tx.findThread(threadId).orElseThrow();
          EntryPath path = tx.loadEntryPath(state.headEntryId());
          int count = 0;
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload) {
              count++;
            }
          }
          return count;
        });
  }
}
