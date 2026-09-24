package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.assistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.inTransaction;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.insertChildEntry;
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

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Session / Entry tree / Thread schema 约束，root-to-head path 与 branch settings 的窄读取。 */
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
              thread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId()),
              tx.findThread(baseline.threadId()).orElseThrow());
          return null;
        });
  }

  @Test
  void sessionRoundTripPreservesCreatedAt() {
    UUID sessionId = TestIds.id(1);
    Session session = session(sessionId);
    inTransaction(store, tx -> tx.insertSession(session));
    assertEquals(session, store.transaction(tx -> tx.findSession(sessionId).orElseThrow()));
  }

  @Test
  void updateSessionReplacesOnlyNameUnderLockAndValidatesTransition() {
    // 测试意图：updateSession 是唯一受支持的 Session 行变更——锁内仅替换 name，id/createdAt 不可变；
    // 未锁、行不存在、身份改变都抛对应异常且行不被改动。
    UUID sessionId = TestIds.id(10);
    inTransaction(store, tx -> tx.insertSession(session(sessionId)));
    Session stored = store.transaction(tx -> tx.findSession(sessionId)).orElseThrow();
    Session renamed = new Session(sessionId, "renamed session", stored.createdAt());

    // 未锁定时更新被拒。
    assertThrows(
        IllegalStateException.class, () -> inTransaction(store, tx -> tx.updateSession(renamed)));
    // 锁定后更新成功且只替换名称。
    inTransaction(
        store,
        tx -> {
          tx.lockSessionForKeyShare(sessionId).orElseThrow();
          tx.updateSession(renamed);
        });
    assertEquals(renamed, store.transaction(tx -> tx.findSession(sessionId)).orElseThrow());

    // 行不存在：lock 返回 empty（不产生锁），未锁定更新抛 IllegalStateException。
    UUID ghostId = TestIds.id(11);
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockSessionForKeyShare(ghostId);
                  tx.updateSession(new Session(ghostId, "ghost", stored.createdAt()));
                }));

    // createdAt 身份字段改变被共享校验拒绝（updateSession 按候选 id 定位存储行，id 恒等于行 id）。
    Session movedCreatedAt =
        new Session(sessionId, "renamed session", stored.createdAt().plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.lockSessionForKeyShare(sessionId).orElseThrow();
                  tx.updateSession(movedCreatedAt);
                }));
    // 拒绝后行保持原名。
    assertEquals(renamed, store.transaction(tx -> tx.findSession(sessionId)).orElseThrow());
  }

  @Test
  void updateSessionAcceptsExactReplayAndForUpdateLock() {
    // 测试意图：updateSession 支持 lockSessionForUpdate（rename 控制面用）且 exact replay 是合法迁移。
    UUID sessionId = TestIds.id(13);
    inTransaction(store, tx -> tx.insertSession(session(sessionId)));
    Session stored = store.transaction(tx -> tx.findSession(sessionId)).orElseThrow();
    inTransaction(
        store,
        tx -> {
          tx.lockSessionForUpdate(sessionId).orElseThrow();
          tx.updateSession(stored);
        });
    assertEquals(stored, store.transaction(tx -> tx.findSession(sessionId)).orElseThrow());
  }

  @Test
  void sessionEntryAndThreadTimestampsRejectSubMillisecondPrecision() {
    UUID sessionId = TestIds.id(1);
    Session session = new Session(sessionId, "session-" + sessionId, T0.plusNanos(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertSession(session)));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(sessionId).isEmpty()));

    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertEntry(
                        turnStartEntry(
                            TestIds.id(100),
                            baseline.sessionId(),
                            baseline.rootEntryId(),
                            T1.plusNanos(1)))));
    ThreadState thread =
        StoreTestSupport.threadState(
            TestIds.id(101),
            baseline.sessionId(),
            baseline.rootEntryId(),
            1,
            0,
            T0.plusNanos(1),
            T0.plusNanos(1));
    assertThrows(
        IllegalArgumentException.class, () -> inTransaction(store, tx -> tx.insertThread(thread)));
  }

  @Test
  void duplicateSessionIdIsRejected() {
    UUID sessionId = TestIds.id(1);
    inTransaction(store, tx -> tx.insertSession(session(sessionId)));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.insertSession(session(sessionId))));
  }

  @Test
  void rootEntryRequiresExistingSession() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(store, tx -> tx.insertEntry(rootEntry(TestIds.id(1), TestIds.id(999)))));
  }

  @Test
  void eachSessionHasAtMostOneRoot() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.insertEntry(rootEntry(TestIds.id(100), baseline.sessionId()))));
  }

  @Test
  void nonRootEntryRequiresExistingRootFirst() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID sessionId = tx.nextId();
                  tx.insertSession(session(sessionId));
                  tx.insertEntry(turnStartEntry(TestIds.id(5), sessionId, TestIds.id(1), T1));
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
                store,
                tx ->
                    tx.insertEntry(
                        turnStartEntry(
                            TestIds.id(10), baseline.sessionId(), TestIds.id(999), T1))));
    // parent 属于其他 session
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  UUID otherSession = tx.nextId();
                  UUID otherRoot = tx.nextId();
                  tx.insertSession(session(otherSession));
                  tx.insertEntry(rootEntry(otherRoot, otherSession));
                  tx.insertEntry(
                      turnStartEntry(TestIds.id(11), baseline.sessionId(), otherRoot, T1));
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
                            TestIds.id(10),
                            baseline.sessionId(),
                            baseline.rootEntryId(),
                            T0.minusMillis(1)))));
  }

  @Test
  void loadEntryPathWalksFromHeadToRoot() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
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
  void loadEntryPathWalksDeepChainFromHeadToRoot() {
    Baseline baseline = seedThreadBaseline(store);
    List<UUID> expectedIds = new ArrayList<>();
    expectedIds.add(baseline.rootEntryId());
    UUID currentParentId = baseline.rootEntryId();
    Instant time = T1;
    for (int i = 0; i < 20; i++) {
      UUID parentId = currentParentId;
      Instant turnTime = time;
      UUID[] turnIds =
          store.transaction(
              tx -> {
                UUID turnStartId = tx.nextId();
                UUID userId = tx.nextId();
                UUID assistantId = tx.nextId();
                UUID turnEndId = tx.nextId();
                tx.insertEntry(
                    turnStartEntry(turnStartId, baseline.sessionId(), parentId, turnTime));
                tx.insertEntry(
                    new Entry(
                        userId, baseline.sessionId(), turnStartId, userMessagePayload(), turnTime));
                tx.insertEntry(
                    new Entry(
                        assistantId, baseline.sessionId(), userId, assistantPayload(), turnTime));
                tx.insertEntry(
                    new Entry(
                        turnEndId,
                        baseline.sessionId(),
                        assistantId,
                        new TurnEndPayload(
                            turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                        turnTime));
                return new UUID[] {turnStartId, userId, assistantId, turnEndId};
              });
      expectedIds.add(turnIds[0]);
      expectedIds.add(turnIds[1]);
      expectedIds.add(turnIds[2]);
      expectedIds.add(turnIds[3]);
      currentParentId = turnIds[3];
      time = time.plusSeconds(1);
    }
    UUID deepHeadId = currentParentId;
    store.transaction(
        tx -> {
          EntryPath path = tx.loadEntryPath(deepHeadId);
          assertEquals(expectedIds, path.entries().stream().map(Entry::id).toList());
          assertEquals(baseline.rootEntryId(), path.root().id());
          assertEquals(deepHeadId, path.head().id());
          return null;
        });
  }

  @Test
  void loadEntryPathRejectsUnknownHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.loadEntryPath(TestIds.id(42))));
  }

  @Test
  void loadEntriesBySessionIdReturnsImmutableStableOrderAndRejectsMissingSession() {
    Baseline baseline = seedThreadBaseline(store);
    UUID otherSessionId =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              tx.insertSession(session(sessionId));
              tx.insertEntry(rootEntry(rootId, sessionId));
              return sessionId;
            });
    UUID sameTimeLarge = new UUID(0x8000000000000000L, 2L);
    UUID sameTimeSmall = new UUID(0x8000000000000000L, 1L);
    UUID laterTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          // 同 createdAt 先写入较大 UUID，再写入较小 UUID，证明排序不是插入序。
          tx.insertEntry(
              new Entry(
                  sameTimeLarge, baseline.sessionId(), laterTurnStartId, userMessagePayload(), T2));
          tx.insertEntry(
              new Entry(
                  sameTimeSmall, baseline.sessionId(), laterTurnStartId, userMessagePayload(), T2));
        });

    store.transaction(
        tx -> {
          List<Entry> entries = tx.loadEntriesBySessionId(baseline.sessionId());
          assertEquals(
              List.of(baseline.rootEntryId(), laterTurnStartId, sameTimeSmall, sameTimeLarge),
              entries.stream().map(Entry::id).toList());
          assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
          assertEquals(
              1, tx.loadEntriesBySessionId(otherSessionId).size(), "must filter by session");
          return null;
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.loadEntriesBySessionId(TestIds.id(999))));
  }

  @Test
  void insertThreadRequiresExistingHeadEntry() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.insertThread(thread(TestIds.id(100), TestIds.id(888), TestIds.id(999)))));
  }

  @Test
  void duplicateThreadIdIsRejected() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.insertThread(
                        thread(
                            baseline.threadId(), baseline.sessionId(), baseline.rootEntryId()))));
  }

  @Test
  void findAndLockThreadReturnEmptyForMissingIds() {
    UUID missingId = TestIds.id(1);
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(missingId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.lockThread(missingId).isEmpty()));
  }

  @Test
  void updateThreadRequiresPriorLock() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.updateThread(
                        thread(
                            baseline.threadId(), baseline.sessionId(), baseline.rootEntryId()))));
  }

  @Test
  void updateThreadAfterLockCommitsNewCurrentState() {
    Baseline baseline = seedThreadBaseline(store);
    inTransaction(
        store,
        tx -> {
          ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(
              StoreTestSupport.threadState(
                  locked.id(),
                  locked.sessionId(),
                  locked.headEntryId(),
                  true,
                  3,
                  locked.version() + 1,
                  locked.createdAt(),
                  T2));
        });
    ThreadState committed =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertTrue(committed.yoloEnabled());
    assertEquals(3L, committed.nextCommandSequence());
    assertEquals(1L, committed.version());
    assertEquals(T2, committed.updatedAt());
  }

  @Test
  void updateThreadWithRenameCommitsNameChangeBumpingVersionExactlyOnce() {
    // 测试意图：rename 复用 updateThread 既有路径——仅 name 变化（version 精确 +1、updatedAt 推进）可落库；
    // head / sequence / creationRequestHash / createdAt 不变；exact replay 被接受。
    Baseline baseline = seedThreadBaseline(store);
    ThreadState stored = store.transaction(tx -> tx.findThread(baseline.threadId())).orElseThrow();
    ThreadState renamed = stored.renameThread("branch 分析", T2);
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId()).orElseThrow();
          tx.updateThread(renamed);
        });
    ThreadState committed =
        store.transaction(tx -> tx.findThread(baseline.threadId())).orElseThrow();
    assertEquals(renamed, committed);
    assertEquals("branch 分析", committed.name());
    assertEquals(stored.version() + 1L, committed.version());
    assertEquals(T2, committed.updatedAt());
    assertEquals(stored.headEntryId(), committed.headEntryId());
    assertEquals(stored.creationRequestHash(), committed.creationRequestHash());
    assertEquals(stored.nextCommandSequence(), committed.nextCommandSequence());
    assertEquals(stored.createdAt(), committed.createdAt());
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
                      StoreTestSupport.threadState(
                          locked.id(),
                          locked.sessionId(),
                          locked.headEntryId(),
                          locked.yoloEnabled(),
                          locked.nextCommandSequence(),
                          locked.version(),
                          T1,
                          T2));
                }));
  }

  @Test
  void updateThreadRejectsNameRegressionWhenVersionNotBumped() {
    Baseline baseline = seedThreadBaseline(store);
    // 名称改变但 version 未 +1 的行直接构造会被共享校验拒绝（必须走 renameThread 转换）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  ThreadState locked = tx.lockThread(baseline.threadId()).orElseThrow();
                  tx.updateThread(
                      StoreTestSupport.threadState(
                          locked.id(),
                          locked.sessionId(),
                          locked.headEntryId(),
                          "branch 分析",
                          locked.yoloEnabled(),
                          locked.nextCommandSequence(),
                          locked.version(),
                          locked.createdAt(),
                          T2));
                }));
  }

  @Test
  void insertThenUpdateInTheSameTransactionIsAllowed() {
    Baseline baseline = seedThreadBaseline(store);
    UUID threadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertThread(thread(id, baseline.sessionId(), baseline.rootEntryId()));
              ThreadState inserted = tx.findThread(id).orElseThrow();
              tx.updateThread(
                  StoreTestSupport.threadState(
                      inserted.id(),
                      inserted.sessionId(),
                      inserted.headEntryId(),
                      true,
                      inserted.nextCommandSequence(),
                      inserted.version() + 1,
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
                  UUID id = tx.nextId();
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
                  UUID turnStartId = tx.nextId();
                  tx.insertEntry(
                      turnStartEntry(
                          turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
                  UUID assistantId = tx.nextId();
                  tx.insertEntry(
                      new Entry(
                          assistantId, baseline.sessionId(), turnStartId, assistantPayload(), T1));
                }));
    // 合法的 TURN_START -> USER -> ASSISTANT 链会被接受并以 path 形式读出
    UUID[] ids =
        store.transaction(
            tx -> {
              UUID turnStartId = tx.nextId();
              UUID userId = tx.nextId();
              UUID assistantId = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
              tx.insertEntry(
                  new Entry(userId, baseline.sessionId(), turnStartId, userMessagePayload(), T1));
              tx.insertEntry(
                  new Entry(assistantId, baseline.sessionId(), userId, assistantPayload(), T1));
              return new UUID[] {turnStartId, userId, assistantId};
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
                  tx.updateThread(
                      StoreTestSupport.threadState(
                          baseline.threadId(),
                          baseline.sessionId(),
                          TestIds.id(999),
                          1,
                          1,
                          T0,
                          T2));
                }));
    // 将 head 迁移到一个已存在 entry 是允许的
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.updateThread(
              StoreTestSupport.threadState(
                  baseline.threadId(), baseline.sessionId(), turnStartEntryId, 1, 1, T0, T2));
        });
    UUID committedHead =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow().headEntryId());
    assertEquals(turnStartEntryId, committedHead);
  }

  /** 测试意图：findRootEntry 成功返回已持久化的 ROOT Entry，且返回 Entry 必为 ROOT 类型。 */
  @Test
  void findRootEntryReturnsRootWhenPresent() {
    Baseline baseline = seedThreadBaseline(store);
    store.transaction(
        tx -> {
          Entry root = tx.findRootEntry(baseline.sessionId()).orElseThrow();
          assertEquals(baseline.rootEntryId(), root.id());
          assertEquals(baseline.sessionId(), root.sessionId());
          assertTrue(root.payload().type().isRoot());
          return null;
        });
  }

  /** 测试意图：仅创建 Session 但未插入 ROOT Entry 时，findRootEntry 返回 empty。 */
  @Test
  void findRootEntryReturnsEmptyWhenSessionHasNoRoot() {
    UUID sessionId = TestIds.id(1);
    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          assertTrue(tx.findRootEntry(sessionId).isEmpty());
          return null;
        });
  }

  /** 测试意图：对不存在的未知 Session，findRootEntry 返回 empty。 */
  @Test
  void findRootEntryReturnsEmptyForUnknownSession() {
    UUID unknownSessionId = TestIds.id(999);
    store.transaction(
        tx -> {
          assertTrue(tx.findRootEntry(unknownSessionId).isEmpty());
          return null;
        });
  }

  /** 测试意图：findRootEntry 拒绝 null sessionId 入参。 */
  @Test
  void findRootEntryRejectsNullSessionId() {
    assertThrows(NullPointerException.class, () -> store.transaction(tx -> tx.findRootEntry(null)));
  }

  /** 测试意图：loadContributorCustomEntriesOnPath 覆盖同一路径多个匹配时，严格保持 root-to-head 顺序，且返回不可变列表。 */
  @Test
  void loadContributorCustomEntriesOnPathReturnsEntriesInRootToHeadOrder() {
    Baseline baseline = seedThreadBaseline(store);
    UUID custom1 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("test.contributor", "type.a", 1, "{\"step\":1}"));
    UUID intermediateCustom =
        insertChildEntry(
            store,
            baseline.sessionId(),
            custom1,
            new CustomEntryPayload("other.contributor", "type.x", 1, "{\"skip\":true}"));
    UUID custom2 =
        insertChildEntry(
            store,
            baseline.sessionId(),
            intermediateCustom,
            new CustomEntryPayload("test.contributor", "type.b", 1, "{\"step\":2}"));

    store.transaction(
        tx -> {
          List<Entry> entries = tx.loadContributorCustomEntriesOnPath(custom2, "test.contributor");
          assertEquals(2, entries.size());
          assertEquals(List.of(custom1, custom2), entries.stream().map(Entry::id).toList());
          assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
          return null;
        });
  }

  /** 测试意图：loadContributorCustomEntriesOnPath 排除其他 contributor，并排除 sibling 分支上的 custom entry。 */
  @Test
  void loadContributorCustomEntriesOnPathExcludesOtherContributorsAndSiblings() {
    Baseline baseline = seedThreadBaseline(store);
    UUID sharedAlpha =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("contributor.alpha", "state", 1, "{\"common\":true}"));
    UUID sharedBeta =
        insertChildEntry(
            store,
            baseline.sessionId(),
            sharedAlpha,
            new CustomEntryPayload("contributor.beta", "state", 1, "{\"other\":true}"));
    UUID branch1Alpha =
        insertChildEntry(
            store,
            baseline.sessionId(),
            sharedBeta,
            new CustomEntryPayload("contributor.alpha", "state", 1, "{\"branch\":1}"));
    UUID branch2Alpha =
        insertChildEntry(
            store,
            baseline.sessionId(),
            sharedBeta,
            new CustomEntryPayload("contributor.alpha", "state", 1, "{\"branch\":2}"));

    store.transaction(
        tx -> {
          List<Entry> branch1AlphaEntries =
              tx.loadContributorCustomEntriesOnPath(branch1Alpha, "contributor.alpha");
          assertEquals(
              List.of(sharedAlpha, branch1Alpha),
              branch1AlphaEntries.stream().map(Entry::id).toList());

          List<Entry> branch2AlphaEntries =
              tx.loadContributorCustomEntriesOnPath(branch2Alpha, "contributor.alpha");
          assertEquals(
              List.of(sharedAlpha, branch2Alpha),
              branch2AlphaEntries.stream().map(Entry::id).toList());

          List<Entry> branch1BetaEntries =
              tx.loadContributorCustomEntriesOnPath(branch1Alpha, "contributor.beta");
          assertEquals(List.of(sharedBeta), branch1BetaEntries.stream().map(Entry::id).toList());
          return null;
        });
  }

  /** 测试意图：当路径上无匹配 custom entry 时返回空列表，包括 head 为 ROOT 或无任何匹配 contributor 的场景。 */
  @Test
  void loadContributorCustomEntriesOnPathReturnsEmptyWhenNoMatches() {
    Baseline baseline = seedThreadBaseline(store);
    store.transaction(
        tx -> {
          List<Entry> onRoot =
              tx.loadContributorCustomEntriesOnPath(baseline.rootEntryId(), "any.contributor");
          assertTrue(onRoot.isEmpty());
          assertThrows(UnsupportedOperationException.class, () -> onRoot.add(null));
          return null;
        });

    UUID otherCustom =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("other.contributor", "state", 1, "{\"ok\":true}"));

    store.transaction(
        tx -> {
          List<Entry> onOther =
              tx.loadContributorCustomEntriesOnPath(otherCustom, "target.contributor");
          assertTrue(onOther.isEmpty());
          return null;
        });
  }

  /** 测试意图：unknown head 必须 fail closed，抛出 IllegalArgumentException。 */
  @Test
  void loadContributorCustomEntriesOnPathRejectsUnknownHead() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> tx.loadContributorCustomEntriesOnPath(TestIds.id(99999), "any.contributor")));
  }

  /** 测试意图：loadContributorCustomEntriesOnPath 拒绝 null headEntryId 与 null contributorId。 */
  @Test
  void loadContributorCustomEntriesOnPathRejectsNullArguments() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        NullPointerException.class,
        () ->
            store.transaction(
                tx -> tx.loadContributorCustomEntriesOnPath(null, "any.contributor")));
    assertThrows(
        NullPointerException.class,
        () ->
            store.transaction(
                tx -> tx.loadContributorCustomEntriesOnPath(baseline.rootEntryId(), null)));
  }

  /** 测试意图：head 本身为不匹配的 CUSTOM Entry 时只作为 sentinel 参与路径完整性验证，绝不泄漏至结果中。 */
  @Test
  void loadContributorCustomEntriesOnPathNonMatchingHeadActsOnlyAsSentinel() {
    Baseline baseline = seedThreadBaseline(store);
    UUID targetCustom =
        insertChildEntry(
            store,
            baseline.sessionId(),
            baseline.rootEntryId(),
            new CustomEntryPayload("target.contributor", "type", 1, "{\"ok\":true}"));
    UUID otherHeadCustom =
        insertChildEntry(
            store,
            baseline.sessionId(),
            targetCustom,
            new CustomEntryPayload("other.contributor", "type", 1, "{\"other\":true}"));

    store.transaction(
        tx -> {
          // 查询 target.contributor：应只包含 targetCustom，不泄漏 head (otherHeadCustom)
          List<Entry> targetEntries =
              tx.loadContributorCustomEntriesOnPath(otherHeadCustom, "target.contributor");
          assertEquals(List.of(targetCustom), targetEntries.stream().map(Entry::id).toList());

          // 查询第三方 contributor：两者的 entry 均不应泄漏，返回空
          List<Entry> thirdEntries =
              tx.loadContributorCustomEntriesOnPath(otherHeadCustom, "third.contributor");
          assertTrue(thirdEntries.isEmpty());

          // 查询 other.contributor：此时 head 本身是匹配的，应正确包含 head
          List<Entry> otherEntries =
              tx.loadContributorCustomEntriesOnPath(otherHeadCustom, "other.contributor");
          assertEquals(List.of(otherHeadCustom), otherEntries.stream().map(Entry::id).toList());
          return null;
        });
  }

  /**
   * 测试意图：loadBranchSettings 以 ROOT settings 为基础，被路径上最近的非 COMPACTION TURN_START settings 覆盖，并与
   * EntryPath.baseSettings() 完全一致。
   */
  @Test
  void loadBranchSettingsFollowsRootAndLatestTurnStartOnPath() {
    Baseline baseline = seedThreadBaseline(store);
    UUID firstGoal = TestIds.id(41L);
    UUID secondGoal = TestIds.id(42L);
    BranchSettings root = store.transaction(tx -> tx.loadBranchSettings(baseline.rootEntryId()));
    assertEquals(branchSettings(), root);

    UUID firstTurnEnd =
        insertClosedInputTurn(
            baseline.sessionId(),
            baseline.rootEntryId(),
            T1,
            goalSettings(firstGoal, "first goal"));
    UUID secondTurnEnd =
        insertClosedInputTurn(
            baseline.sessionId(), firstTurnEnd, T2, goalSettings(secondGoal, "second goal"));

    store.transaction(
        tx -> {
          // 中间节点也命中最近的 TURN_START settings。
          assertEquals(goalSettings(firstGoal, "first goal"), tx.loadBranchSettings(firstTurnEnd));
          assertEquals(
              goalSettings(secondGoal, "second goal"), tx.loadBranchSettings(secondTurnEnd));
          return null;
        });
  }

  /** 测试意图：COMPACTION turn 的 settings 只描述压缩执行模型，绝不参与 branch settings 解析。 */
  @Test
  void loadBranchSettingsSkipsCompactionTurnStart() {
    Baseline baseline = seedThreadBaseline(store);
    UUID goal = TestIds.id(43L);
    UUID inputTurnEnd =
        insertClosedInputTurn(
            baseline.sessionId(), baseline.rootEntryId(), T1, goalSettings(goal, "user goal"));

    UUID compactionTurnStart =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              BranchSettings compactionSettings = goalSettings(TestIds.id(44L), "compaction goal");
              tx.insertEntry(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      inputTurnEnd,
                      new TurnStartPayload(
                          TurnStartReason.COMPACTION,
                          compactionSettings,
                          StoreTestSupport.OWNER_THREAD_ID,
                          StoreTestSupport.CONTEXT_WINDOW,
                          StoreTestSupport.MAX_OUTPUT_TOKENS,
                          new CompactionStart(
                              CompactionPhase.FULL,
                              CompactionTrigger.MANUAL,
                              compactionSettings.model(),
                              inputTurnEnd,
                              null,
                              null)),
                      T2));
              return id;
            });

    store.transaction(
        tx -> {
          assertEquals(goalSettings(goal, "user goal"), tx.loadBranchSettings(compactionTurnStart));
          return null;
        });
  }

  /** 测试意图：sibling fork 的 TURN_START settings 互不可见，各自只解析自己 head 路径上的最近快照。 */
  @Test
  void loadBranchSettingsIsScopedToTheHeadBranch() {
    Baseline baseline = seedThreadBaseline(store);
    UUID goalA = TestIds.id(45L);
    UUID goalB = TestIds.id(46L);
    UUID branchAEnd =
        insertClosedInputTurn(
            baseline.sessionId(), baseline.rootEntryId(), T1, goalSettings(goalA, "branch A goal"));
    UUID branchBEnd =
        insertClosedInputTurn(
            baseline.sessionId(), baseline.rootEntryId(), T2, goalSettings(goalB, "branch B goal"));

    store.transaction(
        tx -> {
          assertEquals(goalSettings(goalA, "branch A goal"), tx.loadBranchSettings(branchAEnd));
          assertEquals(goalSettings(goalB, "branch B goal"), tx.loadBranchSettings(branchBEnd));
          return null;
        });
  }

  /** 测试意图：尚未关闭（open / queued）turn 的 settings 在 TURN_START 落库后立即生效。 */
  @Test
  void loadBranchSettingsAppliesOpenTurnStartImmediately() {
    Baseline baseline = seedThreadBaseline(store);
    UUID goal = TestIds.id(47L);
    UUID openTurnStart =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      baseline.rootEntryId(),
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          goalSettings(goal, "open turn goal"),
                          StoreTestSupport.OWNER_THREAD_ID),
                      T1));
              return id;
            });

    store.transaction(
        tx -> {
          assertEquals(goalSettings(goal, "open turn goal"), tx.loadBranchSettings(openTurnStart));
          return null;
        });
  }

  /** 测试意图：unknown head 与 null 参数必须 fail closed。 */
  @Test
  void loadBranchSettingsRejectsUnknownHeadAndNullArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.loadBranchSettings(TestIds.id(99999))));
    assertThrows(
        NullPointerException.class, () -> store.transaction(tx -> tx.loadBranchSettings(null)));
  }

  /** 插入一个已关闭的 INPUT turn（TURN_START -> USER -> ASSISTANT -> TURN_END），返回其 TURN_END Entry id。 */
  private UUID insertClosedInputTurn(
      UUID sessionId, UUID parentId, Instant createdAt, BranchSettings settings) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          UUID userId = tx.nextId();
          UUID assistantId = tx.nextId();
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  parentId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, settings, StoreTestSupport.OWNER_THREAD_ID),
                  createdAt));
          tx.insertEntry(
              new Entry(userId, sessionId, turnStartId, userMessagePayload(), createdAt));
          tx.insertEntry(new Entry(assistantId, sessionId, userId, assistantPayload(), createdAt));
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  assistantId,
                  new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                  createdAt));
          return turnEndId;
        });
  }

  /** 带用户 Goal 的 branch settings 快照。 */
  private static BranchSettings goalSettings(UUID goalId, String text) {
    return branchSettings().withGoal(new GoalSetting(goalId, text));
  }
}
