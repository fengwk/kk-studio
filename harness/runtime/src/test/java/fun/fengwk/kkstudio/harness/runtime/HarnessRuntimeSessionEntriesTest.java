package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedOpenTurn;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * getThreadSessionEntries：返回 Session 全树（含非当前 head 的历史分支），且不改变 snapshot 的 root-to-head 投影与 version。
 */
class HarnessRuntimeSessionEntriesTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T5, ZoneOffset.UTC));
  }

  @Test
  void snapshotStaysOnActivePathWhileSessionEntriesIncludeInactiveSiblings() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID activeTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    UUID inactiveTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    UUID activeUserId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(userMessageEntry(id, baseline.sessionId(), activeTurnStartId, T2));
              return id;
            });
    store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads().update(thread.advanceHead(activeUserId, T5));
          return null;
        });

    ThreadSnapshot snapshot = runtime.getThreadSnapshot(baseline.threadId());
    assertEquals(
        List.of(baseline.rootEntryId(), activeTurnStartId, activeUserId),
        snapshot.requireFullPath().entries().stream().map(Entry::id).toList());

    List<Entry> entries = runtime.getSessionEntries(baseline.sessionId());
    assertEquals(
        List.of(baseline.rootEntryId(), activeTurnStartId, inactiveTurnStartId, activeUserId),
        entries.stream().map(Entry::id).toList());
    assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
    assertEquals(
        1L,
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow()).version());
  }

  @Test
  void missingSessionIsNotFoundAndDoesNotMutateStore() {
    DomainException error =
        assertThrows(DomainException.class, () -> runtime.getSessionEntries(TestIds.id(999)));
    assertEquals(DomainErrorCode.NOT_FOUND, error.code());
    assertEquals("session", error.resource());
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(DomainException.class, () -> runtime.listThreadsBySession(TestIds.id(999)))
            .code());
  }

  @Test
  void sessionEntryQueryDoesNotBumpVersion() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    runtime.getSessionEntries(baseline.sessionId());
    runtime.getSessionEntries(baseline.sessionId());
    ThreadState thread =
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());
    assertEquals(0L, thread.version());
  }

  @Test
  void paginatedSessionEntriesAdvanceByCreationKeysetAndKeepParentsBeforeChildren() {
    HarnessRuntimeTestSupport.TurnBaseline baseline = seedOpenTurn(store);
    UUID userId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(
                      userMessageEntry(id, baseline.sessionId(), baseline.turnStartEntryId(), T2));
              return id;
            });
    UUID assistantId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries().insert(assistantEntry(id, baseline.sessionId(), userId, T2));
              return id;
            });

    SessionEntryPage first = runtime.listSessionEntryPage(baseline.sessionId(), null, 2);
    assertEquals(
        List.of(baseline.rootEntryId(), baseline.turnStartEntryId()),
        first.entries().stream().map(Entry::id).toList());
    assertTrue(first.hasMore());

    SessionEntryPage second =
        runtime.listSessionEntryPage(baseline.sessionId(), first.entries().getLast().id(), 2);
    assertEquals(List.of(userId, assistantId), second.entries().stream().map(Entry::id).toList());
    assertFalse(second.hasMore());

    SessionEntryPage exhausted =
        runtime.listSessionEntryPage(baseline.sessionId(), second.entries().getLast().id(), 2);
    assertTrue(exhausted.entries().isEmpty());
    assertFalse(exhausted.hasMore());

    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(
                DomainException.class, () -> runtime.listSessionEntryPage(TestIds.id(999), null, 2))
            .code());
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () -> runtime.listSessionEntryPage(baseline.sessionId(), TestIds.id(999), 2))
            .code());
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(
                DomainException.class,
                () -> runtime.listSessionEntryPage(baseline.sessionId(), null, 0))
            .code());
    assertThrows(NullPointerException.class, () -> runtime.listSessionEntryPage(null, null, 2));
  }
}
