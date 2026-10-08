package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ThreadJoin 契约记录与执行树事务锁原语约束测试。 */
public abstract class HarnessStoreJoinContract {

  private static final String REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private HarnessStore store;
  private Baseline baseline;

  @BeforeEach
  void setUp() {
    store = createStore();
    baseline = seedThreadBaseline(store);
  }

  abstract HarnessStore createStore();

  private UUID createChildThread(UUID parentThreadId, UUID sessionId, UUID rootEntryId) {
    return store.transaction(
        tx -> {
          UUID childId = tx.nextId();
          // 子代理恒 FOLLOW 真实执行根：从不可变 parent 链取最顶层线程（parent 为 null），而非中间父节点。
          ThreadYoloPolicy yoloPolicy;
          if (parentThreadId == null) {
            yoloPolicy = ThreadYoloPolicy.root(false);
          } else {
            List<UUID> chain = tx.findAncestorChain(parentThreadId);
            yoloPolicy = ThreadYoloPolicy.follow(chain.get(chain.size() - 1));
          }
          tx.insertThread(
              new ThreadState(
                  childId,
                  sessionId,
                  parentThreadId,
                  rootEntryId,
                  CREATION_REQUEST_HASH,
                  "child-thread",
                  yoloPolicy,
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  T0,
                  T0));
          return childId;
        });
  }

