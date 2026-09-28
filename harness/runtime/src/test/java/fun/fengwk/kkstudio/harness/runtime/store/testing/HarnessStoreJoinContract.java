package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
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

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

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
    return createChildThread(parentThreadId, sessionId, rootEntryId, ThreadLifecycleStatus.IDLE);
  }

  private UUID createChildThread(
      UUID parentThreadId, UUID sessionId, UUID rootEntryId, ThreadLifecycleStatus status) {
    return store.transaction(
        tx -> {
          UUID childId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  childId,
                  sessionId,
                  parentThreadId,
                  rootEntryId,
                  CREATION_REQUEST_HASH,
                  "child-thread",
                  false,
                  status,
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
      UUID invocationId,
      UUID parentThreadId,
      UUID childThreadId,
      long sourceCommandSequence,
      long afterVersion) {
    return new ThreadJoin(
        invocationId,
        REQUEST_HASH,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        afterVersion,
        "test-agent",
        10,
        0L,
        null,
        null,
        null,
        T0,
        T0);
  }

  private static ThreadJoin initialJoin(
      UUID invocationId,
      UUID parentThreadId,
      UUID childThreadId,
      long sourceCommandSequence,
      long afterVersion,
      Instant createdAt,
      Instant updatedAt) {
    return new ThreadJoin(
        invocationId,
        REQUEST_HASH,
        parentThreadId,
        childThreadId,
        sourceCommandSequence,
        afterVersion,
        "test-agent",
        10,
        0L,
        null,
        null,
        null,
        createdAt,
        updatedAt);
  }

  @Test
  void insertAndFindJoinRoundTripWithParent() {
    // 测试意图：验证携带 parentThreadId 的初始未匹配 ThreadJoin 可成功写入并完整读取全部字段。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(100);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

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
    ThreadJoin join = initialJoin(invocationId, null, baseline.threadId(), 1L, 0L);

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
        initialJoin(TestIds.id(102), baseline.threadId(), childId, 1L, 0L, T0.plusNanos(500), T1);
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
        initialJoin(TestIds.id(103), baseline.threadId(), childId, 1L, 0L, T0, T0.plusNanos(500));
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
    // 测试意图：验证 insertJoin 拒绝非初始（已 matched 或已 delivered 或带 reminder）的 Join。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));

    ThreadJoin matched =
        new ThreadJoin(
            TestIds.id(104),
            REQUEST_HASH,
            baseline.threadId(),
            childId,
            1L,
            0L,
            "test-agent",
            10,
            0L,
            1L,
            baseline.rootEntryId(),
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
  }

  @Test
  void insertJoinRejectsDuplicateInvocationId() {
    // 测试意图：验证 insertJoin 拒绝重复的 invocationId 主键冲突。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(105);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

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
    ThreadJoin join = initialJoin(TestIds.id(106), baseline.threadId(), nonExistentChildId, 1L, 0L);

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
    ThreadJoin join =
        initialJoin(TestIds.id(107), nonExistentParentId, baseline.threadId(), 1L, 0L);

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
    ThreadJoin join = initialJoin(TestIds.id(108), baseline.threadId(), childId, 1L, 0L);

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
    ThreadJoin join = initialJoin(TestIds.id(109), baseline.threadId(), childId, 1L, 0L);

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
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

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
    ThreadJoin join = initialJoin(TestIds.id(998), baseline.threadId(), childId, 1L, 0L);

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
    // 测试意图：验证 updateJoin 拒绝篡改不可变持久身份字段（如 agent、afterVersion 等）。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(111);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

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
            0L,
            "altered-agent",
            10,
            0L,
            null,
            null,
            null,
            T0,
            T1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(alteredAgent);
                }));
  }

  @Test
  void updateJoinRejectsUpdatedAtRegression() {
    // 测试意图：验证 updateJoin 拒绝 updatedAt 时间回退。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(112);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L, T1, T2);

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
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    // 产生子线程执行产生的结果 head entry（需在 open TURN_START 之内）
    UUID turnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), childId, T1));
              return id;
            });
    UUID resultHeadId =
        insertChildEntry(store, baseline.sessionId(), turnStartId, userMessagePayload());

    // 匹配 match
    ThreadJoin matched = join.match(1L, resultHeadId, T1);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.updateJoin(matched);
        });

    ThreadJoin storedMatched = store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertEquals(matched, storedMatched);
    assertTrue(storedMatched.matched());
    assertEquals(1L, storedMatched.matchedIdleVersion());
    assertEquals(resultHeadId, storedMatched.resultHeadEntryId());

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
  void updateJoinMatchRejectsNonExistentResultHeadEntry() {
    // 测试意图：验证 updateJoin 匹配时若 resultHeadEntryId 对应 Entry 不存在则抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(114);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    UUID nonExistentEntryId = TestIds.id(987);
    ThreadJoin matched = join.match(1L, nonExistentEntryId, T1);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(childId);
                  tx.updateJoin(matched);
                }));
  }

  @Test
  void updateJoinDeliveryRejectsNonExistentDeliveryCommand() {
    // 测试意图：验证 updateJoin 交付时若父线程不存在指定 delivery command sequence 则抛出 IllegalArgumentException。
    UUID childId =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId, 1L, TestIds.id(1));
    UUID invocationId = TestIds.id(115);
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(join);
        });

    ThreadJoin matched = join.match(1L, baseline.rootEntryId(), T1);
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
    ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);

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
  void loadMatchableJoinsFiltersAndSortsCorrectly() {
    // 测试意图：验证 loadMatchableJoins 只返回指定子线程且 matchedIdleVersion 为空、afterVersion < idleVersion 的
    // join，按 (createdAt, invocationId) 升序。
    UUID childId1 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    UUID childId2 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId1, 1L, TestIds.id(1));
    insertCommand(childId1, 2L, TestIds.id(2));
    insertCommand(childId2, 1L, TestIds.id(3));

    UUID id1 = TestIds.id(201);
    UUID id2 = TestIds.id(202);
    UUID id3 = TestIds.id(203);
    UUID id4 = TestIds.id(204);

    ThreadJoin join1 = initialJoin(id1, baseline.threadId(), childId1, 1L, 0L, T1, T1);
    ThreadJoin join2 =
        initialJoin(id2, baseline.threadId(), childId1, 2L, 5L, T0, T0); // afterVersion=5
    ThreadJoin join3 =
        initialJoin(id3, baseline.threadId(), childId1, 1L, 0L, T2, T2); // afterVersion=0
    ThreadJoin joinOtherChild = initialJoin(id4, baseline.threadId(), childId2, 1L, 0L, T0, T0);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.insertJoin(join1);
          tx.insertJoin(join2);
          tx.insertJoin(join3);
          tx.lockThread(childId2);
          tx.insertJoin(joinOtherChild);
        });

    // 当 idleVersion = 3 时，join2 (afterVersion=5) 不满足 afterVersion < idleVersion；joinOtherChild 属于不同
    // child
    List<ThreadJoin> matchable = store.transaction(tx -> tx.loadMatchableJoins(childId1, 3L));
    assertEquals(List.of(join1, join3), matchable);

    // 将 join1 标记为 matched 后，join1 不再出现在 loadMatchableJoins
    ThreadJoin matchedJoin1 = join1.match(2L, baseline.rootEntryId(), T3);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.updateJoin(matchedJoin1);
        });

    List<ThreadJoin> matchableAfterMatch =
        store.transaction(tx -> tx.loadMatchableJoins(childId1, 3L));
    assertEquals(List.of(join3), matchableAfterMatch);
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

    ThreadJoin join1 = initialJoin(id1, parentId1, childId, 1L, 0L, T0, T0);
    ThreadJoin join2 = initialJoin(id2, parentId1, childId, 2L, 0L, T1, T1);
    ThreadJoin join3 = initialJoin(id3, parentId2, childId, 3L, 0L, T2, T2);

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
    ThreadJoin matched1 = join1.match(1L, baseline.rootEntryId(), T2);
    ThreadJoin matched2 = join2.match(1L, baseline.rootEntryId(), T2);
    ThreadJoin matched3 = join3.match(1L, baseline.rootEntryId(), T2);

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
          ThreadJoin j1 = initialJoin(id1, baseline.threadId(), childId1, 1L, 0L);
          ThreadJoin j2 = initialJoin(id2, baseline.threadId(), childId1, 2L, 0L);
          tx.insertJoin(j1);
          tx.insertJoin(j2);
          tx.updateJoin(j1.match(1L, baseline.rootEntryId(), T1).delivered(1L, T2));
          tx.updateJoin(j2.match(1L, baseline.rootEntryId(), T1).delivered(2L, T2));

          tx.lockThread(childId2);
          ThreadJoin j3 = initialJoin(id3, baseline.threadId(), childId2, 1L, 0L);
          tx.insertJoin(j3);
          tx.updateJoin(j3.match(1L, baseline.rootEntryId(), T1).delivered(3L, T2));
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
          tx.insertJoin(initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L));
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
          tx.updateJoin(join.match(1L, baseline.rootEntryId(), T1).delivered(1L, T2));
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
          tx.insertJoin(initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L));
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
          ThreadJoin join = initialJoin(invocationId, baseline.threadId(), childId, 1L, 0L);
          tx.insertJoin(join);
          tx.updateJoin(join.match(1L, baseline.rootEntryId(), T1));
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
          tx.insertJoin(initialJoin(ticketId, null, rootId, 1L, 0L));
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
          tx.updateJoin(join.match(1L, baseline.rootEntryId(), T1));
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
          ThreadJoin j1 = initialJoin(joinA1, rootA, childA1, 1L, 0L);
          tx.insertJoin(j1);
          tx.updateJoin(j1.match(1L, baseline.rootEntryId(), T1).delivered(1L, T2));

          tx.lockThread(childA2);
          ThreadJoin j2 = initialJoin(joinA2, rootA, childA2, 1L, 0L);
          tx.insertJoin(j2);
          tx.updateJoin(j2.match(1L, baseline.rootEntryId(), T1).delivered(2L, T2));
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
          ThreadJoin jb = initialJoin(joinB, rootB, childB, 1L, 0L);
          tx.insertJoin(jb);
          tx.updateJoin(jb.match(1L, baselineB.rootEntryId(), T1).delivered(1L, T2));
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
  void countActiveChildrenCountsDirectNonIdleChildrenOnly() {
    // 测试意图：验证 countActiveChildren 只统计指定父线程的直接非空闲（ACTIVE 与 WAITING_CHILDREN）子线程数量。
    UUID rootId = baseline.threadId();
    UUID nonExistentId = store.transaction(HarnessStore.Transaction::nextId);

    store.transaction(
        tx -> {
          // 不存在的父线程返回 0
          assertEquals(0, tx.countActiveChildren(nonExistentId));
          // 无子线程的根线程返回 0
          assertEquals(0, tx.countActiveChildren(rootId));
          return null;
        });

    UUID childIdle =
        createChildThread(
            rootId, baseline.sessionId(), baseline.rootEntryId(), ThreadLifecycleStatus.IDLE);
    UUID childActive =
        createChildThread(
            rootId, baseline.sessionId(), baseline.rootEntryId(), ThreadLifecycleStatus.ACTIVE);
    UUID childWaiting =
        createChildThread(
            rootId,
            baseline.sessionId(),
            baseline.rootEntryId(),
            ThreadLifecycleStatus.WAITING_CHILDREN);
    // 间接后代（孙线程）处于 ACTIVE
    UUID grandChildActive =
        createChildThread(
            childIdle, baseline.sessionId(), baseline.rootEntryId(), ThreadLifecycleStatus.ACTIVE);

    store.transaction(
        tx -> {
          // root 的直接子线程中只有 childActive 与 childWaiting 处于非空闲状态（数量为 2，不含 grandChildActive）
          assertEquals(2, tx.countActiveChildren(rootId));
          // childIdle 的直接子线程包含 grandChildActive（数量为 1）
          assertEquals(1, tx.countActiveChildren(childIdle));
          // childActive 无子线程
          assertEquals(0, tx.countActiveChildren(childActive));
          return null;
        });
  }

  @Test
  void countActiveSubagentThreadsCountsActiveExecutionChildrenAcrossAllRoots() {
    // 测试意图：验证 countActiveSubagentThreads 全局统计所有拥有执行父关系（parentThreadId 非空）且非空闲的 Thread：
    // 跨 root 聚合、包含 ACTIVE 与 WAITING_CHILDREN、包含更深后代，并排除 root 自身与 IDLE 子 Thread。
    Baseline other = seedThreadBaseline(store);
    UUID rootA = baseline.threadId();
    UUID rootB = other.threadId();

    store.transaction(
        tx -> {
          assertEquals(0, tx.countActiveSubagentThreads());
          return null;
        });

    UUID childWaiting =
        createChildThread(
            rootA,
            baseline.sessionId(),
            baseline.rootEntryId(),
            ThreadLifecycleStatus.WAITING_CHILDREN);
    // IDLE 子 Thread 不占计数
    createChildThread(
        rootA, baseline.sessionId(), baseline.rootEntryId(), ThreadLifecycleStatus.IDLE);
    UUID grandChildActive =
        createChildThread(
            childWaiting,
            baseline.sessionId(),
            baseline.rootEntryId(),
            ThreadLifecycleStatus.ACTIVE);
    UUID otherTreeChild =
        createChildThread(
            rootB, other.sessionId(), other.rootEntryId(), ThreadLifecycleStatus.ACTIVE);

    // root 自身即使 ACTIVE 也不计入全局 subagent 计数
    inTransaction(
        store,
        tx ->
            tx.updateThread(
                tx.lockThread(rootA)
                    .orElseThrow()
                    .changeLifecycleStatus(ThreadLifecycleStatus.ACTIVE, T1)));

    store.transaction(
        tx -> {
          // childWaiting(WAITING) + grandChildActive(ACTIVE) + otherTreeChild(ACTIVE) = 3
          assertEquals(3, tx.countActiveSubagentThreads());
          return null;
        });

    inTransaction(
        store,
        tx ->
            tx.updateThread(
                tx.lockThread(childWaiting)
                    .orElseThrow()
                    .changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T2)));

    store.transaction(
        tx -> {
          // 父空闲但更深的后代仍活跃：grandChildActive + otherTreeChild = 2
          assertEquals(2, tx.countActiveSubagentThreads());
          return null;
        });

    inTransaction(
        store,
        tx ->
            tx.updateThread(
                tx.lockThread(otherTreeChild)
                    .orElseThrow()
                    .changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T3)));

    store.transaction(
        tx -> {
          assertEquals(1, tx.countActiveSubagentThreads());
          return null;
        });

    inTransaction(
        store,
        tx ->
            tx.updateThread(
                tx.lockThread(grandChildActive)
                    .orElseThrow()
                    .changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T3)));

    store.transaction(
        tx -> {
          assertEquals(0, tx.countActiveSubagentThreads());
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
  void busyChildDuplicateJoinsQuotaCountsDistinctActivePermanentThreadsNotJoins() {
    // 测试意图：验证并发配额原语按活跃永久线程（distinct active permanent threads）计数，
    // 而非按未匹配 Join 记录数计数。即使忙碌子线程挂载了多个未匹配 Join，活跃线程配额计数仍为 1；
    // 当子线程变为空闲后，活跃线程配额降为 0，即便 Join 记录仍处于未结算状态。
    UUID rootId = baseline.threadId();
    UUID childId =
        createChildThread(
            rootId, baseline.sessionId(), baseline.rootEntryId(), ThreadLifecycleStatus.ACTIVE);

    UUID inv1 = store.transaction(HarnessStore.Transaction::nextId);
    UUID inv2 = store.transaction(HarnessStore.Transaction::nextId);

    // 插入两条针对同一 childId 的未匹配 Join
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.insertCommands(
              List.of(command(childId, 1L, tx.nextId()), command(childId, 2L, tx.nextId())));
          tx.insertJoin(initialJoin(inv1, rootId, childId, 1L, 0L));
          tx.insertJoin(initialJoin(inv2, rootId, childId, 2L, 0L));
        });

    store.transaction(
        tx -> {
          // 活跃子线程原语：只有 1 个活跃子线程，不受 2 条 Join 记录影响
          assertEquals(1, tx.countActiveChildren(rootId));
          assertEquals(1, tx.countActiveSubagentThreads());
          return null;
        });

    // 子线程变为 WAITING_CHILDREN：仍被视为活跃（status != IDLE）
    inTransaction(
        store,
        tx -> {
          ThreadState child = tx.lockThread(childId).orElseThrow();
          tx.updateThread(child.changeLifecycleStatus(ThreadLifecycleStatus.WAITING_CHILDREN, T1));
        });

    store.transaction(
        tx -> {
          assertEquals(1, tx.countActiveChildren(rootId));
          assertEquals(1, tx.countActiveSubagentThreads());
          return null;
        });

    // 子线程变为 IDLE：活跃线程配额立即降为 0（即使未匹配 Join 仍存在）
    inTransaction(
        store,
        tx -> {
          ThreadState child = tx.lockThread(childId).orElseThrow();
          tx.updateThread(child.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T2));
        });

    store.transaction(
        tx -> {
          assertEquals(0, tx.countActiveChildren(rootId));
          assertEquals(0, tx.countActiveSubagentThreads());
          return null;
        });
  }

  @Test
  void countActiveChildrenRejectsNullArgument() {
    // 测试意图：验证 countActiveChildren 对 null 参数抛出 NullPointerException。
    assertThrows(
        NullPointerException.class, () -> inTransaction(store, tx -> tx.countActiveChildren(null)));
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
          ThreadJoin j = initialJoin(joinA, rootA, childA, 1L, 0L);
          tx.insertJoin(j);
          tx.updateJoin(j.match(1L, baseline.rootEntryId(), T1).delivered(1L, T2));
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
}
