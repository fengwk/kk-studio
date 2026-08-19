package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

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
            canonical.clientCommandId(),
            canonical.requestHash(),
            canonical.consumedTurnStartEntryId(),
            canonical.cancelRequestId(),
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
                tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow())
            .state());
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(2)).orElseThrow())
            .state());
    ThreadCommand cancelled =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(2)).orElseThrow());
    // cancelRequestId 回单原样持久化（PostgreSQL cancel_request_id 列映射）。
    assertEquals(TestIds.id(1), cancelled.cancelRequestId());
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
  void duplicateClientCommandIdIsRejected() {
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
  void sameClientCommandIdOnDifferentThreadsIsAllowed() {
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
            .transaction(tx -> tx.findCommandByClientId(otherThreadId, TestIds.id(1)))
            .isPresent());
  }

  @Test
  void findCommandByClientIdScopesByThread() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(baseline.threadId(), 1, TestIds.id(1))));
        });
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)))
            .isPresent());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(999)))
            .isEmpty());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(TestIds.id(999), TestIds.id(1)))
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
                      tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow();
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
            tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow());
    List<ThreadCommand> forged =
        Arrays.asList(
            new ThreadCommand(
                TestIds.id(999), // for threadId must match stored
                stored.sequence(),
                stored.payload(),
                stored.clientCommandId(),
                stored.requestHash(),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence() + 1,
                stored.payload(),
                stored.clientCommandId(),
                stored.requestHash(),
                null,
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.threadId(),
                stored.sequence(),
                stored.payload(),
                TestIds.id(888), // different clientCommandId
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
                stored.clientCommandId(),
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
                stored.clientCommandId(),
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
              tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow();
          tx.updateCommands(List.of(withCancelledAt(inserted, TestIds.id(1), T2)));
          return null;
        });
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow())
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
                tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(2)).orElseThrow())
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
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)))
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
                tx -> tx.findCommandByClientId(baseline.threadId(), TestIds.id(1)).orElseThrow())
            .state());
  }

  /**
   * loadCancelledCommandsByRequest：只返回 (threadId, cancelRequestId) 精确匹配的 CANCELLED 行，且按 sequence
   * 升序。
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
}
