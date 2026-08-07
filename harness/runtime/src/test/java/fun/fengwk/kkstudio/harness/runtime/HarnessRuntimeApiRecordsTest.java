package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;

/** 根控制面 API 的 Value-record 校验分支与类型化异常。 */
class HarnessRuntimeApiRecordsTest {

  @Test
  void createThreadCommandRequiresBranchSettings() {
    assertThrows(NullPointerException.class, () -> new CreateThreadCommand("t", null, false));
    assertEquals("t", new CreateThreadCommand("t", settings(), false).title());
    assertTrue(new CreateThreadCommand(null, settings(), true).yoloEnabled());
  }

  @Test
  void createdThreadRequiresAllThreeFacts() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    CreatedThread created =
        store.transaction(
            tx -> {
              Session session = new Session(1, "s", T0);
              Entry root = new Entry(2, 1, null, new RootPayload(settings()), T0);
              ThreadState thread = new ThreadState(3, 2, false, 1, 0, T0, T0);
              return new CreatedThread(session, root, thread);
            });
    assertEquals(1L, created.session().id());
    assertEquals(2L, created.rootEntry().id());
    assertEquals(3L, created.thread().id());
    assertThrows(
        NullPointerException.class,
        () -> new CreatedThread(null, created.rootEntry(), created.thread()));
    assertThrows(
        NullPointerException.class,
        () -> new CreatedThread(created.session(), null, created.thread()));
    assertThrows(
        NullPointerException.class,
        () -> new CreatedThread(created.session(), created.rootEntry(), null));
  }

  @Test
  void threadSnapshotDefensiveCopiesAndValidates() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
    List<ThreadCommand> queued =
        List.of(
            new ThreadCommand(
                1,
                thread.id(),
                1,
                HarnessRuntimeTestSupport.userMessagePayload("a"),
                "cid",
                null,
                null,
                T0));
    ThreadSnapshot snapshot = new ThreadSnapshot(thread, path, queued, null, List.of());
    assertEquals(1, snapshot.queuedCommands().size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.queuedCommands().add(null));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.toolSiblings().add(null));
    assertThrows(
        NullPointerException.class, () -> new ThreadSnapshot(null, path, queued, null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadSnapshot(thread, null, queued, null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new ThreadSnapshot(thread, path, null, null, List.of()));
    assertThrows(
        NullPointerException.class, () -> new ThreadSnapshot(thread, path, queued, null, null));
  }

  @Test
  void conflictExceptionCarriesTypedReasonAndMessage() {
    HarnessRuntimeConflictException error =
        new HarnessRuntimeConflictException(Reason.STALE_REVISION, "stale");
    assertEquals(Reason.STALE_REVISION, error.reason());
    assertEquals("stale", error.getMessage());
    assertThrows(NullPointerException.class, () -> new HarnessRuntimeConflictException(null, "x"));
  }

  @Test
  void notFoundExceptionCarriesMessage() {
    assertEquals("gone", new HarnessRuntimeNotFoundException("gone").getMessage());
  }

  @Test
  void moveHeadCommandValidationIsExact() {
    assertEquals(7L, new MoveHeadCommand(1, 2, 7).expectedRevision());
    assertThrows(IllegalArgumentException.class, () -> new MoveHeadCommand(-1, 2, 0));
  }
}
