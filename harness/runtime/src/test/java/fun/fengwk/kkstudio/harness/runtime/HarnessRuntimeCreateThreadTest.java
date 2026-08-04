package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;

/** createThread: atomic Session + ROOT + Thread creation, rollback, no Work, non-idempotency. */
class HarnessRuntimeCreateThreadTest {

  private final InMemoryHarnessStore store = new InMemoryHarnessStore();
  private final HarnessRuntime runtime = new HarnessRuntime(store, Clock.fixed(T0, ZoneOffset.UTC));

  @Test
  void createsSessionRootAndThreadAtomicallyWithExactInitialFacts() {
    CreatedThread created =
        runtime.createThread(new CreateThreadCommand("my session", settings(), true));

    assertNotNull(created.session());
    assertNotNull(created.rootEntry());
    assertNotNull(created.thread());
    assertNotEquals(created.session().id(), created.rootEntry().id());
    assertNotEquals(created.session().id(), created.thread().id());
    assertNotEquals(created.rootEntry().id(), created.thread().id());

    Session session = store.transaction(tx -> tx.findSession(created.session().id()).orElseThrow());
    assertEquals("my session", session.title());
    assertEquals(T0, session.createdAt());

    Entry root = store.transaction(tx -> tx.findEntry(created.rootEntry().id()).orElseThrow());
    assertEquals(EntryType.ROOT, root.payload().type());
    assertEquals(created.session().id(), root.sessionId());
    assertNull(root.parentEntryId());
    assertEquals(T0, root.createdAt());
    assertEquals(settings(), ((RootPayload) root.payload()).settings());

    ThreadState thread =
        store.transaction(tx -> tx.findThread(created.thread().id()).orElseThrow());
    assertEquals(created.rootEntry().id(), thread.headEntryId());
    assertTrue(thread.yoloEnabled());
    assertEquals(1L, thread.nextCommandSequence());
    assertEquals(0L, thread.revision());
    assertEquals(T0, thread.createdAt());
    assertEquals(T0, thread.updatedAt());

    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    assertEquals(1, path.entries().size());
    assertEquals(created.rootEntry().id(), path.head().id());
  }

  @Test
  void createThreadDoesNotRequestAnyWork() {
    CreatedThread created = runtime.createThread(new CreateThreadCommand(null, settings(), false));
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, created.thread().id()))
                    .isEmpty()));
    assertTrue(
        store.<Boolean>transaction(
            tx ->
                tx.claimNextWork(WorkTargetType.THREAD, T1, "lease", T1.plusSeconds(10))
                    .isEmpty()));
  }

  @Test
  void invalidCreateRollsBackAllDurableRows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.createThread(new CreateThreadCommand("   ", settings(), false)));
    // 回滚只保证 durable 行不落库；nextId 允许被事务消耗（HarnessStore 契约），不断言复用。
    assertTrue(store.transaction(tx -> tx.findSession(1)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findEntry(2)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findThread(3)).isEmpty());
  }

  @Test
  void createIsDocumentedNonIdempotentWithoutCreateRequestId() {
    CreatedThread first = runtime.createThread(new CreateThreadCommand("a", settings(), false));
    CreatedThread second = runtime.createThread(new CreateThreadCommand("b", settings(), true));
    assertNotEquals(first.session().id(), second.session().id());
    assertNotEquals(first.rootEntry().id(), second.rootEntry().id());
    assertNotEquals(first.thread().id(), second.thread().id());
    assertTrue(second.thread().yoloEnabled());
  }

  @Test
  void nullCommandOrSettingsIsRejected() {
    assertThrows(NullPointerException.class, () -> runtime.createThread(null));
    assertThrows(
        NullPointerException.class,
        () -> runtime.createThread(new CreateThreadCommand("a", null, false)));
  }
}
