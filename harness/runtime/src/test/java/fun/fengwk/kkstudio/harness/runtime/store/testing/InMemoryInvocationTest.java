package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T4;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantAbortedPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantErrorPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.modelRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.syntheticToolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.toolResultPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.turnStartPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.TurnBaseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * ModelInvocation / ToolInvocation FK, unique keys, result-entry type and branch constraints.
 *
 * <p>Fixture chains follow the turn protocol: every INPUT TURN_START needs a USER message before an
 * assistant result, and ToolResult entries append under their assistant entry (ordinal prefix).
 */
class InMemoryInvocationTest {

  private InMemoryHarnessStore store;
  private TurnBaseline baseline;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    baseline = seedTurnBaseline(store);
  }

  // ---- Model invocation ----

  @Test
  void modelInvocationRoundTripAndUniqueTurnKey() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          2,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.findModelInvocationByTurn(baseline.threadId(), baseline.turnStartEntryId()))
            .isPresent());
    assertTrue(
        store.transaction(tx -> tx.findModelInvocationByTurn(baseline.threadId(), 42)).isEmpty());
  }

  @Test
  void modelInvocationRequiresExistingThreadAndTurnStartAndBasisHead() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertModelInvocation(
                        modelInvocation(
                            1,
                            999,
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.READY,
                            null,
                            T1))));
    // ROOT is not a TURN_START
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          2,
                          baseline.threadId(),
                          baseline.rootEntryId(),
                          baseline.rootEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                }));
    // basisHead not equal to the current thread head (basis CAS)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          3,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          999,
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                }));
  }

  @Test
  void modelInvocationInsertRequiresBasisEqualToCurrentThreadHead() {
    // a basis that is not the thread head is rejected (creation-time basis CAS)
    Baseline other = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(other.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          1,
                          other.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    // basis on the thread's own head is valid
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  2,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T2));
        });
  }

  @Test
  void modelInvocationRequiresTurnStartOnBasisPath() {
    // a second TURN_START under the same root forms an independent branch
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    // turnStartB is not on the basis path [root, turnStartA]
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          1,
                          baseline.threadId(),
                          turnStartB,
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                }));
    // basis on the thread's own branch is valid
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  3,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T2));
        });
  }

  @Test
  void modelResultEntryTypeAndUniquenessAreEnforced() {
    // chain A: root -> turnStartA -> userA -> assistantA(call-1) -> toolResult(0, call-1)
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload("call-1"));
    long toolResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1"));
    // chain B: root -> turnStartB -> userB -> errorB
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userB, assistantErrorPayload());
    // chain C: root -> turnStartC -> userC -> abortedC
    long turnStartC =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userC = insertChildEntry(store, baseline.sessionId(), turnStartC, userMessagePayload());
    long abortedEntryId =
        insertChildEntry(store, baseline.sessionId(), userC, assistantAbortedPayload());

    // ROOT / USER MESSAGE / TOOL MESSAGE are not allowed result entry types
    for (long rejected : List.of(baseline.rootEntryId(), userEntryId, toolResultEntryId)) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              inTransaction(
                  store,
                  tx -> {
                    long id = tx.nextId();
                    tx.lockThread(baseline.threadId());
                    tx.insertModelInvocation(
                        modelInvocation(
                            id,
                            baseline.threadId(),
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.READY,
                            null,
                            T1));
                    tx.lockModelInvocation(id);
                    tx.updateModelInvocation(
                        modelInvocation(
                            id,
                            baseline.threadId(),
                            baseline.turnStartEntryId(),
                            baseline.turnStartEntryId(),
                            ModelInvocationStatus.CANCELLED,
                            rejected,
                            T1));
                  }));
    }

    // assistant / assistant-error / assistant-aborted on their own turn/branch are accepted
    insertTerminalModel(
        4,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        assistantEntryId,
        T1);
    long thread5 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, turnStartB));
              return id;
            });
    insertTerminalModel(
        5, thread5, turnStartB, turnStartB, ModelInvocationStatus.CANCELLED, errorEntryId, T2);
    long thread6 =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, turnStartC));
              return id;
            });
    insertTerminalModel(
        6, thread6, turnStartC, turnStartC, ModelInvocationStatus.CANCELLED, abortedEntryId, T3);

    // resultEntryId is globally unique across model invocations
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long thread7 = tx.nextId();
                  tx.insertThread(thread(thread7, baseline.turnStartEntryId()));
                  tx.insertModelInvocation(
                      modelInvocation(
                          7,
                          thread7,
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T4));
                  tx.lockModelInvocation(7);
                  tx.updateModelInvocation(
                      modelInvocation(
                          7,
                          thread7,
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          assistantEntryId,
                          T4));
                }));
  }

  @Test
  void modelResultEntryMustBeOnTheBasisPathAndInTheSameTurn() {
    // chain B: root -> turnStartB -> userB -> assistantB
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long assistantB = insertChildEntry(store, baseline.sessionId(), userB, assistantPayload());
    // result on a different turn of the same session is rejected
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          assistantB,
                          T1));
                }));
    // result on another session is rejected
    Baseline other = seedThreadBaseline(store);
    long otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    long otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    long otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.READY,
                          null,
                          T1));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          otherAssistant,
                          T1));
                }));
  }

  @Test
  void modelResultEntryMustBeABasisPathDescendantNotAnAncestor() {
    // chain: root -> turnStartA -> userA -> assistantA(call-1) -> toolResult(0, call-1)
    long userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantA =
        insertChildEntry(store, baseline.sessionId(), userA, assistantPayload("call-1"));
    long toolResultA =
        insertChildEntry(
            store, baseline.sessionId(), assistantA, toolResultPayload(assistantA, 0, "call-1"));
    // relocate the thread head to the tool result and create the model there
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateThread(new ThreadState(baseline.threadId(), toolResultA, false, 1, 1, T0, T2));
        });
    // assistantA is in the same turn but on the ancestor side of the basis -> sibling branch is
    // rejected even though it is a valid assistant result entry
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          toolResultA,
                          ModelInvocationStatus.READY,
                          null,
                          T2));
                  tx.lockModelInvocation(id);
                  tx.updateModelInvocation(
                      modelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          toolResultA,
                          ModelInvocationStatus.CANCELLED,
                          assistantA,
                          T2));
                }));
  }

  @Test
  void terminalReplayAndAttachStayLegalAfterTheThreadHeadRelocates() {
    // chain: root -> turnStartA -> userA -> assistantA
    long userA =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantA = insertChildEntry(store, baseline.sessionId(), userA, assistantPayload());
    // a terminal invocation without a linked result entry
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.CANCELLED,
                  null,
                  locked.createdAt()));
        });
    ModelInvocation committed = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());
    // relocate the thread head onto an unrelated branch in another session
    Baseline other = seedThreadBaseline(store);
    long otherTurnStart =
        insertChildEntry(store, other.sessionId(), other.rootEntryId(), turnStartPayload());
    long otherUser =
        insertChildEntry(store, other.sessionId(), otherTurnStart, userMessagePayload());
    long otherAssistant = insertChildEntry(store, other.sessionId(), otherUser, assistantPayload());
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateThread(
              new ThreadState(baseline.threadId(), otherAssistant, false, 1, 1, T0, T2));
        });
    // terminal exact replay after relocation stays legal (no dependency on the current head)
    inTransaction(
        store,
        tx -> {
          tx.lockModelInvocation(1);
          tx.updateModelInvocation(committed);
        });
    // attaching an entry outside the basis/turn path is rejected even after relocation
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
                  tx.updateModelInvocation(locked.attachResultEntry(otherAssistant, T3));
                }));
    // attaching a result entry on the original turn after relocation stays legal
    inTransaction(
        store,
        tx -> {
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(locked.attachResultEntry(assistantA, T3));
        });
    Long committedResultEntryId =
        store.transaction(tx -> tx.findModelInvocation(1).orElseThrow().resultEntryId());
    assertEquals(assistantA, committedResultEntryId.longValue());
  }

  @Test
  void updateModelInvocationMovesToTerminalWithResultEntry() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantPayload());
    inTransaction(
        store,
        tx -> {
          ModelInvocation locked = tx.lockModelInvocation(1).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  locked.id(),
                  locked.threadId(),
                  locked.turnStartEntryId(),
                  locked.basisHeadEntryId(),
                  ModelInvocationStatus.CANCELLED,
                  assistantEntryId,
                  locked.createdAt()));
        });
    ModelInvocation committed = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());
    assertEquals(ModelInvocationStatus.CANCELLED, committed.status());
    assertEquals(assistantEntryId, committed.resultEntryId());
  }

  @Test
  void updateModelInvocationRequiresLockAndStableIdentity() {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertModelInvocation(
              modelInvocation(
                  1,
                  baseline.threadId(),
                  baseline.turnStartEntryId(),
                  baseline.turnStartEntryId(),
                  ModelInvocationStatus.READY,
                  null,
                  T1));
        });
    ModelInvocation stored = store.transaction(tx -> tx.findModelInvocation(1).orElseThrow());

    // no lock
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.updateModelInvocation(stored)));
    // changed basisHeadEntryId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockModelInvocation(1);
                  tx.updateModelInvocation(
                      new ModelInvocation(
                          stored.id(),
                          stored.threadId(),
                          stored.turnStartEntryId(),
                          stored.basisHeadEntryId() + 1,
                          stored.request(),
                          stored.status(),
                          stored.attempt(),
                          stored.streamCheckpoint(),
                          stored.result(),
                          stored.error(),
                          stored.resultEntryId(),
                          stored.createdAt(),
                          T2));
                }));
    // changed createdAt
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockModelInvocation(1);
                  tx.updateModelInvocation(
                      new ModelInvocation(
                          stored.id(),
                          stored.threadId(),
                          stored.turnStartEntryId(),
                          stored.basisHeadEntryId(),
                          stored.request(),
                          stored.status(),
                          stored.attempt(),
                          stored.streamCheckpoint(),
                          stored.result(),
                          stored.error(),
                          stored.resultEntryId(),
                          T2,
                          T2));
                }));
  }

  @Test
  void findAndLockModelInvocationReturnEmptyForMissingIds() {
    assertTrue(store.<Boolean>transaction(tx -> tx.findModelInvocation(1).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockModelInvocation(1).isEmpty()));
  }

  @Test
  void newInvocationInsertAcceptsOnlyTheInitialState() {
    // model: non-READY status or positive attempt is rejected at insert
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      modelInvocation(
                          1,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          ModelInvocationStatus.CANCELLED,
                          null,
                          T1));
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.insertModelInvocation(
                      new ModelInvocation(
                          2,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          baseline.turnStartEntryId(),
                          modelRequest(),
                          ModelInvocationStatus.READY,
                          1,
                          null,
                          null,
                          null,
                          null,
                          T1,
                          T1));
                }));
    // tool: non-READY status / positive attempt / non-null approval is rejected at insert
    long assistantEntryId = seedAssistantAndModel();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                1,
                                assistantEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.CANCELLED,
                                null,
                                T2)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            new ToolInvocation(
                                11,
                                1,
                                assistantEntryId,
                                0,
                                toolRequest("call-1"),
                                ToolInvocationStatus.READY,
                                1,
                                null,
                                null,
                                null,
                                null,
                                T2,
                                T2)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            new ToolInvocation(
                                12,
                                1,
                                assistantEntryId,
                                0,
                                toolRequest("call-1"),
                                ToolInvocationStatus.READY,
                                0,
                                new ToolApproval(false, null, null, null, null, null, null),
                                null,
                                null,
                                null,
                                T2,
                                T2)))));
    // terminal apply via update is NOT restricted by the initial-state invariant (Stop / apply)
    inTransaction(
        store,
        tx -> {
          tx.insertToolInvocations(
              List.of(
                  toolInvocation(
                      13, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2)));
          tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      13,
                      1,
                      assistantEntryId,
                      0,
                      "call-1",
                      ToolInvocationStatus.CANCELLED,
                      null,
                      T2)));
        });
  }

  // ---- Tool invocation ----

  /**
   * Inserts a READY model invocation and moves it to a terminal status with the given result link
   * in one transaction (insert only accepts the initial state; terminal apply is an update).
   */
  private void insertTerminalModel(
      long id,
      long threadId,
      long turnStartEntryId,
      long basisHeadEntryId,
      ModelInvocationStatus status,
      Long resultEntryId,
      Instant createdAt) {
    inTransaction(
        store,
        tx -> {
          tx.lockThread(threadId);
          tx.insertModelInvocation(
              modelInvocation(
                  id,
                  threadId,
                  turnStartEntryId,
                  basisHeadEntryId,
                  ModelInvocationStatus.READY,
                  null,
                  createdAt));
          ModelInvocation locked = tx.lockModelInvocation(id).orElseThrow();
          tx.updateModelInvocation(
              modelInvocation(
                  id,
                  threadId,
                  turnStartEntryId,
                  basisHeadEntryId,
                  status,
                  resultEntryId,
                  locked.createdAt()));
        });
  }

  /**
   * Inserts a READY tool invocation and moves it to a terminal status with the given result link in
   * one transaction (insert only accepts the initial state; terminal apply is an update).
   */
  private void insertTerminalTool(
      long id,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      ToolInvocationStatus status,
      Long resultEntryId,
      Instant createdAt) {
    inTransaction(
        store,
        tx -> {
          tx.insertToolInvocations(
              List.of(
                  toolInvocation(
                      id,
                      modelInvocationId,
                      assistantEntryId,
                      ordinal,
                      toolCallId,
                      ToolInvocationStatus.READY,
                      null,
                      createdAt)));
          tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      id,
                      modelInvocationId,
                      assistantEntryId,
                      ordinal,
                      toolCallId,
                      status,
                      resultEntryId,
                      createdAt)));
        });
  }

  /**
   * Turn-valid chain TURN_START -> USER -> ASSISTANT(call-1..call-3) plus a terminal model result.
   */
  private long seedAssistantAndModel() {
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long assistantEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            userEntryId,
            assistantPayload("call-1", "call-2", "call-3"));
    insertTerminalModel(
        1,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        assistantEntryId,
        T1);
    return assistantEntryId;
  }

  @Test
  void toolInvocationRequiresModelInvocationAndAssistantMessageEntry() {
    long assistantEntryId = seedAssistantAndModel();
    // missing model invocation
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                999,
                                assistantEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolAssistantEntryMustBeAnAssistantMessage() {
    // a model whose result is an ASSISTANT_ERROR entry: a valid model result that is not an
    // assistant MESSAGE source
    long userEntryId =
        insertChildEntry(
            store, baseline.sessionId(), baseline.turnStartEntryId(), userMessagePayload());
    long errorEntryId =
        insertChildEntry(store, baseline.sessionId(), userEntryId, assistantErrorPayload());
    insertTerminalModel(
        1,
        baseline.threadId(),
        baseline.turnStartEntryId(),
        baseline.turnStartEntryId(),
        ModelInvocationStatus.CANCELLED,
        errorEntryId,
        T1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                1,
                                errorEntryId,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolInvocationRequiresModelResultEntryEqualsAssistantEntry() {
    // the model result is assistantA; sourcing a tool from assistantB is rejected
    seedAssistantAndModel();
    long turnStartB =
        insertChildEntry(store, baseline.sessionId(), baseline.rootEntryId(), turnStartPayload());
    long userB = insertChildEntry(store, baseline.sessionId(), turnStartB, userMessagePayload());
    long assistantB =
        insertChildEntry(store, baseline.sessionId(), userB, assistantPayload("call-1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                1,
                                assistantB,
                                0,
                                "call-1",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
  }

  @Test
  void toolInvocationUniqueKeysAreEnforced() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        12,
                        1,
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    // duplicate (assistantEntryId, ordinal)
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                13,
                                1,
                                assistantEntryId,
                                0,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T3)))));
    // duplicate id
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                12,
                                1,
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T3)))));
  }

  @Test
  void toolRequestCallMustExactlyMatchTheAssistantToolCallAtTheSameOrdinal() {
    long assistantEntryId = seedAssistantAndModel(); // assistant calls: call-1, call-2, call-3
    // wrong call id at the same ordinal
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                10,
                                1,
                                assistantEntryId,
                                1,
                                "call-other",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
    // ordinal beyond the assistant tool calls
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        List.of(
                            toolInvocation(
                                11,
                                1,
                                assistantEntryId,
                                5,
                                "call-5",
                                ToolInvocationStatus.READY,
                                null,
                                T2)))));
    // exact match accepted
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        12,
                        1,
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
  }

  @Test
  void toolResultEntryMustBeMatchingToolMessage() {
    long assistantEntryId = seedAssistantAndModel();
    long resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    // a matching result link is accepted
    insertTerminalTool(
        10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.CANCELLED, resultEntryId0, T2);
    // assistant MESSAGE is not a ToolResult entry
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              1,
                              "call-2",
                              ToolInvocationStatus.READY,
                              null,
                              T3)));
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              1,
                              "call-2",
                              ToolInvocationStatus.CANCELLED,
                              assistantEntryId,
                              T3)));
                }));
    long resultEntryId1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    long resultEntryId2 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId1,
            toolResultPayload(assistantEntryId, 2, "call-3", ToolResultStatus.CANCELLED));
    // result metadata must match this invocation's ordinal/toolCallId
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              2,
                              "call-3",
                              ToolInvocationStatus.READY,
                              null,
                              T4)));
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              2,
                              "call-3",
                              ToolInvocationStatus.CANCELLED,
                              resultEntryId1,
                              T4)));
                }));
    // request call must match the assistant tool call at the same ordinal
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              1,
                              "call-other",
                              ToolInvocationStatus.READY,
                              null,
                              T4)));
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              1,
                              "call-other",
                              ToolInvocationStatus.CANCELLED,
                              resultEntryId1,
                              T4)));
                }));
    // a matching result entry is accepted
    insertTerminalTool(
        14, 1, assistantEntryId, 2, "call-3", ToolInvocationStatus.CANCELLED, resultEntryId2, T5);
  }

  @Test
  void toolResultStatusMustExactlyMapTheInvocationStatus() {
    long assistantEntryId = seedAssistantAndModel();
    // a SUCCEEDED ToolResult cannot link a CANCELLED invocation
    long succeededResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.SUCCEEDED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.READY,
                              null,
                              T2)));
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.CANCELLED,
                              succeededResultEntryId,
                              T2)));
                }));
    // the matching status is accepted
    long cancelledResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            succeededResultEntryId,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    insertTerminalTool(
        12,
        1,
        assistantEntryId,
        1,
        "call-2",
        ToolInvocationStatus.CANCELLED,
        cancelledResultEntryId,
        T3);
  }

  @Test
  void toolResultEntryMustNotBeSynthetic() {
    long assistantEntryId = seedAssistantAndModel();
    long syntheticResultEntryId =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            syntheticToolResultPayload(assistantEntryId, 0, "call-1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertToolInvocations(
                      List.of(
                          toolInvocation(
                              id,
                              1,
                              assistantEntryId,
                              0,
                              "call-1",
                              ToolInvocationStatus.READY,
                              null,
                              T2)));
                  ToolInvocation locked =
                      tx.lockToolInvocationsByAssistantEntryId(assistantEntryId).get(0);
                  // reach the terminal UNKNOWN state through legal transitions, then attach the
                  // synthetic result entry: the store must reject the synthetic link itself
                  ToolInvocation next =
                      locked
                          .markApprovalNotRequired(T2)
                          .beginDispatch(T2)
                          .markRunning(T2)
                          .unknown(new ToolInvocationError("UNKNOWN", "ownership lost"), T2)
                          .attachResultEntry(syntheticResultEntryId, T2);
                  tx.updateToolInvocations(List.of(next));
                }));
  }

  @Test
  void loadToolInvocationsByAssistantEntryIdReturnsOrdinalOrder() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10, 1, assistantEntryId, 2, "call-3", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        11, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        12,
                        1,
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    List<Integer> ordinals =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::ordinal)
                    .toList());
    assertEquals(List.of(0, 1, 2), ordinals);
  }

  @Test
  void lockToolInvocationsByAssistantEntryIdAllowsBatchTerminalUpdate() {
    long assistantEntryId = seedAssistantAndModel();
    long resultEntryId0 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            assistantEntryId,
            toolResultPayload(assistantEntryId, 0, "call-1", ToolResultStatus.CANCELLED));
    long resultEntryId1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            resultEntryId0,
            toolResultPayload(assistantEntryId, 1, "call-2", ToolResultStatus.CANCELLED));
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10, 1, assistantEntryId, 0, "call-1", ToolInvocationStatus.READY, null, T2),
                    toolInvocation(
                        11,
                        1,
                        assistantEntryId,
                        1,
                        "call-2",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    inTransaction(
        store,
        tx -> {
          List<ToolInvocation> locked = tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
          tx.updateToolInvocations(
              List.of(
                  toolInvocation(
                      locked.get(0).id(),
                      1,
                      assistantEntryId,
                      locked.get(0).ordinal(),
                      locked.get(0).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId0,
                      locked.get(0).createdAt()),
                  toolInvocation(
                      locked.get(1).id(),
                      1,
                      assistantEntryId,
                      locked.get(1).ordinal(),
                      locked.get(1).request().call().id(),
                      ToolInvocationStatus.CANCELLED,
                      resultEntryId1,
                      locked.get(1).createdAt())));
        });
    List<Long> resultEntries =
        store.transaction(
            tx ->
                tx.loadToolInvocationsByAssistantEntryId(assistantEntryId).stream()
                    .map(ToolInvocation::resultEntryId)
                    .toList());
    assertEquals(List.of(resultEntryId0, resultEntryId1), resultEntries);
  }

  @Test
  void updateToolInvocationsRequiresLockAndStableIdentity() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10,
                        1,
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    ToolInvocation stored = store.transaction(tx -> tx.findToolInvocation(10).orElseThrow());

    // no lock
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.updateToolInvocations(List.of(stored))));
    // changed frozen request identity
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockToolInvocationsByAssistantEntryId(assistantEntryId);
                  tx.updateToolInvocations(
                      List.of(
                          new ToolInvocation(
                              stored.id(),
                              stored.modelInvocationId(),
                              stored.assistantEntryId(),
                              stored.ordinal(),
                              toolRequest("call-other"),
                              stored.status(),
                              stored.attempt(),
                              stored.approval(),
                              stored.result(),
                              stored.error(),
                              stored.resultEntryId(),
                              stored.createdAt(),
                              T3)));
                }));
  }

  @Test
  void toolListResultsAreImmutableAndNullElementsAreRejected() {
    long assistantEntryId = seedAssistantAndModel();
    inTransaction(
        store,
        tx ->
            tx.insertToolInvocations(
                List.of(
                    toolInvocation(
                        10,
                        1,
                        assistantEntryId,
                        0,
                        "call-1",
                        ToolInvocationStatus.READY,
                        null,
                        T2))));
    List<ToolInvocation> loaded =
        store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            loaded.add(
                toolInvocation(
                    11, 1, assistantEntryId, 1, "call-2", ToolInvocationStatus.READY, null, T2)));
    assertThrows(
        NullPointerException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertToolInvocations(
                        Arrays.asList(
                            toolInvocation(
                                11,
                                1,
                                assistantEntryId,
                                1,
                                "call-2",
                                ToolInvocationStatus.READY,
                                null,
                                T2),
                            null))));
  }
}
