package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.List;
import java.util.UUID;

/** 执行树收集：目标子树、派生停止身份、直接父锁集合与规范排序。 */
class StopTreeCollectorTest {

  private InMemoryHarnessStore store;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
  }

  @Test
  void collectsSubtreeAndDirectParentLocksWithoutUnrelatedSiblings() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);
    UUID child =
        insertChild(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID grandChild = insertChild(child, root.threadId(), root.sessionId(), root.rootEntryId());
    UUID unrelatedSibling =
        insertChild(root.threadId(), root.threadId(), root.sessionId(), root.rootEntryId());
    UUID stopRequestId = id(41L);

    StopTreeCollector.Tree tree =
        store.transaction(
            tx -> StopTreeCollector.collect(tx, new StopCommand(child, stopRequestId, 0L)));

    assertEquals(
        List.of(child, grandChild).stream().sorted(UuidOrder.COMPARATOR).toList(),
        tree.candidates().stream()
            .map(candidate -> candidate.thread().id())
            .sorted(UuidOrder.COMPARATOR)
            .toList());
    assertEquals(stopRequestId, candidate(tree, child).stopRequestId());
    assertEquals(
        StopTreeCollector.deriveChildStopRequestId(stopRequestId, grandChild),
        candidate(tree, grandChild).stopRequestId());
    assertEquals(List.of(root.sessionId()), tree.sessionIds());
    assertEquals(
        List.of(root.threadId(), child, grandChild).stream().sorted(UuidOrder.COMPARATOR).toList(),
        tree.threadIds());
    assertFalse(tree.threadIds().contains(unrelatedSibling));
  }

  @Test
  void rootTargetDoesNotAddAParentLock() {
    HarnessRuntimeTestSupport.Baseline root = seedBaseline(store);

    StopTreeCollector.Tree tree =
        store.transaction(
            tx -> StopTreeCollector.collect(tx, new StopCommand(root.threadId(), id(42L), 0L)));

    assertEquals(1, tree.candidates().size());
    assertEquals(List.of(root.sessionId()), tree.sessionIds());
    assertEquals(List.of(root.threadId()), tree.threadIds());
  }

  @Test
  void missingTargetIsNotFoundBeforeAnyCollection() {
    HarnessRuntimeNotFoundException error =
        assertThrows(
            HarnessRuntimeNotFoundException.class,
            () ->
                store.transaction(
                    tx -> StopTreeCollector.collect(tx, new StopCommand(id(99L), id(43L), 0L))));

    assertTrue(error.getMessage().contains(id(99L).toString()));
  }

  @Test
  void childStopRequestIdIsDeterministicAndRejectsMissingInputs() {
    UUID parentStopRequestId = id(44L);
    UUID childThreadId = id(45L);

    assertEquals(
        StopTreeCollector.deriveChildStopRequestId(parentStopRequestId, childThreadId),
        StopTreeCollector.deriveChildStopRequestId(parentStopRequestId, childThreadId));
    assertNotEquals(
        StopTreeCollector.deriveChildStopRequestId(parentStopRequestId, childThreadId),
        StopTreeCollector.deriveChildStopRequestId(parentStopRequestId, id(46L)));
    assertThrows(
        NullPointerException.class,
        () -> StopTreeCollector.deriveChildStopRequestId(null, childThreadId));
    assertThrows(
        NullPointerException.class,
        () -> StopTreeCollector.deriveChildStopRequestId(parentStopRequestId, null));
  }

  private static StopTreeCollector.Candidate candidate(StopTreeCollector.Tree tree, UUID threadId) {
    return tree.candidates().stream()
        .filter(candidate -> candidate.thread().id().equals(threadId))
        .findFirst()
        .orElseThrow();
  }

  private UUID insertChild(
      UUID parentThreadId, UUID rootThreadId, UUID sessionId, UUID headEntryId) {
    UUID childId = UUID.randomUUID();
    store.transaction(
        tx -> {
          tx.threads()
              .insert(
                  new ThreadState(
                      childId,
                      sessionId,
                      parentThreadId,
                      headEntryId,
                      CREATION_REQUEST_HASH,
                      "child-branch",
                      rootThreadId,
                      false,
                      SpawnTrigger.MODEL_TOOL,
                      ThreadConstraints.NONE,
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
}
