package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinDriver;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.SettingsPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 真实 PostgreSQL 下迟到退出确认必须按当前历史停止边界结算，而不是按回执时间/UUID 排序选择旧 Stop。
 *
 * <p>场景：目标 Thread 有一个指向父 Thread 的 NOTIFY_PARENT Join，以及一个已登记但从未进入 body 的执行代次；该事实让两次 Stop 都无法提前结算
 * Join。第二次 Stop 后 head 的最近控制边界是新的 STOPPED TurnEnd，即使两条回执的 created_at 相同且 UUID
 * 顺序与发生顺序相反，迟到确认也只能选用当前边界。
 */
class PostgresqlExecutionExitStopReceiptTest {

  /** 第一次 Stop 使用较大 UUID，第二次使用较小 UUID；PG 按 UUID 升序排序时旧回执会排在后面。 */
  private static final UUID FIRST_STOP_REQUEST_ID =
      UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");

  private static final UUID SECOND_STOP_REQUEST_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000001");

  private static final Instant SAME_RECEIPT_CREATED_AT = Instant.ofEpochMilli(1_700_000_000_123L);

  private HarnessStore store;
  private JdbcTemplate jdbcTemplate;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    jdbcTemplate = new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource());
    runtime =
        new HarnessRuntime(
            store,
            builtinDriver(store, (threadId, path, preparation) -> null),
            () -> CompactionConfig.DEFAULT);
  }

  @Test
  void lateExitSettlementUsesCurrentHeadStopReceiptEvenWhenOldReceiptSortsLast() {
    // 测试意图：同一 Thread 两次真实 runtime.stop 的回执时间被固定为相同毫秒、UUID 顺序与发生顺序相反；
    // 迟到 NOT_STARTED 确认必须结算第二次 Stop 的当前 head 边界，通知恰一次，重复确认不改变结果。
    Tree tree = seedJoinAndUnconfirmedExecution();
    UUID firstBoundary = stopAndBoundary(tree.targetThreadId(), FIRST_STOP_REQUEST_ID);
    UUID secondBoundary = stopAndBoundary(tree.targetThreadId(), SECOND_STOP_REQUEST_ID);
    assertNotEquals(firstBoundary, secondBoundary);

    StoppedThreadReceipt firstReceipt = findReceipt(tree.targetThreadId(), FIRST_STOP_REQUEST_ID);
    StoppedThreadReceipt secondReceipt = findReceipt(tree.targetThreadId(), SECOND_STOP_REQUEST_ID);
    assertEquals(firstBoundary, firstReceipt.stoppedTurnEndEntryId());
    assertEquals(secondBoundary, secondReceipt.stoppedTurnEndEntryId());

    // 两条回执的真实写入时间可能落在不同毫秒；固定为同一合法时间后，排序只能由 UUID 决定。
    jdbcTemplate.update(
        "update harness_thread_stop_receipt set created_at = ? where thread_id = ?",
        Timestamp.from(SAME_RECEIPT_CREATED_AT),
        tree.targetThreadId());
    List<UUID> ordered =
        store.transaction(
            tx ->
                tx.loadStopReceiptsByThread(tree.targetThreadId()).stream()
                    .map(StoppedThreadReceipt::stopRequestId)
                    .toList());
    assertEquals(List.of(SECOND_STOP_REQUEST_ID, FIRST_STOP_REQUEST_ID), ordered);

    // 尾部控制 Entry 不遮挡停止边界；Join 与执行事实仍未结算，等待迟到的 NOT_STARTED 确认。
    appendTrailingSettings(tree);
    EntryPath path =
        store.transaction(
            tx ->
                tx.loadEntryPath(tx.findThread(tree.targetThreadId()).orElseThrow().headEntryId()));
    Entry terminal = path.headIgnoringTrailingControlEntries();
    assertTrue(
        terminal.payload() instanceof TurnEndPayload end
            && end.closeRequestId().equals(SECOND_STOP_REQUEST_ID));
    ThreadJoin before = findJoin(tree.joinId());
    assertFalse(before.matched());
    assertEquals(ExecutionExitState.UNCONFIRMED, findExecution(tree.executionId()).state());
    assertFalse(hasThreadWork(tree.targetThreadId()));

    ExecutionExitFact expected = findExecution(tree.executionId());
    long versionBeforeConfirmation = findThread(tree.targetThreadId()).version();
    boolean changed = confirmNotStarted(tree.targetThreadId(), expected);
    assertTrue(changed);

    ThreadJoin settled = findJoin(tree.joinId());
    assertEquals(
        secondBoundary,
        settled.terminalEntryId(),
        "late exit settlement must use the current head stop boundary");
    assertNotEquals(firstBoundary, settled.terminalEntryId());
    assertEquals(1, notificationCount(tree.parentThreadId()));
    assertEquals(versionBeforeConfirmation + 1, findThread(tree.targetThreadId()).version());
    assertFalse(hasThreadWork(tree.targetThreadId()));
    assertEquals(ExecutionExitState.NOT_STARTED, findExecution(tree.executionId()).state());

    // 重复确认是幂等的：不重复通知、不再推进 version、不改变已冻结边界。
    long versionAfterConfirmation = findThread(tree.targetThreadId()).version();
    assertFalse(confirmNotStarted(tree.targetThreadId(), expected));
    assertEquals(secondBoundary, findJoin(tree.joinId()).terminalEntryId());
    assertEquals(1, notificationCount(tree.parentThreadId()));
    assertEquals(versionAfterConfirmation, findThread(tree.targetThreadId()).version());
    assertFalse(hasThreadWork(tree.targetThreadId()));
  }

  @Test
  void missingStopReceiptLeavesJoinUnsettled() {
    // 故障注入：仅在独立 PG 中删除目标回执，验证本切片明确的「无匹配持久回执不得结算」边界；
    // 正常运行不会自行删除回执。确认执行事实后 Join、通知与 STOPPED Work 均不得被推进。
    Tree tree = seedJoinAndUnconfirmedExecution();
    stopAndBoundary(tree.targetThreadId(), FIRST_STOP_REQUEST_ID);
    jdbcTemplate.update(
        "delete from harness_thread_stop_receipt where thread_id = ?", tree.targetThreadId());
    assertTrue(
        store
            .transaction(tx -> tx.findStopReceipt(tree.targetThreadId(), FIRST_STOP_REQUEST_ID))
            .isEmpty(),
        "the forced-missing receipt must be absent before confirmation");

    ExecutionExitFact expected = findExecution(tree.executionId());
    assertTrue(confirmNotStarted(tree.targetThreadId(), expected));
    assertEquals(ExecutionExitState.NOT_STARTED, findExecution(tree.executionId()).state());
    assertFalse(findJoin(tree.joinId()).matched());
    assertEquals(0, notificationCount(tree.parentThreadId()));
    assertEquals(
        ThreadExecutionControl.STOPPED, findThread(tree.targetThreadId()).executionControl());
    assertFalse(hasThreadWork(tree.targetThreadId()));
  }

  private record Tree(
      UUID sessionId, UUID parentThreadId, UUID targetThreadId, UUID joinId, UUID executionId) {}

  /** 父 Thread + 目标 Thread；目标持有一条 NOTIFY_PARENT Join 和一个未进入 body 的执行代次。 */
  private Tree seedJoinAndUnconfirmedExecution() {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID parentThreadId = tx.nextId();
          UUID targetThreadId = tx.nextId();
          tx.insertSession(StoreTestSupport.session(sessionId));
          tx.insertEntry(StoreTestSupport.rootEntry(rootEntryId, sessionId));
          tx.insertThread(
              thread(parentThreadId, sessionId, null, parentThreadId, rootEntryId, "parent", 1));
          tx.insertThread(
              thread(
                  targetThreadId,
                  sessionId,
                  parentThreadId,
                  parentThreadId,
                  rootEntryId,
                  "target",
                  2));
          ThreadCommand source = StoreTestSupport.command(targetThreadId, 1L, tx.nextId());
          tx.insertCommands(List.of(source));
          UUID joinId = tx.nextId();
          tx.insertJoin(
              new ThreadJoin(
                  joinId,
                  CREATION_REQUEST_HASH,
                  parentThreadId,
                  targetThreadId,
                  1L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  T0,
                  T0,
                  JoinDelivery.NOTIFY_PARENT,
                  null));
          UUID executionId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              executionId,
              targetThreadId,
              tx.nextId(),
              1,
              "late-exit-lease",
              ExecutionExitKind.MODEL,
              null);
          return new Tree(sessionId, parentThreadId, targetThreadId, joinId, executionId);
        });
  }

  private static ThreadState thread(
      UUID id,
      UUID sessionId,
      UUID parentThreadId,
      UUID rootThreadId,
      UUID headEntryId,
      String name,
      long nextCommandSequence) {
    return new ThreadState(
        id,
        sessionId,
        parentThreadId,
        headEntryId,
        CREATION_REQUEST_HASH,
        name,
        rootThreadId,
        false,
        parentThreadId == null ? SpawnTrigger.USER : SpawnTrigger.MODEL_TOOL,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        nextCommandSequence,
        0L,
        T0,
        T0);
  }

  private UUID stopAndBoundary(UUID threadId, UUID stopRequestId) {
    long version = findThread(threadId).version();
    runtime.stop(new StopCommand(threadId, stopRequestId, version));
    ThreadState stopped = findThread(threadId);
    assertEquals(ThreadExecutionControl.STOPPED, stopped.executionControl());
    return findReceipt(threadId, stopRequestId).stoppedTurnEndEntryId();
  }

  /** 在第二次 Stop 的边界之后追加真实合法 SETTINGS 控制 Entry，验证尾部控制 Entry 不遮挡停止边界。 */
  private void appendTrailingSettings(Tree tree) {
    store.transaction(
        tx -> {
          ThreadState target = tx.lockThread(tree.targetThreadId()).orElseThrow();
          UUID settingsId = tx.nextId();
          Instant now = tx.now();
          tx.insertEntry(
              new Entry(
                  settingsId,
                  tree.sessionId(),
                  target.headEntryId(),
                  new SettingsPayload(StoreTestSupport.branchSettings(), tree.targetThreadId()),
                  now));
          tx.updateThread(target.advanceHead(settingsId, now));
          return null;
        });
  }

  private boolean confirmNotStarted(UUID threadId, ExecutionExitFact expected) {
    return store.transaction(
        tx -> {
          ExecutionExitLedger.lockAncestorChain(tx, threadId);
          tx.lockThread(threadId).orElseThrow();
          ExecutionExitLedger.Confirmation confirmation =
              ExecutionExitLedger.confirm(tx, expected, ExecutionExitState.NOT_STARTED, tx.now())
                  .orElseThrow();
          ExecutionExitLedger.advanceAfterConfirmation(tx, threadId, confirmation);
          return confirmation.changed();
        });
  }

  private StoppedThreadReceipt findReceipt(UUID threadId, UUID stopRequestId) {
    return store.transaction(tx -> tx.findStopReceipt(threadId, stopRequestId)).orElseThrow();
  }

  private ThreadState findThread(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  private ExecutionExitFact findExecution(UUID executionId) {
    return store.transaction(tx -> tx.findExecution(executionId)).orElseThrow();
  }

  private ThreadJoin findJoin(UUID joinId) {
    return store.transaction(tx -> tx.findJoin(joinId)).orElseThrow();
  }

  private long notificationCount(UUID parentThreadId) {
    return store.transaction(tx -> loadCommandsByThread(tx, parentThreadId)).stream()
        .filter(command -> command.type().isNotification())
        .count();
  }

  private boolean hasThreadWork(UUID threadId) {
    return store
        .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, threadId)))
        .isPresent();
  }
}
