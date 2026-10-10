package fun.fengwk.kkstudio.harness.runtime.compaction;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinPurpose;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 压缩预算来源缺失必须明确失败，不能退化成普通无预算请求；完整执行由真实 PG 回归覆盖。 */
class CompactionChildScopeTest {

  private static final UUID CHILD = id(1);
  private static final UUID PARENT = id(2);
  private static final UUID JOIN = id(3);
  private static final String HASH = "0".repeat(64);
  private static final Instant NOW = Instant.ofEpochMilli(1000);

  @Test
  void compactionJoinWithoutParentFailsClosed() {
    HarnessStore.Transaction tx = transactionWithJoin(null);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> CompactionChildScope.frozenStartFor(tx, CHILD));

    assertEquals("COMPACTION join " + JOIN + " has no parent thread", error.getMessage());
  }

  @Test
  void missingParentFailsClosed() {
    HarnessStore.Transaction tx = transactionWithJoin(PARENT);
    when(tx.findThread(PARENT)).thenReturn(Optional.empty());

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> CompactionChildScope.frozenStartFor(tx, CHILD));

    assertEquals(
        "COMPACTION join " + JOIN + " references missing parent thread " + PARENT,
        error.getMessage());
  }

  @Test
  void missingFrozenTurnFailsClosed() {
    HarnessStore.Transaction tx = transactionWithJoin(PARENT);
    UUID session = id(4);
    UUID head = id(5);
    ThreadState parent =
        new ThreadState(
            PARENT,
            session,
            null,
            head,
            HASH,
            "parent",
            ThreadYoloPolicy.root(false),
            ThreadExecutionControl.RUNNABLE,
            0,
            1,
            0,
            NOW,
            NOW);
    BranchSettings settings = new BranchSettings("parent", new ModelSelection("p", "m", "v"), null);
    Entry root = new Entry(head, session, null, new RootPayload(settings), NOW);
    when(tx.findThread(PARENT)).thenReturn(Optional.of(parent));
    when(tx.loadEntryPath(head)).thenReturn(new EntryPath(List.of(root)));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> CompactionChildScope.frozenStartFor(tx, CHILD));

    assertEquals(
        "COMPACTION join " + JOIN + " has no frozen compaction turn start on the parent path",
        error.getMessage());
  }

  private HarnessStore.Transaction transactionWithJoin(UUID parent) {
    HarnessStore.Transaction tx = mock(HarnessStore.Transaction.class);
    ThreadJoin join =
        new ThreadJoin(
            JOIN,
            HASH,
            parent,
            CHILD,
            1,
            "compaction",
            null,
            0,
            null,
            null,
            null,
            NOW,
            NOW,
            JoinPurpose.COMPACTION,
            null);
    when(tx.loadIncompleteJoins(CHILD)).thenReturn(List.of(join));
    return tx;
  }
}
