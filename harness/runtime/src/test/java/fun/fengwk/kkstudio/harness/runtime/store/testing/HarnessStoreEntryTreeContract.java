package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;

/** Session / Entry tree / Thread schema 约束，以及 root-to-head path 的加载。 */
public abstract class HarnessStoreEntryTreeContract {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = createStore();
  }

  abstract HarnessStore createStore();

  @Test
  void sessionEntryAndThreadRoundTrip() {
    Baseline baseline = seedThreadBaseline(store);
    store.transaction(
        tx -> {
          assertEquals(
              session(baseline.sessionId()), tx.findSession(baseline.sessionId()).orElseThrow());
          assertEquals(
              rootEntry(baseline.rootEntryId(), baseline.sessionId()),
              tx.findEntry(baseline.rootEntryId()).orElseThrow());
          assertEquals(
              thread(baseline.threadId(), baseline.rootEntryId()),
              tx.findThread(baseline.threadId()).orElseThrow());
          return null;
        });
  }

  @Test
  void sessionTitlePreservesValidSurroundingWhitespace() {
    Session session = new Session(1, "  visible title  ", T0);
    inTransaction(store, tx -> tx.insertSession(session));
    assertEquals(session, store.transaction(tx -> tx.findSession(1).orElseThrow()));
  }

  @Test
  void sessionEntryAndThreadTimestampsRejectSubMillisecondPrecision() {
    Session session = new Session(1, "session", T0.plusNanos(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertSession(session)));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(1).isEmpty()));

    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertEntry(
                        turnStartEntry(
                            100, baseline.sessionId(), baseline.rootEntryId(), T1.plusNanos(1)))));
    ThreadState thread =
        new ThreadState(101, baseline.rootEntryId(), false, 1, 0, T0.plusNanos(1), T0.plusNanos(1));
    assertThrows(
        IllegalArgumentException.class, () -> inTransaction(store, tx -> tx.insertThread(thread)));
  }

  @Test
  void duplicateSessionIdIsRejected() {
    inTransaction(store, tx -> tx.insertSession(session(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertSession(session(1))));
  }

  @Test
  void rootEntryRequiresExistingSession() {
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertEntry(rootEntry(1, 999))));
  }

  @Test
  void eachSessionHasAtMostOneRoot() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertEntry(rootEntry(100, baseline.sessionId()))));
  }

  @Test
  void nonRootEntryRequiresExistingRootFirst() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long sessionId = tx.nextId();
                  tx.insertSession(session(sessionId));
                  tx.insertEntry(turnStartEntry(5, sessionId, 1, T1));
                }));
  }

  @Test
  void nonRootEntryRequiresExistingSameSessionParent() {
    Baseline baseline = seedThreadBaseline(store);
    // parent 不存在
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.insertEntry(turnStartEntry(10, baseline.sessionId(), 999, T1))));
    // parent 属于其他 session
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long otherSession = tx.nextId();
                  long otherRoot = tx.nextId();
                  tx.insertSession(session(otherSession));
                  tx.insertEntry(rootEntry(otherRoot, otherSession));
                  tx.insertEntry(turnStartEntry(11, baseline.sessionId(), otherRoot, T1));
                }));
  }

  @Test
  void entryCreatedAtMustNotPrecedeItsParent() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertEntry(
                        turnStartEntry(
                            10, baseline.sessionId(), baseline.rootEntryId(), T0.minusMillis(1)))));
  }

  @Test
  void loadEntryPathWalksFromHeadToRoot() {
    Baseline baseline = seedThreadBaseline(store);
    long turnStartEntryId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(turnStartEntryId);
          assertEquals(
              List.of(baseline.rootEntryId(), turnStartEntryId),
              path.entries().stream().map(Entry::id).toList());
          assertEquals(baseline.rootEntryId(), path.root().id());
          assertEquals(turnStartEntryId, path.head().id());
          return null;
        });
    // 单个 ROOT 也是一个合法的截断 path
    store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(baseline.rootEntryId());
          assertEquals(
              List.of(baseline.rootEntryId()), path.entries().stream().map(Entry::id).toList());
          return null;
        });
  }

  @Test
  void loadEntryPathRejectsUnknownHead() {
    assertThrows(
        IllegalArgumentException.class, () -> inTransaction(store, tx -> tx.loadEntryPath(42)));
  }

  @Test
  void insertThreadRequiresExistingHeadEntry() {
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertThread(thread(100, 999))));
  }

  @Test
  void duplicateThreadIdIsRejected() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.insertThread(thread(baseline.threadId(), baseline.rootEntryId()))));
  }

  @Test
  void findAndLockThreadReturnEmptyForMissingIds() {
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(1).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockThread(1).isEmpty()));
  }

  @Test
  void updateThreadRequiresPriorLock() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store, tx -> tx.updateThread(thread(baseline.threadId(), baseline.rootEntryId()))));
  }

  @Test
  void updateThreadAfterLockCommitsNewCurrentState() {
    Baseline baseline = seedThreadBaseline(store);
    inTransaction(
        store,
        tx -> {
          ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(
              new ThreadState(
                  locked.id(),
                  locked.headEntryId(),
                  true,
                  3,
                  locked.revision() + 1,
                  locked.createdAt(),
                  T2));
        });
    ThreadState committed =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertTrue(committed.yoloEnabled());
    assertEquals(3L, committed.nextCommandSequence());
    assertEquals(1L, committed.revision());
    assertEquals(T2, committed.updatedAt());
  }

  @Test
  void updateThreadRejectsChangedCreatedAt() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.updateThread(
                      new ThreadState(
                          locked.id(),
                          locked.headEntryId(),
                          locked.yoloEnabled(),
                          locked.nextCommandSequence(),
                          locked.revision(),
                          T1,
                          T2));
                }));
  }

  @Test
  void insertThenUpdateInTheSameTransactionIsAllowed() {
    Baseline baseline = seedThreadBaseline(store);
    long threadId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertThread(thread(id, baseline.rootEntryId()));
              ThreadState inserted = tx.findThread(id).orElseThrow();
              tx.updateThread(
                  new ThreadState(
                      inserted.id(),
                      inserted.headEntryId(),
                      true,
                      inserted.nextCommandSequence(),
                      inserted.revision() + 1,
                      inserted.createdAt(),
                      T2));
              return id;
            });
    assertTrue(
        store.<Boolean>transaction(tx -> tx.findThread(threadId).orElseThrow().yoloEnabled()));
  }

  @Test
  void insertEntryValidatesTheFullEntryPathBeforeWriting() {
    Baseline baseline = seedThreadBaseline(store);
    // 非 ROOT entry 必须位于开放的 TURN_START 内
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long id = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          id,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          userMessagePayload(),
                          T1));
                }));
    // INPUT turn 必须在 assistant result 之前有 USER/CUSTOM message
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  long turnStartId = tx.nextId();
                  tx.insertEntry(
                      turnStartEntry(
                          turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
                  long assistantId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          assistantId, baseline.sessionId(), turnStartId, assistantPayload(), T1));
                }));
    // 合法的 TURN_START -> USER -> ASSISTANT 链会被接受并以 path 形式读出
    long[] ids =
        store.transaction(
            tx -> {
              long turnStartId = tx.nextId();
              long userId = tx.nextId();
              long assistantId = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
              tx.insertEntry(
                  new Entry(userId, baseline.sessionId(), turnStartId, userMessagePayload(), T1));
              tx.insertEntry(
                  new Entry(assistantId, baseline.sessionId(), userId, assistantPayload(), T1));
              return new long[] {turnStartId, userId, assistantId};
            });
    store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(ids[2]);
          assertEquals(
              List.of(baseline.rootEntryId(), ids[0], ids[1], ids[2]),
              path.entries().stream().map(Entry::id).toList());
          return null;
        });
  }

  @Test
  void updateThreadRequiresExistingHeadEntry() {
    Baseline baseline = seedThreadBaseline(store);
    // head 未知
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockThread(baseline.threadId());
                  tx.updateThread(new ThreadState(baseline.threadId(), 999, false, 1, 1, T0, T2));
                }));
    // 将 head 迁移到一个已存在 entry 是允许的
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
          tx.updateThread(
              new ThreadState(baseline.threadId(), turnStartEntryId, false, 1, 1, T0, T2));
        });
    long committedHead =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().headEntryId());
    assertEquals(turnStartEntryId, committedHead);
  }
}