  private void insertCommand(UUID threadId, long sequence, UUID idempotencyKey) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId);
          tx.insertCommands(List.of(command(threadId, sequence, idempotencyKey)));
        });
  }

  private static ThreadJoin initialJoin(
      UUID invocationId, UUID parentThreadId, UUID childThreadId, long sourceCommandSequence) {
    return initialJoin(invocationId, parentThreadId, childThreadId, sourceCommandSequence, T0, T0);
  }

  private static ThreadJoin initialJoin(
      UUID invocationId,
      UUID parentThreadId,
      UUID childThreadId,
      long sourceCommandSequence,
      Instant createdAt,
      Instant updatedAt) {
    return joinReceipts(
        invocationId,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        null,
        null,
        null,
        createdAt,
        updatedAt);
  }

  /** 构造携带显式 terminal / final answer / delivery 凭据的 Join，用于终止状态与不可变性校验。 */
  private static ThreadJoin joinReceipts(
      UUID invocationId,
      UUID parentThreadId,
      UUID childThreadId,
      long sourceCommandSequence,
      UUID terminalEntryId,
      UUID finalAnswerEntryId,
      Long deliveryCommandSequence,
      Instant createdAt,
      Instant updatedAt) {
    return new ThreadJoin(
        invocationId,
        REQUEST_HASH,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        "test-agent",
        10,
        0L,
        terminalEntryId,
        finalAnswerEntryId,
        deliveryCommandSequence,
        createdAt,
        updatedAt,
        JoinPurpose.TASK,
        null);
  }

  @Test
  void insertAndFindJoinRoundTripWithParent() {
    // 测试意图：验证携带 parentThreadId 的初始未匹配 ThreadJoin 可成功写入并完整读取全部字段。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(100);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    Optional<ThreadJoin> loaded = store.transaction(tx -> tx.findJoin(invocationId));
    assertTrue(loaded.isPresent());
    assertEquals(join, loaded.get());
    assertFalse(loaded.get().matched());
  }

  @Test
  void insertAndFindJoinRoundTripWithoutParent() {
    // 测试意图：验证 root one-shot ticket（parentThreadId 为 null）的 ThreadJoin 可成功写入并完整读取。
    insertCommand(baseline.threadId(), 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(101);
    ThreadJoin join = initialJoin(invocationId, null, baseline.threadId(), 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertJoin(join);
        });

    Optional<ThreadJoin> loaded = store.transaction(tx -> tx.findJoin(invocationId));
    assertTrue(loaded.isPresent());
    assertEquals(join, loaded.get());
  }

  @Test
  void findJoinNonExistentReturnsEmpty() {
    // 测试意图：验证查找不存在的 invocationId 返回 Optional.empty()。
    Optional<ThreadJoin> loaded = store.transaction(tx -> tx.findJoin(TestIds.id(999)));
    assertTrue(loaded.isEmpty());
  }

  @Test
  void insertJoinRejectsSubMillisecondPrecision() {
    // 测试意图：验证 insertJoin 拒绝亚毫秒精度的 createdAt 与 updatedAt。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));

    ThreadJoin invalidCreatedAt =
        initialJoin(TestIds.id(102), baseline.threadId(), childId, 1L, T0.plusNanos(500), T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(invalidCreatedAt);
                }));

    ThreadJoin invalidUpdatedAt =
        initialJoin(TestIds.id(103), baseline.threadId(), childId, 1L, T0, T0.plusNanos(500));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(invalidUpdatedAt);
                }));
  }

  @Test
  void insertJoinRejectsAlreadyMatchedOrDeliveredJoin() {
    // 测试意图：验证 insertJoin 拒绝非初始（已 matched、已 delivered 或已 remind）的 Join。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(baseline.threadId(), 1L, TestIds.id(2));

    // 已冻结 terminal 结果的 Join
    ThreadJoin matched =
        joinReceipts(
            TestIds.id(104),
            baseline.threadId(),
            childId,
            1L,
            baseline.rootEntryId(),
            null,
            null,
            T0,
            T0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(matched);
                }));

    // 已冻结结果并已完成父交付的 Join
    ThreadJoin delivered =
        joinReceipts(
            TestIds.id(105),
            baseline.threadId(),
            childId,
            1L,
            baseline.rootEntryId(),
            null,
            1L,
            T0,
            T0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(delivered);
                }));

    // 已推进提醒轮次的 Join
    ThreadJoin reminded =
        initialJoin(TestIds.id(106), baseline.threadId(), childId, 1L).remind(1L, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(reminded);
                }));
  }

  @Test
  void insertJoinRejectsDuplicateInvocationId() {
    // 测试意图：验证 insertJoin 拒绝重复的 invocationId 主键冲突。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(105);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(join);
                }));
  }

  @Test
  void insertJoinRejectsNonExistentChildThread() {
    // 测试意图：验证 insertJoin 在 childThreadId 对应线程不存在时抛出 IllegalArgumentException。
    UUID nonExistentChildId = TestIds.id(888);
    ThreadJoin join = initialJoin(TestIds.id(106), baseline.threadId(), nonExistentChildId, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertJoin(join);
                }));
  }

  @Test
  void insertJoinRejectsNonExistentParentThread() {
    // 测试意图：验证 insertJoin 在 parentThreadId 非空且对应父线程不存在时抛出 IllegalArgumentException。
    UUID nonExistentParentId = TestIds.id(889);
    insertCommand(baseline.threadId(), 1L, TestIds.id(1));
    ThreadJoin join = initialJoin(TestIds.id(107), nonExistentParentId, baseline.threadId(), 1L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertJoin(join);
                }));
  }

  @Test
  void insertJoinRejectsNonExistentSourceCommand() {
    // 测试意图：验证 insertJoin 在子线程不存在对应 source_command_sequence 时抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    // 未在 childId 上插入 sequence 1 的 command
    ThreadJoin join = initialJoin(TestIds.id(108), baseline.threadId(), childId, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.insertJoin(join);
                }));
  }

  @Test
  void insertJoinRejectsUnlockedChildThread() {
    // 测试意图：验证在未锁定子线程的情况下调用 insertJoin 抛出 IllegalStateException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    ThreadJoin join = initialJoin(TestIds.id(109), baseline.threadId(), childId, 1L);

    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.insertJoin(join)));
  }

  @Test
  void updateJoinRejectsUnlockedChildThread() {
    // 测试意图：验证在未锁定子线程的情况下调用 updateJoin 抛出 IllegalStateException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(110);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin reminded = join.remind(1L, T1);
    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.updateJoin(reminded)));
  }

  @Test
  void updateJoinRejectsNonExistentJoin() {
    // 测试意图：验证 updateJoin 在 join 不存在时抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    ThreadJoin join = initialJoin(TestIds.id(998), baseline.threadId(), childId, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(join);
                }));
  }

  @Test
  void updateJoinRejectsImmutableIdentityChanges() {
    // 测试意图：验证 updateJoin 拒绝篡改不可变持久身份字段（如 agent、sourceCommandSequence 等）。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(childId, 2L, TestIds.id(2));
    UUID invocationId = TestIds.id(111);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin alteredAgent =
        new ThreadJoin(
            invocationId,
            REQUEST_HASH,
            baseline.threadId(),
            childId,
            1L,
            "altered-agent",
            10,
            0L,
            null,
            null,
            null,
            T0,
            T1,
            JoinPurpose.TASK,
            null);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(alteredAgent);
                }));

    ThreadJoin alteredSourceCommandSequence =
        joinReceipts(invocationId, baseline.threadId(), childId, 2L, null, null, null, T0, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(alteredSourceCommandSequence);
                }));
  }

  @Test
  void updateJoinRejectsUpdatedAtRegression() {
    // 测试意图：验证 updateJoin 拒绝 updatedAt 时间回退。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(112);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, T1, T2);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin regressedTime = join.remind(1L, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(regressedTime);
                }));
  }

  @Test
  void updateJoinMatchAndDeliveryRoundTrip() {
    // 测试意图：验证 Join 完整的生命周期推进：创建 -> match 冻结结果 -> delivery 交付给父线程。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(113);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    // 产生子线程执行产生的终态 Entry（需在 open TURN_START 之内）
    UUID turnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), childId, T1));
              return id;
            });
    UUID terminalEntryId =
        insertChildEntry(store, baseline.sessionId(), turnStartId, userMessagePayload());
    UUID finalAnswerEntryId =
        insertChildEntry(store, baseline.sessionId(), terminalEntryId, userMessagePayload());

    // 匹配 match：冻结 terminal Entry 与最终回答入口
    ThreadJoin matched = join.match(terminalEntryId, finalAnswerEntryId, T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched);
        });

    ThreadJoin storedMatched = store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(matched, storedMatched);
    assertTrue(storedMatched.matched());
    assertEquals(terminalEntryId, storedMatched.terminalEntryId());
    assertEquals(finalAnswerEntryId, storedMatched.finalAnswerEntryId());

    // 在父线程上插入 delivery command
    insertCommand(baseline.threadId(), 1L, TestIds.id(2));

    // 交付 delivered
    ThreadJoin delivered = storedMatched.delivered(1L, T2);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(delivered);
        });

    ThreadJoin storedDelivered = store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(delivered, storedDelivered);
    assertEquals(1L, storedDelivered.deliveryCommandSequence());
  }

  @Test
  void updateJoinRejectsFrozenTerminalAndFinalAnswerChanges() {
    // 测试意图：验证 matched 后 terminalEntryId 与 finalAnswerEntryId 均被冻结，不得再改写。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(117);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    Baseline other = seedThreadBaseline(store);
    ThreadJoin matched = join.match(baseline.rootEntryId(), baseline.rootEntryId(), T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched);
        });

    // 改写 terminalEntryId
    ThreadJoin changedTerminal =
        joinReceipts(
            invocationId,
            baseline.threadId(),
            childId,
            1L,
            other.rootEntryId(),
            null,
            null,
            T0,
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(changedTerminal);
                }));

    // 改写 finalAnswerEntryId
    ThreadJoin changedFinalAnswer =
        joinReceipts(
            invocationId,
            baseline.threadId(),
            childId,
            1L,
            baseline.rootEntryId(),
            other.rootEntryId(),
            null,
            T0,
            T2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(changedFinalAnswer);
                }));
  }

  @Test
  void updateJoinRejectsDeliveryCommandSequenceChange() {
    // 测试意图：验证已写入的 deliveryCommandSequence 不可更改。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(baseline.threadId(), 1L, TestIds.id(2));
    insertCommand(baseline.threadId(), 2L, TestIds.id(3));
    UUID invocationId = TestIds.id(118);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin delivered = join.match(baseline.rootEntryId(), null, T1).delivered(1L, T2);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(delivered);
        });

    ThreadJoin changedDelivery =
        joinReceipts(
            invocationId,
            baseline.threadId(),
            childId,
            1L,
            baseline.rootEntryId(),
            null,
            2L,
            T0,
            T3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(changedDelivery);
                }));
  }

  @Test
  void updateJoinRejectsReminderAdvanceAfterMatch() {
    // 测试意图：验证 matched 后 reminderTurn 不得再推进。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(119);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin reminded = join.remind(1L, T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(reminded);
        });

    ThreadJoin matched = reminded.match(baseline.rootEntryId(), null, T2);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched);
        });

    ThreadJoin advancedReminder = reminded.remind(2L, T3).match(baseline.rootEntryId(), null, T3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(advancedReminder);
                }));
  }

  @Test
  void joinRequiresFinalAnswerEntryToImplyTerminalEntry() {
    // 测试意图：验证 ThreadJoin 构造时 finalAnswerEntryId 必须依附 terminalEntryId。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            joinReceipts(
                TestIds.id(880),
                TestIds.id(881),
                TestIds.id(882),
                1L,
                null,
                TestIds.id(883),
                null,
                T0,
                T0));
  }

  @Test
  void updateJoinMatchRejectsNonExistentTerminalEntry() {
    // 测试意图：验证 updateJoin 匹配时若 terminalEntryId 或 finalAnswerEntryId 对应 Entry 不存在
    // 则抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(114);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    UUID nonExistentEntryId = TestIds.id(987);
    ThreadJoin matchedNonExistentTerminal = join.match(nonExistentEntryId, null, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(matchedNonExistentTerminal);
                }));

    ThreadJoin matchedNonExistentFinalAnswer =
        join.match(baseline.rootEntryId(), nonExistentEntryId, T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(matchedNonExistentFinalAnswer);
                }));
  }

  @Test
  void updateJoinDeliveryRejectsNonExistentDeliveryCommand() {
    // 测试意图：验证 updateJoin 交付时若父线程不存在指定 delivery command sequence 则抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(115);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin matched = join.match(baseline.rootEntryId(), null, T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched);
        });

    // 父线程未插入 sequence 1 的 command，执行 delivered 应当在 store 抛出异常
    ThreadJoin delivered = matched.delivered(1L, T2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(delivered);
                }));
  }

  @Test
  void updateJoinRemindAdvancesTurnOnUnmatchedJoin() {
    // 测试意图：验证未 matched 的 join 成功推进 reminderTurn。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(116);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin reminded1 = join.remind(1L, T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(reminded1);
        });
    assertEquals(
        1L, (long) store.transaction(tx -> tx.findJoin(invocationId).orElseThrow().reminderTurn()));

    ThreadJoin reminded2 = reminded1.remind(3L, T2);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(reminded2);
        });
    assertEquals(
        3L, (long) store.transaction(tx -> tx.findJoin(invocationId).orElseThrow().reminderTurn()));
  }

  @Test
  void loadIncompleteJoinsFiltersByChildAndUnfrozenTerminalAndSortsCorrectly() {
    // 测试意图：验证 loadIncompleteJoins 只返回指定子线程且尚未冻结 terminal（terminalEntryId 为 null）的 join，
    // 按 (createdAt, invocationId) 升序；已 matched 的 join 与其它子线程的 join 均被排除。
    UUID childId1 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    UUID childId2 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId1, 1L, TestIds.id(1));
    insertCommand(childId1, 2L, TestIds.id(2));
    insertCommand(childId1, 3L, TestIds.id(3));
    insertCommand(childId1, 4L, TestIds.id(4));
    insertCommand(childId2, 1L, TestIds.id(5));

    UUID id1 = TestIds.id(201);
    UUID id2 = TestIds.id(202);
    UUID id3 = TestIds.id(203);
    UUID id4 = TestIds.id(204);
    UUID id5 = TestIds.id(205);

    ThreadJoin join1 = initialJoin(id1, baseline.threadId(), childId1, 1L, T1, T1);
    ThreadJoin join2 = initialJoin(id2, baseline.threadId(), childId1, 2L, T0, T0);
    ThreadJoin join3 = initialJoin(id3, baseline.threadId(), childId1, 3L, T2, T2);
    // 与 join3 同时创建，用于验证 invocationId 升序的稳定次序
    ThreadJoin join4 = initialJoin(id5, baseline.threadId(), childId1, 4L, T2, T2);
    ThreadJoin joinOtherChild = initialJoin(id4, baseline.threadId(), childId2, 1L, T0, T0);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.insertJoin(join1);
          tx.insertJoin(join2);
          tx.insertJoin(join3);
          tx.insertJoin(join4);
          tx.lockThread(childId2);
          tx.insertJoin(joinOtherChild);
        });

    // joinOtherChild 属于不同 child，不参与 childId1 的 incomplete 集合
    assertEquals(
        List.of(join2, join1, join3, join4),
        store.transaction(tx -> tx.loadIncompleteJoins(childId1)));
    assertTrue(store.transaction(tx -> tx.loadIncompleteJoins(TestIds.id(999))).isEmpty());

    // 冻结 join1 的 terminal 结果后，join1 不再出现在 incomplete 列表
    ThreadJoin matchedJoin1 = join1.match(baseline.rootEntryId(), null, T3);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.updateJoin(matchedJoin1);
        });

    assertEquals(
        List.of(join2, join3, join4), store.transaction(tx -> tx.loadIncompleteJoins(childId1)));
  }

  @Test
  void loadPendingDeliveriesFiltersAndSortsCorrectly() {
    // 测试意图：验证 loadPendingDeliveries 只返回指定父线程且已 matched、未 delivered 的 join，按 (createdAt,
    // invocationId) 升序。
    UUID parentId1 = baseline.threadId();
    UUID parentId2 = createChildThread(null, baseline.sessionId(), baseline.rootEntryId());
    UUID childId = createChildThread(parentId1, baseline.sessionId(), baseline.rootEntryId());

    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(childId, 2L, TestIds.id(2));
    insertCommand(childId, 3L, TestIds.id(3));
    insertCommand(parentId1, 1L, TestIds.id(4));

    UUID id1 = TestIds.id(301);
    UUID id2 = TestIds.id(302);
    UUID id3 = TestIds.id(303);

    ThreadJoin join1 = initialJoin(id1, parentId1, childId, 1L, T0, T0);
    ThreadJoin join2 = initialJoin(id2, parentId1, childId, 2L, T1, T1);
    ThreadJoin join3 = initialJoin(id3, parentId2, childId, 3L, T2, T2);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join1);
          tx.insertJoin(join2);
          tx.insertJoin(join3);
        });

    // 初始状态均未 matched，loadPendingDeliveries 返回空
    assertTrue(store.transaction(tx -> tx.loadPendingDeliveries(parentId1)).isEmpty());

    // 匹配 join1 与 join2
    ThreadJoin matched1 = join1.match(baseline.rootEntryId(), null, T2);
    ThreadJoin matched2 = join2.match(baseline.rootEntryId(), null, T2);
    ThreadJoin matched3 = join3.match(baseline.rootEntryId(), null, T2);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched1);
          tx.updateJoin(matched2);
          tx.updateJoin(matched3);
        });

    List<ThreadJoin> pending = store.transaction(tx -> tx.loadPendingDeliveries(parentId1));
    assertEquals(List.of(matched1, matched2), pending);

    // 将 matched1 交付后，从 pending 列表中移除
    ThreadJoin delivered1 = matched1.delivered(1L, T3);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(delivered1);
        });

    List<ThreadJoin> pendingAfterDelivery =
        store.transaction(tx -> tx.loadPendingDeliveries(parentId1));
    assertEquals(List.of(matched2), pendingAfterDelivery);
  }

  @Test
  void deleteJoinsByChildRemovesOnlyTargetChildJoins() {
    // 测试意图：验证 deleteJoinsByChild 仅删除指定子线程的全部已完成交付 join，需要子线程锁，并返回删除行数。
    UUID childId1 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    UUID childId2 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId1, 1L, TestIds.id(1));
    insertCommand(childId1, 2L, TestIds.id(2));
    insertCommand(childId2, 1L, TestIds.id(3));
    insertCommand(baseline.threadId(), 1L, TestIds.id(11));
    insertCommand(baseline.threadId(), 2L, TestIds.id(12));
    insertCommand(baseline.threadId(), 3L, TestIds.id(13));

    UUID id1 = TestIds.id(401);
    UUID id2 = TestIds.id(402);
    UUID id3 = TestIds.id(403);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          ThreadJoin j1 = initialJoin(id1, baseline.threadId(), childId1, 1L);
          ThreadJoin j2 = initialJoin(id2, baseline.threadId(), childId1, 2L);
          tx.insertJoin(j1);
          tx.insertJoin(j2);
          tx.updateJoin(j1.match(baseline.rootEntryId(), null, T1).delivered(1L, T2));
          tx.updateJoin(j2.match(baseline.rootEntryId(), null, T1).delivered(2L, T2));

          tx.lockThread(childId2);
          ThreadJoin j3 = initialJoin(id3, baseline.threadId(), childId2, 1L);
          tx.insertJoin(j3);
          tx.updateJoin(j3.match(baseline.rootEntryId(), null, T1).delivered(3L, T2));
        });

    // 未锁定 childId1 时调用 deleteJoinsByChild 抛异常
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.deleteJoinsByChild(childId1)));

    // 仅锁定 Thread 但未持有 Tree 锁时调用 deleteJoinsByChild 抛异常
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId1);
                  tx.deleteJoinsByChild(childId1);
                }));

    // 持有 Tree 锁及 Thread 锁后删除 childId1 的 join
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(baseline.threadId());
              tx.lockThread(childId1);
              return tx.deleteJoinsByChild(childId1);
            });
    assertEquals(2, deleted);

    // childId1 的 join 已被删除，childId2 的 join 依然存在
    store.transaction(
        tx -> {
          assertTrue(tx.findJoin(id1).isEmpty());
          assertTrue(tx.findJoin(id2).isEmpty());
          assertTrue(tx.findJoin(id3).isPresent());
          return null;
        });
  }

  @Test
  void deleteThreadsRejectsDeletionWhenReferencedByJoins() {
    // 测试意图：验证 deleteThreads 在线程存在关联 join 时拒绝删除，显式调用 deleteJoinsByChild 后方可 GC 线程。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(baseline.threadId(), 1L, TestIds.id(2));
    UUID invocationId = TestIds.id(501);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(initialJoin(invocationId, baseline.threadId(), childId, 1L));
        });

    // 直接尝试 deleteThreads 删除 childId 抛异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(childId);
                  tx.deleteThreads(List.of(childId));
                }));

    // 直接尝试 deleteThreads 删除 parent 线程也抛异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(baseline.threadId());
                  tx.deleteThreads(List.of(baseline.threadId()));
                }));

    // 匹配并交付 Join
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          ThreadJoin join = tx.findJoin(invocationId).orElseThrow();
          tx.updateJoin(join.match(baseline.rootEntryId(), null, T1).delivered(1L, T2));
        });

    // 交付后但尚未调用 deleteJoinsByChild 时，deleteThreads 仍应被拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(childId);
                  tx.deleteThreads(List.of(childId));
                }));

    // 显式清理 Join 后，deleteThreads 成功删除 childId，父线程保留
    inTransaction(
        store,
        tx -> {
          tx.lockTree(baseline.threadId());
          tx.lockThread(childId);
          tx.deleteJoinsByChild(childId);
          tx.deleteThreads(List.of(childId));
        });

    assertTrue(store.transaction(tx -> tx.findThread(childId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findThread(baseline.threadId())).isPresent());
  }

  @Test
  void deleteJoinsByChildRejectsUnmatchedJoin() {
    // 测试意图：验证 deleteJoinsByChild 在子线程存在未匹配 Join 时拒绝删除并抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(502);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(initialJoin(invocationId, baseline.threadId(), childId, 1L));
        });

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(childId);
                  tx.deleteJoinsByChild(childId);
                }));

    assertTrue(store.transaction(tx -> tx.findJoin(invocationId)).isPresent());
  }

  @Test
  void deleteJoinsByChildRejectsMatchedUndeliveredJoin() {
    // 测试意图：验证 deleteJoinsByChild 在子线程 Join 已匹配但未交付给父线程时拒绝删除，交付后方可安全删除。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    insertCommand(baseline.threadId(), 1L, TestIds.id(2));
    UUID invocationId = TestIds.id(503);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L);
          tx.insertJoin(join);
          tx.updateJoin(join.match(baseline.rootEntryId(), null, T1));
        });

    // 已匹配但未交付时删除失败
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(childId);
                  tx.deleteJoinsByChild(childId);
                }));

    // 推进为交付
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          ThreadJoin join = tx.findJoin(invocationId).orElseThrow();
          tx.updateJoin(join.delivered(1L, T2));
        });

    // 交付后成功删除
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(baseline.threadId());
              tx.lockThread(childId);
              return tx.deleteJoinsByChild(childId);
            });
    assertEquals(1, deleted);
    assertTrue(store.transaction(tx -> tx.findJoin(invocationId)).isEmpty());
  }

  @Test
  void deleteJoinsByChildSucceedsForMatchedRootTicket() {
    // 测试意图：验证根 completion ticket（parentThreadId 为 null）在未匹配时拒绝删除，已匹配后允许显式清理。
    UUID rootId = baseline.threadId();
    insertCommand(rootId, 1L, TestIds.id(1));
    UUID ticketId = TestIds.id(504);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(rootId);
          tx.insertJoin(initialJoin(ticketId, null, rootId, 1L));
        });

    // 未匹配时删除根 ticket 抛出异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(rootId);
                  tx.lockThread(rootId);
                  tx.deleteJoinsByChild(rootId);
                }));

    // 匹配根 ticket
    inTransaction(
        store,
        tx -> {
          tx.lockThread(rootId);
          ThreadJoin join = tx.findJoin(ticketId).orElseThrow();
          tx.updateJoin(join.match(baseline.rootEntryId(), null, T1));
        });

    // 匹配后成功显式清理
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(rootId);
              tx.lockThread(rootId);
              return tx.deleteJoinsByChild(rootId);
            });
    assertEquals(1, deleted);
    assertTrue(store.transaction(tx -> tx.findJoin(ticketId)).isEmpty());
  }

  @Test
  void deleteThreadsRejectsParentWhenSurvivingChildExists() {
    // 测试意图：验证 deleteThreads 在父线程存在存活子线程时拒绝删除，避免底层外键异常并保证清理顺序。
    UUID rootId = baseline.threadId();
    UUID childId = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());

    // 尝试单独删除 parent 线程抛出异常
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(rootId);
                  tx.lockThread(rootId);
                  tx.deleteThreads(List.of(rootId));
                }));

    assertTrue(store.transaction(tx -> tx.findThread(rootId)).isPresent());
    assertTrue(store.transaction(tx -> tx.findThread(childId)).isPresent());

    // 先删除子线程，再删除父线程均成功
    inTransaction(
        store,
        tx -> {
          tx.lockTree(rootId);
          tx.lockThread(childId);
          tx.deleteThreads(List.of(childId));
        });
    assertTrue(store.transaction(tx -> tx.findThread(childId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findThread(rootId)).isPresent());

    inTransaction(
        store,
        tx -> {
          tx.lockTree(rootId);
          tx.lockThread(rootId);
          tx.deleteThreads(List.of(rootId));
        });
    assertTrue(store.transaction(tx -> tx.findThread(rootId)).isEmpty());
  }

  @Test
  void deleteThreadsSucceedsDeletingHierarchyInSingleBatch() {
    // 测试意图：验证 deleteThreads 在单个批次中同时删除多级父子线程时，内部按子到父的规范拓扑顺序清理。
    UUID rootId = baseline.threadId();
    UUID childId = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());
    UUID grandChildId = createChildThread(childId, baseline.sessionId(), baseline.rootEntryId());

    insertCommand(rootId, 1L, TestIds.id(1));
    insertCommand(childId, 1L, TestIds.id(2));
    insertCommand(grandChildId, 1L, TestIds.id(3));

    List<UUID> allHierarchyThreads =
        List.of(rootId, childId, grandChildId).stream().sorted(UuidOrder.COMPARATOR).toList();

    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(rootId);
              for (UUID threadId : allHierarchyThreads) {
                tx.lockThread(threadId);
              }
              return tx.deleteThreads(List.of(rootId, childId, grandChildId));
            });
    assertEquals(3, deleted);

    store.transaction(
        tx -> {
          assertTrue(tx.findThread(rootId).isEmpty());
          assertTrue(tx.findThread(childId).isEmpty());
          assertTrue(tx.findThread(grandChildId).isEmpty());
          return null;
        });
  }

  @Test
  void fullCleanupValidDeliveredChildWithParentAndMultiSessionMultiTree() {
    // 测试意图：验证完整深清理流程（joins -> child threads -> parent -> entries -> sessions）
    // 在包含交付子线程及多 Session 多树环境下的正确性与隔离性，确保目标 Session 完全清理且非目标 Session 完整保留。
    UUID sessionA = baseline.sessionId();
    UUID rootA = baseline.threadId();
    UUID childA1 = createChildThread(rootA, sessionA, baseline.rootEntryId());
    UUID childA2 = createChildThread(rootA, sessionA, baseline.rootEntryId());
    insertCommand(childA1, 1L, TestIds.id(1));
    insertCommand(childA2, 1L, TestIds.id(2));
    insertCommand(rootA, 1L, TestIds.id(3));
    insertCommand(rootA, 2L, TestIds.id(4));

    UUID joinA1 = TestIds.id(601);
    UUID joinA2 = TestIds.id(602);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childA1);
          ThreadJoin j1 = initialJoin(joinA1, rootA, childA1, 1L);
          tx.insertJoin(j1);
          tx.updateJoin(j1.match(baseline.rootEntryId(), null, T1).delivered(1L, T2));

          tx.lockThread(childA2);
          ThreadJoin j2 = initialJoin(joinA2, rootA, childA2, 1L);
          tx.insertJoin(j2);
          tx.updateJoin(j2.match(baseline.rootEntryId(), null, T1).delivered(2L, T2));
        });

    // 建立隔离的 Session B 及关联事实
    Baseline baselineB = seedThreadBaseline(store);
    UUID sessionB = baselineB.sessionId();
    UUID rootB = baselineB.threadId();
    UUID childB = createChildThread(rootB, sessionB, baselineB.rootEntryId());
    insertCommand(childB, 1L, TestIds.id(5));
    insertCommand(rootB, 1L, TestIds.id(6));
    UUID joinB = TestIds.id(603);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childB);
          ThreadJoin jb = initialJoin(joinB, rootB, childB, 1L);
          tx.insertJoin(jb);
          tx.updateJoin(jb.match(baselineB.rootEntryId(), null, T1).delivered(1L, T2));
        });

    // 对 Session A 执行完整深删除（按规范顺序：joins -> child threads -> parent -> entries -> session）
    store.transaction(
        tx -> {
          List<UUID> allThreadsA =
              List.of(rootA, childA1, childA2).stream().sorted(UuidOrder.COMPARATOR).toList();
          tx.lockTree(rootA);
          for (UUID threadId : allThreadsA) {
            tx.lockThread(threadId);
          }

          assertEquals(1, tx.deleteJoinsByChild(childA1));
          assertEquals(1, tx.deleteJoinsByChild(childA2));

          // 删除子线程
          assertEquals(2, tx.deleteThreads(List.of(childA1, childA2)));

          // 删除父线程
          assertEquals(1, tx.deleteThreads(List.of(rootA)));

          // 删除 Session A 的 entries
          assertTrue(tx.deleteEntries(sessionA) > 0);

          // 删除 Session A
          assertTrue(tx.deleteSession(sessionA));
          return null;
        });

    // 校验 Session A 及其所有级联事实已被彻底清除
    store.transaction(
        tx -> {
          assertTrue(tx.findSession(sessionA).isEmpty());
          assertThrows(IllegalArgumentException.class, () -> tx.loadEntriesBySessionId(sessionA));
          assertTrue(tx.findThread(rootA).isEmpty());
          assertTrue(tx.findThread(childA1).isEmpty());
          assertTrue(tx.findThread(childA2).isEmpty());
          assertTrue(tx.findJoin(joinA1).isEmpty());
          assertTrue(tx.findJoin(joinA2).isEmpty());

          // 校验 Session B 毫发无损
          assertTrue(tx.findSession(sessionB).isPresent());
          assertTrue(tx.findThread(rootB).isPresent());
          assertTrue(tx.findThread(childB).isPresent());
          assertTrue(tx.findJoin(joinB).isPresent());
          assertEquals(1, tx.loadCommandsByThread(childB).size());
          assertEquals(1, tx.loadCommandsByThread(rootB).size());
          return null;
        });
  }

  @Test
  void findAncestorChainReturnsSingleRootAndHierarchy() {
    // 测试意图：验证 findAncestorChain 派生 head-to-root 祖先链（包含自身）。
    UUID rootId = baseline.threadId();
    UUID childId = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());
    UUID grandChildId = createChildThread(childId, baseline.sessionId(), baseline.rootEntryId());

    store.transaction(
        tx -> {
          assertEquals(List.of(rootId), tx.findAncestorChain(rootId));
          assertEquals(List.of(childId, rootId), tx.findAncestorChain(childId));
          assertEquals(List.of(grandChildId, childId, rootId), tx.findAncestorChain(grandChildId));
          assertTrue(tx.findAncestorChain(TestIds.id(999)).isEmpty());
          return null;
        });
  }

  @Test
  void lockTreeAcquiresLockOnRootThreadIdempotently() {
    // 测试意图：验证 lockTree 在根线程上加锁成功，且同一事务内可重入（幂等）。
    UUID rootId = baseline.threadId();
    inTransaction(
        store,
        tx -> {
          tx.lockTree(rootId);
          tx.lockTree(rootId); // 重复获取合法
        });
  }

  @Test
  void lockTreeRejectsNonRootThread() {
    // 测试意图：验证 lockTree 在非根线程（具有 parentThreadId 的子线程）上加锁时抛出 IllegalStateException。
    UUID rootId = baseline.threadId();
    UUID childId = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());

    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.lockTree(childId)));
  }

  @Test
  void lockTreeMustPrecedeBusinessRowLocks() {
    // 测试意图：验证在已获取 Session 或 Thread 等行锁之后再调用 lockTree 抛出 IllegalStateException。
    UUID rootId = baseline.threadId();

    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(rootId);
                  tx.lockTree(rootId); // 锁序违背：行锁后禁止 tree lock
                }));

    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockSessionForKeyShare(baseline.sessionId());
                  tx.lockTree(rootId);
                }));
  }

  @Test
  void lockTreeAllowsSubsequentRowLocks() {
    // 测试意图：验证在 lockTree 成功获取后，后续获取 Session、Thread 等业务行锁正常执行。
    UUID rootId = baseline.threadId();
    inTransaction(
        store,
        tx -> {
          tx.lockTree(rootId);
          tx.lockSessionForKeyShare(baseline.sessionId());
          tx.lockThread(rootId);
        });
  }

  @Test
  void lockTreeEnforcesAscendingRootOrder() {
    // 测试意图：验证多 root 加锁时必须按 UuidOrder 升序获取，降序获取抛出 IllegalStateException。
    UUID rootA = store.transaction(tx -> tx.nextId());
    UUID rootB = store.transaction(tx -> tx.nextId());
    UUID smaller = UuidOrder.COMPARATOR.compare(rootA, rootB) < 0 ? rootA : rootB;
    UUID larger = smaller.equals(rootA) ? rootB : rootA;

    // 升序获取成功
    inTransaction(
        store,
        tx -> {
          tx.lockTree(smaller);
          tx.lockTree(larger);
        });

    // 降序获取失败
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(larger);
                  tx.lockTree(smaller);
                }));
  }

  @Test
  void countIncompleteChildJoinsCountsOnlyUnmatchedDirectChildJoinsOfParent() {
    // 测试意图：验证 countIncompleteChildJoins 只统计 parentThreadId 等于入参且尚未冻结 terminal 结果的直接子 Join：
    // 每条未匹配 Join 各占一个额度，已匹配或属于其他父 Thread 的 Join 不计入，root ticket 与不存在的父 Thread 均返回 0。
    UUID rootId = baseline.threadId();
    UUID childId1 = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());
    UUID childId2 = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());
    UUID grandChildId = createChildThread(childId1, baseline.sessionId(), baseline.rootEntryId());
    UUID nonExistentParentId = store.transaction(HarnessStore.Transaction::nextId);

    insertCommand(childId1, 1L, TestIds.id(1));
    insertCommand(childId1, 2L, TestIds.id(2));
    insertCommand(childId2, 1L, TestIds.id(3));
    insertCommand(grandChildId, 1L, TestIds.id(4));
    insertCommand(rootId, 1L, TestIds.id(5));

    store.transaction(
        tx -> {
          // 尚无任何 Join 时，不存在的父 Thread 与尚无子 Join 的父 Thread 均返回 0
          assertEquals(0, tx.countIncompleteChildJoins(nonExistentParentId));
          assertEquals(0, tx.countIncompleteChildJoins(rootId));
          assertEquals(0, tx.countIncompleteChildJoins(childId1));
          return null;
        });

    UUID ticketId = TestIds.id(801);
    UUID joinId1 = TestIds.id(802);
    UUID joinId2 = TestIds.id(803);
    UUID joinId3 = TestIds.id(804);
    UUID grandChildJoinId = TestIds.id(805);

    inTransaction(
        store,
        tx -> {
          // root one-shot ticket（parentThreadId 为 null）不占任何父 Thread 的 child quota
          tx.lockThread(rootId);
          tx.insertJoin(initialJoin(ticketId, null, rootId, 1L));

          tx.lockThread(childId1);
          tx.insertJoin(initialJoin(joinId1, rootId, childId1, 1L));
          tx.insertJoin(initialJoin(joinId2, rootId, childId1, 2L));

          tx.lockThread(childId2);
          tx.insertJoin(initialJoin(joinId3, rootId, childId2, 1L));

          tx.lockThread(grandChildId);
          tx.insertJoin(initialJoin(grandChildJoinId, childId1, grandChildId, 1L));
        });

    store.transaction(
        tx -> {
          // rootId 的直接未匹配子 Join：childId1 两条 + childId2 一条 = 3（root ticket 与孙级 Join 不计入）
          assertEquals(3, tx.countIncompleteChildJoins(rootId));
          // childId1 的直接未匹配子 Join 只有孙级那一条
          assertEquals(1, tx.countIncompleteChildJoins(childId1));
          assertEquals(0, tx.countIncompleteChildJoins(childId2));
          assertEquals(0, tx.countIncompleteChildJoins(nonExistentParentId));
          return null;
        });

    // 冻结 childId1 上一部分 Join 的 terminal 结果后，rootId 的计数随之下降
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.updateJoin(tx.findJoin(joinId1).orElseThrow().match(baseline.rootEntryId(), null, T1));
        });

    store.transaction(
        tx -> {
          assertEquals(2, tx.countIncompleteChildJoins(rootId));
          // childId1 的孙级 Join 仍未匹配，计数不变
          assertEquals(1, tx.countIncompleteChildJoins(childId1));
          return null;
        });
  }

  @Test
  void countIncompleteSubagentJoinsCountsUnmatchedNonRootJoinsAcrossAllRoots() {
    // 测试意图：验证 countIncompleteSubagentJoins 全局统计尚未冻结 terminal 结果且 parentThreadId 非空的 Join：
    // 跨 root 聚合、包含更深后代，root ticket（parentThreadId 为 null）不计入，匹配或删除 Join 都会改变计数。
    Baseline other = seedThreadBaseline(store);
    UUID rootA = baseline.threadId();
    UUID rootB = other.threadId();

    store.transaction(
        tx -> {
          assertEquals(0, tx.countIncompleteSubagentJoins());
          return null;
        });

    UUID childA1 = createChildThread(rootA, baseline.sessionId(), baseline.rootEntryId());
    UUID childA2 = createChildThread(rootA, baseline.sessionId(), baseline.rootEntryId());
    UUID grandChildA1 = createChildThread(childA1, baseline.sessionId(), baseline.rootEntryId());
    UUID childB1 = createChildThread(rootB, other.sessionId(), other.rootEntryId());

    insertCommand(childA1, 1L, TestIds.id(1));
    insertCommand(childA2, 1L, TestIds.id(2));
    insertCommand(grandChildA1, 1L, TestIds.id(3));
    insertCommand(childB1, 1L, TestIds.id(4));
    insertCommand(rootA, 1L, TestIds.id(5));

    UUID ticketAId = TestIds.id(901);
    UUID joinA1Id = TestIds.id(902);
    UUID grandChildJoinId = TestIds.id(903);
    UUID joinA2Id = TestIds.id(904);
    UUID joinB1Id = TestIds.id(905);

    inTransaction(
        store,
        tx -> {
          // 必须先按 UUID 升序完成全部 Thread 行锁，再插入 Join（store 强制严格升序锁序）。
          for (UUID threadId :
              List.of(rootA, childA1, childA2, grandChildA1, childB1).stream()
                  .sorted(UuidOrder.COMPARATOR)
                  .toList()) {
            tx.lockThread(threadId);
          }
          // root one-shot ticket（parentThreadId 为 null）不占全局 subagent 额度
          tx.insertJoin(initialJoin(ticketAId, null, rootA, 1L));
          tx.insertJoin(initialJoin(joinA1Id, rootA, childA1, 1L));
          // 更深的后代 Join（父为 childA1）同样计入全局未完成计数
          tx.insertJoin(initialJoin(grandChildJoinId, childA1, grandChildA1, 1L));
          tx.insertJoin(initialJoin(joinA2Id, rootA, childA2, 1L));
          tx.insertJoin(initialJoin(joinB1Id, rootB, childB1, 1L));
        });

    store.transaction(
        tx -> {
          // 4 条带父未匹配 Join（跨 rootA / rootB、含孙级），root ticket 不计入
          assertEquals(4, tx.countIncompleteSubagentJoins());
          return null;
        });

    // 冻结 joinA1 的 terminal 结果：全局未完成计数 -1
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childA1);
          tx.updateJoin(
              tx.findJoin(joinA1Id).orElseThrow().match(baseline.rootEntryId(), null, T1));
        });

    store.transaction(
        tx -> {
          assertEquals(3, tx.countIncompleteSubagentJoins());
          return null;
        });

    // child 与 parent 同时在删除集合内时，未匹配 Join 可随两端一起清理并立即从计数中移除
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(rootA);
              tx.lockThread(rootA);
              tx.lockThread(childA2);
              return tx.deleteJoinsForThreads(List.of(rootA, childA2));
            });
    // 删除的是 root ticket（child 为 rootA）与 childA2 的 Join
    assertEquals(2, deleted);

    store.transaction(
        tx -> {
          assertEquals(2, tx.countIncompleteSubagentJoins());
          return null;
        });

    inTransaction(
        store,
        tx -> {
          tx.lockThread(grandChildA1);
          tx.updateJoin(
              tx.findJoin(grandChildJoinId).orElseThrow().match(baseline.rootEntryId(), null, T1));
        });

    store.transaction(
        tx -> {
          assertEquals(1, tx.countIncompleteSubagentJoins());
          return null;
        });

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childB1);
          tx.updateJoin(tx.findJoin(joinB1Id).orElseThrow().match(other.rootEntryId(), null, T1));
        });

    store.transaction(
        tx -> {
          assertEquals(0, tx.countIncompleteSubagentJoins());
          return null;
        });
  }

  @Test
  void joinAdmissionLockPrecedesTreeAndRowLocks() {
    // 测试意图：验证全局 Join 准入锁只能在事务内最先获取——准入后再取 tree / Thread 锁合法且准入锁幂等；
    // 已取 tree 锁或业务行锁后再取准入锁必须确定性拒绝，保证 admission -> tree -> row 的锁序。
    UUID rootId = baseline.threadId();
    inTransaction(
        store,
        tx -> {
          tx.lockJoinAdmission();
          tx.lockJoinAdmission();
          tx.lockTree(rootId);
          tx.lockThread(rootId);
        });

    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(rootId);
                  tx.lockJoinAdmission();
                }));

    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(rootId);
                  tx.lockJoinAdmission();
                }));
  }

  @Test
  void busyChildDuplicateJoinsQuotaCountsEachUnmatchedJoinNotThreadState() {
    // 测试意图：验证并发配额按尚未冻结 terminal 结果的 Join 计数而非线程执行控制状态：同一忙碌子线程挂载的
    // 多条未匹配 Join 各占一个额度，全部匹配后额度清零，而子线程自身仍保持 RUNNABLE。
    UUID rootId = baseline.threadId();
    UUID childId = createChildThread(rootId, baseline.sessionId(), baseline.rootEntryId());

    UUID inv1 = store.transaction(HarnessStore.Transaction::nextId);
    UUID inv2 = store.transaction(HarnessStore.Transaction::nextId);

    // 插入两条针对同一 childId 的未匹配 Join
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertCommands(
              List.of(command(childId, 1L, tx.nextId()), command(childId, 2L, tx.nextId())));
          tx.insertJoin(initialJoin(inv1, rootId, childId, 1L));
          tx.insertJoin(initialJoin(inv2, rootId, childId, 2L));
        });

    store.transaction(
        tx -> {
          // 两条未匹配 Join 各占一个额度，与子线程数量无关
          assertEquals(2, tx.countIncompleteChildJoins(rootId));
          assertEquals(2, tx.countIncompleteSubagentJoins());
          return null;
        });

    // 两条 Join 均冻结 terminal 结果后额度清零
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(tx.findJoin(inv1).orElseThrow().match(baseline.rootEntryId(), null, T1));
          tx.updateJoin(tx.findJoin(inv2).orElseThrow().match(baseline.rootEntryId(), null, T2));
        });

    store.transaction(
        tx -> {
          assertEquals(0, tx.countIncompleteChildJoins(rootId));
          assertEquals(0, tx.countIncompleteSubagentJoins());
          // 额度清零与线程执行控制状态无关：子线程持久状态仍为 RUNNABLE
          assertEquals(
              ThreadExecutionControl.RUNNABLE,
              tx.findThread(childId).orElseThrow().executionControl());
          return null;
        });
  }

  @Test
  void countIncompleteChildJoinsRejectsNullArgument() {
    // 测试意图：验证 countIncompleteChildJoins 对 null 参数抛出 NullPointerException。
    assertThrows(
        NullPointerException.class,
        () -> inTransaction(store, tx -> tx.countIncompleteChildJoins(null)));
  }

  @Test
  void deleteJoinsByChildRequiresHeldTreeLockOnSameRoot() {
    // 测试意图：验证 deleteJoinsByChild 必须在当前事务已获取目标线程所属 execution tree 的 tree lock 下执行；
    // 未加 tree lock 或持有其他不同 tree 的 lock 时均抛出 IllegalStateException。
    UUID rootA = baseline.threadId();
    UUID childA = createChildThread(rootA, baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childA, 1L, TestIds.id(1));
    insertCommand(rootA, 1L, TestIds.id(2));
    UUID joinA = TestIds.id(701);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childA);
          ThreadJoin j = initialJoin(joinA, rootA, childA, 1L);
          tx.insertJoin(j);
          tx.updateJoin(j.match(baseline.rootEntryId(), null, T1).delivered(1L, T2));
        });

    Baseline baselineB = seedThreadBaseline(store);
    UUID rootB = baselineB.threadId();

    // 1. 无 tree lock：抛出 IllegalStateException
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childA);
                  tx.deleteJoinsByChild(childA);
                }));

    // 2. 持有错误 tree 的 lock（rootB 而非 rootA）：抛出 IllegalStateException
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(rootB);
                  tx.lockThread(childA);
                  tx.deleteJoinsByChild(childA);
                }));

    // 3. 正确持有 rootA 的 tree lock：成功删除
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(rootA);
              tx.lockThread(childA);
              return tx.deleteJoinsByChild(childA);
            });
    assertEquals(1, deleted);
  }

  @Test
  void deleteThreadsRequiresHeldTreeLockOnSameRoot() {
    // 测试意图：验证 deleteThreads 必须在当前事务已获取每个目标线程所属 execution tree 的 tree lock 下执行；
    // 未加 tree lock 或持有其他不同 tree 的 lock 时均抛出 IllegalStateException。
    UUID rootA = baseline.threadId();
    UUID childA = createChildThread(rootA, baseline.sessionId(), baseline.rootEntryId());

    Baseline baselineB = seedThreadBaseline(store);
    UUID rootB = baselineB.threadId();

    // 1. 无 tree lock：删除 childA 抛出 IllegalStateException
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childA);
                  tx.deleteThreads(List.of(childA));
                }));

    // 2. 持有错误 tree 的 lock（rootB 而非 rootA）：抛出 IllegalStateException
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(rootB);
                  tx.lockThread(childA);
                  tx.deleteThreads(List.of(childA));
                }));

    // 3. 正确持有 rootA 的 tree lock：成功删除 childA
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(rootA);
              tx.lockThread(childA);
              return tx.deleteThreads(List.of(childA));
            });
    assertEquals(1, deleted);
    assertTrue(store.transaction(tx -> tx.findThread(childA)).isEmpty());
  }

  @Test
  void deleteThreadsCrossTreeBatchEnforcesEachTreeLocked() {
    // 测试意图：验证跨树批次 deleteThreads 要求批内每个线程对应的 tree root 均已被 lockTree 锁定；
    // 若只锁了部分树，则抛出 IllegalStateException；全锁定后允许原子删除跨树线程。
    Baseline baselineB = seedThreadBaseline(store);
    UUID rootA = baseline.threadId();
    UUID rootB = baselineB.threadId();

    UUID smallerRoot = UuidOrder.COMPARATOR.compare(rootA, rootB) < 0 ? rootA : rootB;
    UUID largerRoot = smallerRoot.equals(rootA) ? rootB : rootA;

    // 1. 跨树批次只锁了 smallerRoot 的树，未锁 largerRoot 的树：抛出 IllegalStateException
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(smallerRoot);
                  tx.lockThread(smallerRoot);
                  tx.lockThread(largerRoot);
                  tx.deleteThreads(List.of(smallerRoot, largerRoot));
                }));

    // 2. 跨树批次按 UuidOrder 锁定两棵树及两个线程：成功删除 2 个根线程
    int deleted =
        store.transaction(
            tx -> {
              tx.lockTree(smallerRoot);
              tx.lockTree(largerRoot);
              tx.lockThread(smallerRoot);
              tx.lockThread(largerRoot);
              return tx.deleteThreads(List.of(smallerRoot, largerRoot));
            });
    assertEquals(2, deleted);
    assertTrue(store.transaction(tx -> tx.findThread(rootA)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findThread(rootB)).isEmpty());
  }

  @Test
  void updateJoinValidatesTerminalOwnershipAndFinalAnswerPlacement() {
    // 测试意图：冻结 Join 回执时 terminal 必须落在子线程分支、TurnEnd 必须归子线程所有，
    // 且 ASSISTANT final answer 必须位于源命令应用 Entry 之后。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    ChildTurn earlier =
        insertCompletedChildTurn(childId, baseline.sessionId(), baseline.rootEntryId());
    ChildTurn source = insertCompletedChildTurn(childId, baseline.sessionId(), earlier.turnEndId());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          ThreadCommand queued = queuedCommand(tx, childId, 1L);
          tx.updateCommands(List.of(queued.markApplied(source.turnStartId())));
        });

    // 正向：terminal 为子线程 Turn 的 TURN_END，final answer 为源应用之后的 ASSISTANT。
    UUID acceptedInvocation = TestIds.id(200);
    insertJoin(childId, acceptedInvocation, 1L);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(
              tx.findJoin(acceptedInvocation)
                  .orElseThrow()
                  .match(source.turnEndId(), source.assistantId(), T2));
        });
    ThreadJoin stored = store.transaction(tx -> tx.findJoin(acceptedInvocation).orElseThrow());
    assertEquals(source.turnEndId(), stored.terminalEntryId());
    assertEquals(source.assistantId(), stored.finalAnswerEntryId());

    // 负向：final answer 位于源应用 Entry 之前（更早 Turn 的 ASSISTANT，位于 terminal 路径但早于源）。
    UUID earlyInvocation = TestIds.id(201);
    insertJoin(childId, earlyInvocation, 1L);
    assertJoinRejected(
        "join final answer entry must follow the applied source entry",
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(
                      tx.findJoin(earlyInvocation)
                          .orElseThrow()
                          .match(source.turnEndId(), earlier.assistantId(), T2));
                }));

    // 负向：terminal TURN_END 由其它线程拥有。
    ChildTurn foreignTurn =
        insertCompletedChildTurn(baseline.threadId(), baseline.sessionId(), source.turnEndId());
    UUID foreignInvocation = TestIds.id(202);
    insertJoin(childId, foreignInvocation, 1L);
    assertJoinRejected(
        "join terminal turn must be owned by the child thread",
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(
                      tx.findJoin(foreignInvocation)
                          .orElseThrow()
                          .match(foreignTurn.turnEndId(), foreignTurn.assistantId(), T2));
                }));

    // 负向：源命令尚未 APPLIED 时不允许冻结 ASSISTANT final answer。
    insertCommand(childId, 2L, TestIds.id(2));
    UUID unappliedInvocation = TestIds.id(203);
    insertJoin(childId, unappliedInvocation, 2L);
    assertJoinRejected(
        "join final answer requires an applied source command",
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(
                      tx.findJoin(unappliedInvocation)
                          .orElseThrow()
                          .match(source.turnEndId(), source.assistantId(), T2));
                }));
  }

  /** 断言 Join 更新被拒绝且异常信息包含给定片段。 */
  private static void assertJoinRejected(String fragment, Runnable action) {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, action::run);
    assertTrue(ex.getMessage().contains(fragment), () -> "unexpected message: " + ex.getMessage());
  }

  /** 子线程自有 Turn 的三个定位 Entry（TURN_START / ASSISTANT 结果 / TURN_END）。 */
  private record ChildTurn(UUID turnStartId, UUID assistantId, UUID turnEndId) {}

  /** 在 {@code parentEntryId} 下插入一个结构合法的 COMPLETED Turn，其 TURN_START 由 {@code ownerThreadId} 拥有。 */
  private ChildTurn insertCompletedChildTurn(
      UUID ownerThreadId, UUID sessionId, UUID parentEntryId) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          tx.insertEntry(turnStartEntry(turnStartId, sessionId, parentEntryId, ownerThreadId, T1));
          UUID userId = tx.nextId();
          tx.insertEntry(new Entry(userId, sessionId, turnStartId, userMessagePayload(), T1));
          UUID assistantId = tx.nextId();
          tx.insertEntry(assistantEntry(assistantId, sessionId, userId, T1));
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  assistantId,
                  new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          return new ChildTurn(turnStartId, assistantId, turnEndId);
        });
  }

  /** 按 sequence 取队列命令。 */
  private static ThreadCommand queuedCommand(
      HarnessStore.Transaction tx, UUID threadId, long sequence) {
    return tx.loadQueuedCommands(threadId).stream()
        .filter(command -> command.sequence() == sequence)
        .findFirst()
        .orElseThrow();
  }

  /** 写入一个未匹配的 Join 回执。 */
  private void insertJoin(UUID childThreadId, UUID invocationId, long sourceCommandSequence) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childThreadId);
          tx.insertJoin(
              initialJoin(invocationId, baseline.threadId(), childThreadId, sourceCommandSequence));
        });
  }
}
