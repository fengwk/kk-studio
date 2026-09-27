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
                  ThreadLifecycleStatus.IDLE,
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
    // 测试意图：验证 deleteJoinsByChild 仅删除指定子线程的全部 join，需要子线程锁，并返回删除行数。
    UUID childId1 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    UUID childId2 =
        createChildThread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId());
    insertCommand(childId1, 1L, TestIds.id(1));
    insertCommand(childId1, 2L, TestIds.id(2));
    insertCommand(childId2, 1L, TestIds.id(3));

    UUID id1 = TestIds.id(401);
    UUID id2 = TestIds.id(402);
    UUID id3 = TestIds.id(403);

    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId1);
          tx.insertJoin(initialJoin(id1, baseline.threadId(), childId1, 1L, 0L));
          tx.insertJoin(initialJoin(id2, baseline.threadId(), childId1, 2L, 0L));
          tx.lockThread(childId2);
          tx.insertJoin(initialJoin(id3, baseline.threadId(), childId2, 1L, 0L));
        });

    // 未锁定 childId1 时调用 deleteJoinsByChild 抛异常
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.deleteJoinsByChild(childId1)));

    // 锁定后删除 childId1 的 join
    int deleted =
        store.transaction(
            tx -> {
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
                  tx.lockThread(baseline.threadId());
                  tx.deleteThreads(List.of(baseline.threadId()));
                }));

    // 显式清理 Join 后，deleteThreads 成功删除
    inTransaction(
        store,
        tx -> {
          tx.lockThread(childId);
          tx.deleteJoinsByChild(childId);
          tx.deleteThreads(List.of(childId));
        });

    assertTrue(store.transaction(tx -> tx.findThread(childId)).isEmpty());
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
}
