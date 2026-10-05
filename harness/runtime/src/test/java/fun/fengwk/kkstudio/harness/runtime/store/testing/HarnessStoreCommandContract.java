package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.notificationCommand;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.notificationEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.withCancelledAt;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.withConsumedTurnStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Command mailbox 约束：唯一 key、queued 顺序、terminal marker 与不可变身份。 */
public abstract class HarnessStoreCommandContract {

  private HarnessStore store;
  private Baseline baseline;

  @BeforeEach
  void setUp() {
    store = createStore();
    baseline = seedThreadBaseline(store);
  }

  abstract HarnessStore createStore();

  @Test
  void loadQueuedCommandsReturnsOnlyQueuedCommandsSortedBySequence() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 2, TestIds.id(1)),
                  command(baseline.threadId(), 1, TestIds.id(2))));
        });
    List<ThreadCommand> queued =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId());
            });
    // 按 sequence 升序：sequence=1 在前，sequence=2 在后
    assertEquals(
        List.of(
            command(baseline.threadId(), 1, TestIds.id(2)),
            command(baseline.threadId(), 2, TestIds.id(1))),
        queued);
  }

  @Test
  void commandTimestampsRejectSubMillisecondPrecision() {
    ThreadCommand canonical = command(baseline.threadId(), 1, TestIds.id(1));
    ThreadCommand command =
        new ThreadCommand(
            canonical.threadId(),
            canonical.sequence(),
            canonical.payload(),
            canonical.idempotencyKey(),
            canonical.requestHash(),
            canonical.appliedEntryId(),
            canonical.stopRequestId(),
            canonical.cancelledAt(),
            canonical.createdAt().plusNanos(1));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.insertCommands(List.of(command));
                }));
  }

  @Test
  void queuedCommandsOfOtherThreadsAreNotLoaded() {
    UUID otherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.threadState(
                      id, baseline.sessionId(), baseline.rootEntryId(), 1, 0, T1, T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(otherThreadId);
              return tx.loadQueuedCommands(otherThreadId).isEmpty();
            }));
  }

  @Test
  void appliedOrCancelledCommandsDropOutOfQueuedLoad() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 1, TestIds.id(1)),
                  command(baseline.threadId(), 2, TestIds.id(2))));
        });
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          List<ThreadCommand> queued = tx.loadQueuedCommands(baseline.threadId());
          tx.updateCommands(
              List.of(
                  withConsumedTurnStart(queued.get(0), turnStartEntryId),
                  withCancelledAt(queued.get(1), TestIds.id(1), T2)));
        });
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId()).isEmpty();
            }));
    assertEquals(
        ThreadCommandState.APPLIED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                        .orElseThrow())
            .state());
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(2))
                        .orElseThrow())
            .state());
    ThreadCommand cancelled =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(2)).orElseThrow());
    // stopRequestId 回单原样持久化（PostgreSQL stop_request_id 列映射）。
    assertEquals(TestIds.id(1), cancelled.stopRequestId());
    assertEquals(T2, cancelled.cancelledAt());
  }

  @Test
  void duplicateCommandIdIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(baseline.threadId(), 2, TestIds.id(1))));
                }));
  }

  @Test
  void duplicateThreadSequenceIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(2))));
                }));
  }

  @Test
  void duplicateIdempotencyKeyIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(baseline.threadId(), 2, TestIds.id(1))));
                }));
  }

  @Test
  void sameIdempotencyKeyOnDifferentThreadsIsAllowed() {
    UUID otherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.threadState(
                      id, baseline.sessionId(), baseline.rootEntryId(), 1, 0, T1, T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.lockThread(otherThreadId);
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 1, TestIds.id(1)),
                  command(otherThreadId, 1, TestIds.id(1))));
        });
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByIdempotencyKey(otherThreadId, TestIds.id(1)))
            .isPresent());
  }

  @Test
  void findCommandByIdempotencyKeyScopesByThread() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)))
            .isPresent());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(999)))
            .isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByIdempotencyKey(TestIds.id(999), TestIds.id(1)))
            .isEmpty());
  }

  @Test
  void updateCommandsRequiresLockFromLoadQueuedCommands() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ThreadCommand stored =
                      tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                          .orElseThrow();
                  tx.updateCommands(List.of(withConsumedTurnStart(stored, TestIds.id(77))));
                }));
  }

  @Test
  void updateCommandsRejectsIdentityChanges() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    ThreadCommand stored =
        store.transaction(
            tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)).orElseThrow());
    List<ThreadCommand> forged =
        Arrays.asList(
            new ThreadCommand(
                TestIds.id(999), // for threadId must match stored
                stored.sequence(),
                stored.payload(),
                stored.idempotencyKey(),
                stored.requestHash(),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence() + 1,
                stored.payload(),
                stored.idempotencyKey(),
                stored.requestHash(),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence(),
                stored.payload(),
                TestIds.id(888), // different idempotencyKey
                stored.requestHash(),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence(),
                new UserMessageCommandPayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("other message")))),
                stored.idempotencyKey(),
                ThreadCommandPayloadJsonCodec.requestHash(
                    new UserMessageCommandPayload(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new TextMessageContent("other message"))))),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence(),
                stored.payload(),
                stored.idempotencyKey(),
                stored.requestHash(),
                null,
                null,
                null,
                T1));
    for (ThreadCommand forgedRow : forged) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inTransaction(
                  store,
                  tx -> {
                    tx.lockThread(baseline.threadId());
                    tx.loadQueuedCommands(baseline.threadId());
                    tx.updateCommands(List.of(forgedRow));
                  }),
          "expected identity rejection for " + forgedRow);
    }
  }

  @Test
  void insertThenUpdateInTheSameTransactionIsAllowed() {
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
          ThreadCommand inserted =
              tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)).orElseThrow();
          tx.updateCommands(List.of(withCancelledAt(inserted, TestIds.id(1), T2)));
          return null;
        });
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                        .orElseThrow())
            .state());
  }

  @Test
  void insertCommandsDefensivelyCopiesAndRejectsNullElements() {
    assertThrows(
        NullPointerException.class, () -> inTransaction(store, tx -> tx.insertCommands(null)));
    assertThrows(
        NullPointerException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertCommands(
                        Arrays.asList(command(baseline.threadId(), 1, TestIds.id(1)), null))));
  }

  @Test
  void loadQueuedCommandsReturnsAnImmutableList() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    List<ThreadCommand> queued =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId());
            });
    assertThrows(
        UnsupportedOperationException.class,
        () -> queued.add(command(baseline.threadId(), 9, TestIds.id(9))));
  }

  @Test
  void insertCommandsRequiresExistingThread() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.insertCommands(List.of(command(TestIds.id(999), 1, TestIds.id(1))))));
  }

  @Test
  void findCommandLocatesExactlyTheThreadScopedSequence() {
    // 测试意图：验证 findCommand 按 (threadId, sequence) 直接定位单条 Command：命中该 Thread 的唯一 sequence，
    // 另一 Thread 的同 sequence 与不存在的 sequence 均返回 empty；离开 QUEUED（已消费或已取消）后行仍可被读到。
    Baseline other = seedThreadBaseline(store);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 1, TestIds.id(1)),
                  command(baseline.threadId(), 2, TestIds.id(2))));
        });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(other.threadId());
          tx.insertCommands(List.of(command(other.threadId(), 1, TestIds.id(3))));
        });

    store.transaction(
        tx -> {
          ThreadCommand first = tx.findCommand(baseline.threadId(), 1).orElseThrow();
          assertEquals(1L, first.sequence());
          assertEquals(TestIds.id(1), first.idempotencyKey());
          ThreadCommand second = tx.findCommand(baseline.threadId(), 2).orElseThrow();
          assertEquals(TestIds.id(2), second.idempotencyKey());
          // 同名 sequence 在另一 Thread 上是不同行
          assertEquals(
              TestIds.id(3), tx.findCommand(other.threadId(), 1).orElseThrow().idempotencyKey());
          // 不存在的 sequence 与不存在的 Thread 都返回 empty
          assertTrue(tx.findCommand(baseline.threadId(), 99).isEmpty());
          assertTrue(tx.findCommand(TestIds.id(999), 1).isEmpty());
          return null;
        });

    // 终态 marker 之后仍然可直接按 PK 读到 durable 行（findCommand 不做 queued 过滤）
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          tx.updateCommands(List.of(withCancelledAt(queued, TestIds.id(7), T2)));
          return null;
        });
    store.transaction(
        tx -> {
          assertEquals(
              ThreadCommandState.CANCELLED,
              tx.findCommand(baseline.threadId(), 1).orElseThrow().state());
          return null;
        });
  }

  @Test
  void commandLockRankExceptionRequiresTheSameExecutionTree() {
    // 测试意图：验证 COMMAND 的锁阶梯例外严格限定在同一执行树内——在已获取高阶 WORK 锁之后回退写 COMMAND 时，
    // 只有该 COMMAND 所属 Thread 落在已持有的树锁内才被允许；仅仅持有另一棵树的树锁不构成例外，必须拒绝。
    Baseline other = seedThreadBaseline(store);
    WorkTarget otherWork = new WorkTarget(WorkTargetType.THREAD, other.threadId());

    // 只持有 baseline 树的树锁：对其它树的 Thread 回退写 COMMAND 必须拒绝
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockTree(baseline.threadId());
                  tx.lockThread(other.threadId());
                  tx.requestWork(otherWork, T0);
                  tx.insertCommands(List.of(command(other.threadId(), 1, TestIds.id(11))));
                }));
    assertTrue(store.transaction(tx -> tx.findCommand(other.threadId(), 1)).isEmpty());

    // 同时持有两棵树的树锁（升序）：同一回退写 COMMAND 合法
    inTransaction(
        store,
        tx -> {
          for (UUID root :
              List.of(baseline.threadId(), other.threadId()).stream()
                  .sorted(UuidOrder.COMPARATOR)
                  .toList()) {
            tx.lockTree(root);
          }
          tx.lockThread(other.threadId());
          tx.requestWork(otherWork, T0);
          tx.insertCommands(List.of(command(other.threadId(), 1, TestIds.id(11))));
        });
    assertTrue(store.transaction(tx -> tx.findCommand(other.threadId(), 1)).isPresent());
  }

  @Test
  void commandRankRejectionHappensBeforeAnyInsertEvenWhenCaught() {
    // 测试意图：锁序拒绝必须发生在 SQL/内存写入之前，调用方捕获校验异常也不能提交半条命令。
    Baseline other = seedThreadBaseline(store);
    inTransaction(
        store,
        tx -> {
          tx.lockTree(baseline.threadId());
          tx.lockThread(other.threadId());
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, other.threadId()), T0);
          assertThrows(
              IllegalStateException.class,
              () -> tx.insertCommands(List.of(command(other.threadId(), 1, TestIds.id(11)))));
          assertTrue(tx.findCommand(other.threadId(), 1).isEmpty());
        });
    assertTrue(store.transaction(tx -> tx.findCommand(other.threadId(), 1)).isEmpty());
  }

  @Test
  void commandMailboxOperationsRequireTheThreadLockFirst() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    // 未持有 thread lock 的 load 会被拒绝，锁序 Thread -> commands
    assertThrows(
        IllegalStateException.class,
        () -> store.transaction(tx -> tx.loadQueuedCommands(baseline.threadId())));
    // 未持有 thread lock 的 insert 会被拒绝
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.insertCommands(List.of(command(baseline.threadId(), 9, TestIds.id(9))))));
    // 已锁定路径可正常工作
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId()).size() == 1;
            }));
  }

  @Test
  void updateCommandsLifecycleIsRestrictedToQueuedTerminalTransitions() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });
    // QUEUED -> QUEUED 不是生命周期转换
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(queued));
                }));
    // 同事务内的 QUEUED -> APPLIED 之后的 terminal 转换会被拦下
    UUID otherTurnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          ThreadCommand applied = withConsumedTurnStart(queued, turnStartEntryId);
          tx.updateCommands(List.of(applied));
          // APPLIED -> QUEUED（回退）
          assertThrows(IllegalArgumentException.class, () -> tx.updateCommands(List.of(queued)));
          // APPLIED -> CANCELLED（跨到 CANCELLED）
          assertThrows(
              IllegalArgumentException.class,
              () -> tx.updateCommands(List.of(withCancelledAt(applied, TestIds.id(1), T2))));
          // APPLIED 改为带不同 consumed marker 的同 session TURN_START
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  tx.updateCommands(List.of(withConsumedTurnStart(queued, otherTurnStartEntryId))));
          // APPLIED 的精确 idempotent replay 可接受
          tx.updateCommands(List.of(applied));
        });
    // CANCELLED 的精确 idempotent replay 可接受
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 2, TestIds.id(2))));
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          ThreadCommand cancelled = withCancelledAt(queued, TestIds.id(1), T2);
          tx.updateCommands(List.of(cancelled));
          tx.updateCommands(List.of(cancelled));
        });
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(2))
                        .orElseThrow())
            .state());
  }

  @Test
  void insertCommandsAcceptsOnlyQueuedCommands() {
    ThreadCommand applied =
        withConsumedTurnStart(command(baseline.threadId(), 1, TestIds.id(1)), TestIds.id(77));
    ThreadCommand cancelled =
        withCancelledAt(command(baseline.threadId(), 2, TestIds.id(2)), TestIds.id(1), T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertCommands(List.of(applied))));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertCommands(List.of(cancelled))));
  }

  @Test
  void caughtLateInsertValidationFailureDoesNotCommitAnEarlierBatchItem() {
    ThreadCommand valid = command(baseline.threadId(), 1, TestIds.id(1));
    ThreadCommand invalid =
        withCancelledAt(command(baseline.threadId(), 2, TestIds.id(2)), TestIds.id(1), T2);
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          try {
            tx.insertCommands(List.of(valid, invalid));
          } catch (IllegalArgumentException ignored) {
            // 批量方法必须先完成所有 item 的校验，再进行第一次 mutation
          }
          return null;
        });
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1)))
            .isEmpty());
  }

  @Test
  void caughtLateUpdateValidationFailureDoesNotCommitAnEarlierBatchItem() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 1, TestIds.id(1)),
                  command(baseline.threadId(), 2, TestIds.id(2))));
        });
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });

    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          List<ThreadCommand> queued = tx.loadQueuedCommands(baseline.threadId());
          try {
            tx.updateCommands(
                List.of(withConsumedTurnStart(queued.get(0), turnStartEntryId), queued.get(1)));
          } catch (IllegalArgumentException ignored) {
            // 第二个 item 仍为 QUEUED -> QUEUED 时，第一个 item 不应被 applied
          }
          return null;
        });

    List<ThreadCommandState> states =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId()).stream()
                  .map(ThreadCommand::state)
                  .toList();
            });
    assertEquals(List.of(ThreadCommandState.QUEUED, ThreadCommandState.QUEUED), states);
  }

  @Test
  void updateCommandsConsumedTurnStartMustReferenceSameSessionTurnStart() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    // 位于 TURN_START 之下的 USER MESSAGE 不是 TURN_START Entry
    UUID userEntryId =
        store.transaction(
            tx -> {
              UUID turnStartId = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(id, baseline.sessionId(), turnStartId, userMessagePayload(), T1));
              return id;
            });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(withConsumedTurnStart(queued, userEntryId)));
                }));
    // 其他 session 中的 TURN_START 不匹配 thread 当前 head session
    Baseline other = seedThreadBaseline(store);
    UUID otherTurnStart =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, other.sessionId(), other.rootEntryId(), T1));
              return id;
            });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(withConsumedTurnStart(queued, otherTurnStart)));
                }));
    // thread 当前 head session 中的 TURN_START 可接受
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          tx.updateCommands(List.of(withConsumedTurnStart(queued, turnStartEntryId)));
        });
    assertEquals(
        ThreadCommandState.APPLIED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                        .orElseThrow())
            .state());
  }

  /**
   * loadCancelledCommandsByRequest：只返回 (threadId, stopRequestId) 精确匹配的 CANCELLED 行，且按 sequence 升序。
   */
  @Test
  void loadCancelledCommandsByRequestIsScopedByThreadRequestAndOrdered() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), 1, TestIds.id(1)),
                  command(baseline.threadId(), 2, TestIds.id(2)),
                  command(baseline.threadId(), 3, TestIds.id(3)),
                  command(baseline.threadId(), 4, TestIds.id(4))));
        });
    UUID otherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.threadState(
                      id, baseline.sessionId(), baseline.rootEntryId(), 1, 0, T1, T1));
              return id;
            });
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              tx.lockThread(otherThreadId);
              ThreadCommand foreign = command(otherThreadId, 1, TestIds.id(9));
              tx.insertCommands(List.of(foreign));
              tx.updateCommands(List.of(withCancelledAt(foreign, TestIds.id(1), T2)));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          List<ThreadCommand> queued = tx.loadQueuedCommands(baseline.threadId());
          // seq1 -> APPLIED；seq2 与 seq3 -> 同一 stop；seq4 -> 另一 stop。
          tx.updateCommands(
              List.of(
                  withConsumedTurnStart(queued.get(0), turnStartEntryId),
                  withCancelledAt(queued.get(1), TestIds.id(1), T2),
                  withCancelledAt(queued.get(2), TestIds.id(1), T2),
                  withCancelledAt(queued.get(3), TestIds.id(2), T2)));
        });
    assertEquals(
        List.of(
            command(baseline.threadId(), 2, TestIds.id(2)).cancel(TestIds.id(1), T2),
            command(baseline.threadId(), 3, TestIds.id(3)).cancel(TestIds.id(1), T2)),
        store.transaction(
            tx -> tx.loadCancelledCommandsByRequest(baseline.threadId(), TestIds.id(1))));
    // 另一 stop 的取消行不混入；另一 Thread 的相同 raw id 不混入。
    assertEquals(
        List.of(command(baseline.threadId(), 4, TestIds.id(4)).cancel(TestIds.id(2), T2)),
        store.transaction(
            tx -> tx.loadCancelledCommandsByRequest(baseline.threadId(), TestIds.id(2))));
    assertTrue(
        store
            .<Boolean>transaction(
                tx ->
                    tx.loadCancelledCommandsByRequest(otherThreadId, TestIds.id(1)).stream()
                        .allMatch(c -> c.threadId().equals(otherThreadId)))
            .equals(Boolean.TRUE));
  }

  /** listThreadsBySession：按 (created_at, id) 确定性序返回同 Session 的 Thread，跨 Session 不混入。 */
  @Test
  void listThreadsBySessionOrdersByCreatedAtThenId() {
    UUID laterThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.threadState(
                      id, baseline.sessionId(), baseline.rootEntryId(), 1, 0, T1, T1));
              return id;
            });
    UUID sameTimeHigherId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(
                  StoreTestSupport.threadState(
                      id, baseline.sessionId(), baseline.rootEntryId(), 1, 0, T0, T0));
              return id;
            });
    Baseline other = seedThreadBaseline(store);
    // createdAt 相同时按 id 升序，随后 createdAt 更大的排后；其他 Session 不列出。
    assertEquals(
        List.of(baseline.threadId(), sameTimeHigherId, laterThreadId),
        store.transaction(tx -> tx.listThreadsBySession(baseline.sessionId())).stream()
            .map(thread -> thread.id())
            .toList());
    assertTrue(
        store.transaction(tx -> tx.listThreadsBySession(other.sessionId())).stream()
            .noneMatch(thread -> thread.sessionId().equals(baseline.sessionId())));
  }

  @Test
  void notificationCommandMustMatchItsOwnNotificationEntry() {
    // 测试意图：NOTIFICATION 命令必须精确匹配同四字段的 NOTIFICATION Entry；其它命令不得引用 NOTIFICATION Entry。
    UUID notificationId = TestIds.id(41);
    UUID sourceThreadId = TestIds.id(42);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  notificationCommand(
                      baseline.threadId(),
                      1,
                      TestIds.id(1),
                      notificationId,
                      NotificationKind.SUBAGENT_RESULT,
                      sourceThreadId,
                      "delegated result")));
        });
    UUID matchingEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  notificationEntry(
                      id,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      notificationId,
                      NotificationKind.SUBAGENT_RESULT,
                      sourceThreadId,
                      "delegated result",
                      T1));
              return id;
            });
    // 正向：四字段一致的 NOTIFICATION Entry 可被应用。
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          tx.updateCommands(List.of(queued.markApplied(matchingEntryId)));
        });
    assertEquals(
        ThreadCommandState.APPLIED,
        store
            .transaction(
                tx ->
                    tx.findCommandByIdempotencyKey(baseline.threadId(), TestIds.id(1))
                        .orElseThrow())
            .state());

    // 负向：kind 不一致的 NOTIFICATION Entry 被拒绝。
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  notificationCommand(
                      baseline.threadId(),
                      2,
                      TestIds.id(2),
                      notificationId,
                      NotificationKind.SUBAGENT_RESULT,
                      sourceThreadId,
                      "delegated result")));
        });
    UUID mismatchedEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  notificationEntry(
                      id,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      notificationId,
                      NotificationKind.TASK_BUDGET,
                      sourceThreadId,
                      "delegated result",
                      T1));
              return id;
            });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(queued.markApplied(mismatchedEntryId)));
                }));

    // 负向：NOTIFICATION 命令不得引用非 NOTIFICATION Entry（TURN_START）。
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  notificationCommand(
                      baseline.threadId(),
                      3,
                      TestIds.id(3),
                      notificationId,
                      NotificationKind.SUBAGENT_RESULT,
                      sourceThreadId,
                      "delegated result")));
        });
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      id, baseline.sessionId(), baseline.rootEntryId(), baseline.threadId(), T1));
              return id;
            });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(queued.markApplied(turnStartEntryId)));
                }));

    // 负向：其它命令不得引用 NOTIFICATION Entry。
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 4, TestIds.id(4))));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
                  tx.updateCommands(List.of(queued.markApplied(matchingEntryId)));
                }));
  }
}
