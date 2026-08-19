package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * getThreadSessionEntries：返回 Session 全树（含非当前 head 的历史分支），且不改变 snapshot 的 root-to-head 投影与 revision。
 */
class HarnessRuntimeSessionEntriesTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = new HarnessRuntime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void snapshotStaysOnActivePathWhileSessionEntriesIncludeInactiveSiblings() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID activeTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    UUID inactiveTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    UUID activeUserId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(userMessageEntry(id, baseline.sessionId(), activeTurnStartId, T2));
              return id;
            });
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(thread.advanceHead(activeUserId, T5));
          return null;
        });

    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(
        List.of(baseline.rootEntryId(), activeTurnStartId, activeUserId),
        snapshot.entryPath().entries().stream().map(Entry::id).toList());

    List<Entry> entries = runtime.getThreadSessionEntries(baseline.threadId());
    assertEquals(
        List.of(baseline.rootEntryId(), activeTurnStartId, inactiveTurnStartId, activeUserId),
        entries.stream().map(Entry::id).toList());
    assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
    assertEquals(
        1L, store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow()).revision());
  }

  @Test
  void missingThreadIsNotFoundAndDoesNotMutateStore() {
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.getThreadSessionEntries(TestIds.id(999)));
  }

  @Test
  void sessionEntryQueryDoesNotBumpRevision() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.getThreadSessionEntries(baseline.threadId());
    runtime.getThreadSessionEntries(baseline.threadId());
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.revision());
  }
}
