package fun.fengwk.kkstudio.harness.runtime.store.testing;

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
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.Arrays;
import java.util.List;

/**
 * Command mailbox constraints: unique keys, queued ordering, terminal markers and immutable
 * identity.
 */
class InMemoryCommandTest {

  private InMemoryHarnessStore store;
  private Baseline baseline;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    baseline = seedThreadBaseline(store);
  }

  @Test
  void loadQueuedCommandsReturnsOnlyQueuedCommandsSortedBySequence() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(1, baseline.threadId(), 2, "client-b"),
                  command(2, baseline.threadId(), 1, "client-a")));
        });
    List<Long> queuedIds =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId()).stream()
                  .map(ThreadCommand::id)
                  .toList();
            });
    assertEquals(List.of(2L, 1L), queuedIds);
  }

  @Test
  void queuedCommandsOfOtherThreadsAreNotLoaded() {
    long otherThreadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(new ThreadState(id, baseline.rootEntryId(), false, 1, 0, T1, T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
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
                  command(1, baseline.threadId(), 1, "client-a"),
                  command(2, baseline.threadId(), 2, "client-b")));
        });
    long turnStartEntryId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
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
                  withCancelledAt(queued.get(1), T2)));
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
                tx -> tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow())
            .state());
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx -> tx.findCommandByClientId(baseline.threadId(), "client-b").orElseThrow())
            .state());
  }

  @Test
  void duplicateCommandIdIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(1, baseline.threadId(), 2, "client-b")));
                }));
  }

  @Test
  void duplicateThreadSequenceIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(2, baseline.threadId(), 1, "client-b")));
                }));
  }

  @Test
  void duplicateClientCommandIdIsRejected() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertCommands(List.of(command(2, baseline.threadId(), 2, "client-a")));
                }));
  }

  @Test
  void sameClientCommandIdOnDifferentThreadsIsAllowed() {
    long otherThreadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(new ThreadState(id, baseline.rootEntryId(), false, 1, 0, T1, T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.lockThread(otherThreadId);
          tx.insertCommands(
              List.of(
                  command(1, baseline.threadId(), 1, "client-a"),
                  command(2, otherThreadId, 1, "client-a")));
        });
    assertTrue(
        store.transaction(tx -> tx.findCommandByClientId(otherThreadId, "client-a")).isPresent());
  }

  @Test
  void findCommandByClientIdScopesByThread() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), "client-a"))
            .isPresent());
    assertTrue(
        store
            .transaction(tx -> tx.findCommandByClientId(baseline.threadId(), "missing"))
            .isEmpty());
    assertTrue(store.transaction(tx -> tx.findCommandByClientId(999, "client-a")).isEmpty());
  }

  @Test
  void updateCommandsRequiresLockFromLoadQueuedCommands() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ThreadCommand stored =
                      tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow();
                  tx.updateCommands(List.of(withConsumedTurnStart(stored, 77)));
                }));
  }

  @Test
  void updateCommandsRejectsIdentityChanges() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    ThreadCommand stored =
        store.transaction(
            tx -> tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow());
    List<ThreadCommand> forged =
        Arrays.asList(
            new ThreadCommand(
                stored.id(),
                stored.threadId() + 1,
                stored.sequence(),
                stored.payload(),
                stored.clientCommandId(),
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.id(),
                stored.threadId(),
                stored.sequence() + 1,
                stored.payload(),
                stored.clientCommandId(),
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.id(),
                stored.threadId(),
                stored.sequence(),
                stored.payload(),
                "other-client",
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.id(),
                stored.threadId(),
                stored.sequence(),
                new UserMessageCommandPayload(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("other message")))),
                stored.clientCommandId(),
                null,
                null,
                stored.createdAt()),
            new ThreadCommand(
                stored.id(),
                stored.threadId(),
                stored.sequence(),
                stored.payload(),
                stored.clientCommandId(),
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
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
          ThreadCommand inserted =
              tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow();
          tx.updateCommands(List.of(withCancelledAt(inserted, T2)));
          return null;
        });
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx -> tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow())
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
                        Arrays.asList(command(1, baseline.threadId(), 1, "client-a"), null))));
  }

  @Test
  void loadQueuedCommandsReturnsAnImmutableList() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    List<ThreadCommand> queued =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId());
              return tx.loadQueuedCommands(baseline.threadId());
            });
    assertThrows(
        UnsupportedOperationException.class,
        () -> queued.add(command(9, baseline.threadId(), 9, "client-zz")));
  }

  @Test
  void insertCommandsRequiresExistingThread() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(store, tx -> tx.insertCommands(List.of(command(1, 999, 1, "client-a")))));
  }

  @Test
  void commandMailboxOperationsRequireTheThreadLockFirst() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    // load without the thread lock is rejected (lock order Thread -> commands)
    assertThrows(
        IllegalStateException.class,
        () -> store.transaction(tx -> tx.loadQueuedCommands(baseline.threadId())));
    // insert without the thread lock is rejected
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.insertCommands(List.of(command(9, baseline.threadId(), 9, "client-z")))));
    // the locked path works
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
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    long turnStartEntryId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    // QUEUED -> QUEUED is not a lifecycle transition
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
    // QUEUED -> APPLIED then terminal transitions are fenced in the same transaction
    long otherTurnStartEntryId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          ThreadCommand applied = withConsumedTurnStart(queued, turnStartEntryId);
          tx.updateCommands(List.of(applied));
          // APPLIED -> QUEUED
          assertThrows(IllegalArgumentException.class, () -> tx.updateCommands(List.of(queued)));
          // APPLIED -> CANCELLED
          assertThrows(
              IllegalArgumentException.class,
              () -> tx.updateCommands(List.of(withCancelledAt(applied, T2))));
          // APPLIED -> APPLIED with a different consumed marker (a valid same-session TURN_START)
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  tx.updateCommands(List.of(withConsumedTurnStart(queued, otherTurnStartEntryId))));
          // APPLIED -> APPLIED exact idempotent replay is accepted
          tx.updateCommands(List.of(applied));
        });
    // CANCELLED -> CANCELLED exact idempotent replay is accepted
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(2, baseline.threadId(), 2, "client-b")));
          ThreadCommand queued = tx.loadQueuedCommands(baseline.threadId()).get(0);
          ThreadCommand cancelled = withCancelledAt(queued, T2);
          tx.updateCommands(List.of(cancelled));
          tx.updateCommands(List.of(cancelled));
        });
    assertEquals(
        ThreadCommandState.CANCELLED,
        store
            .transaction(
                tx -> tx.findCommandByClientId(baseline.threadId(), "client-b").orElseThrow())
            .state());
  }

  @Test
  void insertCommandsAcceptsOnlyQueuedCommands() {
    ThreadCommand applied =
        withConsumedTurnStart(command(1, baseline.threadId(), 1, "client-a"), 77);
    ThreadCommand cancelled = withCancelledAt(command(2, baseline.threadId(), 2, "client-b"), T2);
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertCommands(List.of(applied))));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertCommands(List.of(cancelled))));
  }

  @Test
  void updateCommandsConsumedTurnStartMustReferenceSameSessionTurnStart() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(List.of(command(1, baseline.threadId(), 1, "client-a")));
        });
    // a USER MESSAGE (under a TURN_START) is not a TURN_START entry
    long userEntryId =
        store.transaction(
            tx -> {
              long turnStartId = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
              long id = tx.nextId();
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
    // a TURN_START in another session does not match the thread head session
    Baseline other = seedThreadBaseline(store);
    long otherTurnStart =
        store.transaction(
            tx -> {
              long id = tx.nextId();
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
    // a TURN_START in the thread head session is accepted
    long turnStartEntryId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
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
                tx -> tx.findCommandByClientId(baseline.threadId(), "client-a").orElseThrow())
            .state());
  }
}
