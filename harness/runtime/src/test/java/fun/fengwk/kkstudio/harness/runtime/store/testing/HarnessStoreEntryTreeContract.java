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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.Baseline;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
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

  protected abstract HarnessStore createStore();

  /** 两个实现都必须拒绝同名根；失败的插入和重命名不能留下事实或推进版本。 */
  @Test
  void rootNameConflictsRollbackInsertAndRenameButAllowSelfReplay() {
    Baseline baseline = seedThreadBaseline(store);
    UUID duplicateId = TestIds.id(101);
    HarnessRuntimeConflictException insert =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                inTransaction(
                    store,
                    tx ->
                        tx.threads()
                            .insert(
                                thread(
                                    duplicateId, baseline.sessionId(), baseline.rootEntryId()))));
    assertEquals(HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT, insert.reason());
    assertTrue(store.transaction(tx -> tx.threads().find(duplicateId)).isEmpty());
    UUID siblingId = TestIds.id(102);
    inTransaction(
        store,
        tx ->
            tx.threads()
                .insert(
                    StoreTestSupport.siblingRoot(
                        siblingId, baseline.sessionId(), baseline.rootEntryId(), "branch")));
    ThreadState before = store.transaction(tx -> tx.threads().find(siblingId).orElseThrow());
    HarnessRuntimeConflictException rename =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                inTransaction(
                    store,
                    tx -> {
                      ThreadState locked = tx.threads().lock(siblingId).orElseThrow();
                      tx.threads().update(locked.renameThread("main", T1));
                    }));
    assertEquals(HarnessRuntimeConflictException.Reason.THREAD_NAME_CONFLICT, rename.reason());
    assertEquals(before, store.transaction(tx -> tx.threads().find(siblingId).orElseThrow()));
    inTransaction(store, tx -> tx.threads().update(tx.threads().lock(siblingId).orElseThrow()));
    assertEquals(before, store.transaction(tx -> tx.threads().find(siblingId).orElseThrow()));
    // 非 name 约束（重复主键）不能误分类为名称冲突。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.threads()
                        .insert(
                            StoreTestSupport.siblingRoot(
                                baseline.threadId(),
                                baseline.sessionId(),
                                baseline.rootEntryId(),
                                "unused-name"))));
  }

  @Test
  void sameRootNameIsAllowedAcrossSessionsAndForSubagents() {
    Baseline first = seedThreadBaseline(store);
    Baseline second = seedThreadBaseline(store);
    assertEquals(
        "main", store.transaction(tx -> tx.threads().find(second.threadId()).orElseThrow()).name());
    UUID childId = TestIds.id(101);
    ThreadState root = thread(childId, first.sessionId(), first.rootEntryId());
    ThreadState child =
        new ThreadState(
            root.id(),
            root.sessionId(),
            first.threadId(),
            root.headEntryId(),
            root.creationRequestHash(),
            root.name(),
            first.threadId(),
            false,
            SpawnTrigger.MODEL_TOOL,
            ThreadConstraints.NONE,
            root.executionControl(),
            root.inputThroughSequence(),
            root.nextCommandSequence(),
            root.version(),
            root.createdAt(),
            root.updatedAt());
    inTransaction(store, tx -> tx.threads().insert(child));
    assertEquals(child, store.transaction(tx -> tx.threads().find(childId).orElseThrow()));
  }

  @Test
  void sessionEntryAndThreadRoundTrip() {
    Baseline baseline = seedThreadBaseline(store);
    store.transaction(
        tx -> {
          assertEquals(
              session(baseline.sessionId()),
              tx.sessions().find(baseline.sessionId()).orElseThrow());
          assertEquals(
              rootEntry(baseline.rootEntryId(), baseline.sessionId()),
              tx.entries().find(baseline.rootEntryId()).orElseThrow());
          assertEquals(
              thread(baseline.threadId(), baseline.sessionId(), baseline.rootEntryId()),
              tx.threads().find(baseline.threadId()).orElseThrow());
          return null;
        });
  }

  @Test
  void sessionRoundTripPreservesCreatedAt() {
    UUID sessionId = TestIds.id(1);
    Session session = session(sessionId);
    inTransaction(store, tx -> tx.sessions().insert(session));
    assertEquals(session, store.transaction(tx -> tx.sessions().find(sessionId).orElseThrow()));
  }

  @Test
  void updateSessionReplacesOnlyNameUnderLockAndValidatesTransition() {
    // 测试意图：updateSession 是唯一受支持的 Session 行变更——锁内仅替换 name，id/createdAt 不可变；
    // 未锁、行不存在、身份改变都抛对应异常且行不被改动。
    UUID sessionId = TestIds.id(10);
    inTransaction(store, tx -> tx.sessions().insert(session(sessionId)));
    Session stored = store.transaction(tx -> tx.sessions().find(sessionId)).orElseThrow();
    Session renamed = new Session(sessionId, "renamed session", stored.createdAt());

    // 未锁定时更新被拒。
    assertThrows(
        IllegalStateException.class,
        () -> inTransaction(store, tx -> tx.sessions().update(renamed)));
    // 锁定后更新成功且只替换名称。
    inTransaction(
        store,
        tx -> {
          tx.sessions().lockForKeyShare(sessionId).orElseThrow();
          tx.sessions().update(renamed);
        });
    assertEquals(renamed, store.transaction(tx -> tx.sessions().find(sessionId)).orElseThrow());

    // 行不存在：lock 返回 empty（不产生锁），未锁定更新抛 IllegalStateException。
    UUID ghostId = TestIds.id(11);
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                store,
                tx -> {
                  tx.sessions().lockForKeyShare(ghostId);
                  tx.sessions().update(new Session(ghostId, "ghost", stored.createdAt()));
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
                  tx.sessions().lockForKeyShare(sessionId).orElseThrow();
                  tx.sessions().update(movedCreatedAt);
                }));
    // 拒绝后行保持原名。
    assertEquals(renamed, store.transaction(tx -> tx.sessions().find(sessionId)).orElseThrow());
  }

  @Test
  void updateSessionAcceptsExactReplayAndForUpdateLock() {
    // 测试意图：updateSession 支持 lockSessionForUpdate（rename 控制面用）且 exact replay 是合法迁移。
    UUID sessionId = TestIds.id(13);
    inTransaction(store, tx -> tx.sessions().insert(session(sessionId)));
    Session stored = store.transaction(tx -> tx.sessions().find(sessionId)).orElseThrow();
    inTransaction(
        store,
        tx -> {
          tx.sessions().lockForUpdate(sessionId).orElseThrow();
          tx.sessions().update(stored);
        });
    assertEquals(stored, store.transaction(tx -> tx.sessions().find(sessionId)).orElseThrow());
  }

  @Test
  void sessionEntryAndThreadTimestampsRejectSubMillisecondPrecision() {
    UUID sessionId = TestIds.id(1);
    Session session = new Session(sessionId, "session-" + sessionId, T0.plusNanos(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.sessions().insert(session)));
    assertTrue(store.<Boolean>transaction(tx -> tx.sessions().find(sessionId).isEmpty()));

    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.entries()
                        .insert(
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
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.threads().insert(thread)));
  }

  @Test
  void duplicateSessionIdIsRejected() {
    UUID sessionId = TestIds.id(1);
    inTransaction(store, tx -> tx.sessions().insert(session(sessionId)));
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.sessions().insert(session(sessionId))));
  }

  @Test
  void rootEntryRequiresExistingSession() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store, tx -> tx.entries().insert(rootEntry(TestIds.id(1), TestIds.id(999)))));
  }

  @Test
  void eachSessionHasAtMostOneRoot() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx -> tx.entries().insert(rootEntry(TestIds.id(100), baseline.sessionId()))));
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
                  tx.sessions().insert(session(sessionId));
                  tx.entries().insert(turnStartEntry(TestIds.id(5), sessionId, TestIds.id(1), T1));
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
                    tx.entries()
                        .insert(
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
                  tx.sessions().insert(session(otherSession));
                  tx.entries().insert(rootEntry(otherRoot, otherSession));
                  tx.entries()
                      .insert(turnStartEntry(TestIds.id(11), baseline.sessionId(), otherRoot, T1));
                }));
  }

  @Test
  void loadEntryPathWalksFromHeadToRoot() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnStartEntryId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    store.transaction(
        tx -> {
          EntryPath path = tx.entries().loadPath(turnStartEntryId);
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
          EntryPath path = tx.entries().loadPath(baseline.rootEntryId());
          assertEquals(
              List.of(baseline.rootEntryId()), path.entries().stream().map(Entry::id).toList());
          return null;
        });
  }

  /**
   * STOP barrier Turn（显式 idle Stop 的持久形态）端到端往返：TURN_START(STOP) → ASSISTANT_ERROR(CANCELLED) →
   * TURN_END(STOPPED, closeRequestId) 必须逐字段持久化并可重新加载（新枚举值只走 JSON，无 DDL 影响）。
   */
  @Test
  void stopBarrierTurnRoundTripsThroughTheStore() {
    Baseline baseline = seedThreadBaseline(store);
    EntryPath reloaded =
        store.transaction(
            tx -> {
              UUID turnStartId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          turnStartId,
                          baseline.sessionId(),
                          baseline.rootEntryId(),
                          new TurnStartPayload(
                              TurnStartReason.STOP, branchSettings(), baseline.threadId()),
                          T1));
              UUID barrierId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          barrierId,
                          baseline.sessionId(),
                          turnStartId,
                          new AssistantErrorPayload(
                              new AssistantError("CANCELLED", "Cancelled by user"), null),
                          T1));
              UUID closeRequestId = tx.nextId();
              UUID turnEndId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          turnEndId,
                          baseline.sessionId(),
                          barrierId,
                          new TurnEndPayload(
                              turnStartId,
                              TurnEndOutcome.STOPPED,
                              false,
                              TurnEndReason.USER_STOP,
                              closeRequestId),
                          T1));
              return tx.entries().loadPath(turnEndId);
            });

    assertEquals(4, reloaded.entries().size());
    TurnStartPayload start = (TurnStartPayload) reloaded.entries().get(1).payload();
    assertEquals(TurnStartReason.STOP, start.reason());
    assertEquals(baseline.threadId(), start.ownerThreadId());
    assertInstanceOf(AssistantErrorPayload.class, reloaded.entries().get(2).payload());
    TurnEndPayload end = (TurnEndPayload) reloaded.head().payload();
    assertEquals(TurnEndOutcome.STOPPED, end.outcome());
    assertEquals(TurnEndReason.USER_STOP, end.reason());
    assertEquals(start.ownerThreadId(), start.ownerThreadId());
    assertEquals(reloaded.entries().get(1).id(), end.turnStartEntryId());
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
                tx.entries()
                    .insert(turnStartEntry(turnStartId, baseline.sessionId(), parentId, turnTime));
                tx.entries()
                    .insert(
                        new Entry(
                            userId,
                            baseline.sessionId(),
                            turnStartId,
                            userMessagePayload(),
                            turnTime));
                tx.entries()
                    .insert(
                        new Entry(
                            assistantId,
                            baseline.sessionId(),
                            userId,
                            assistantPayload(),
                            turnTime));
                tx.entries()
                    .insert(
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
          EntryPath path = tx.entries().loadPath(deepHeadId);
          assertEquals(expectedIds, path.entries().stream().map(Entry::id).toList());
          assertEquals(baseline.rootEntryId(), path.root().id());
          assertEquals(deepHeadId, path.head().id());
          return null;
        });
  }

  /** 测试意图：祖先游标返回其后的连续子链且不含游标；游标即 head 时为空增量；null 游标返回完整链。 */
  @Test
  void loadSliceReturnsStrictSuffixAfterAncestorAndEmptySliceAtHead() {
    Baseline baseline = seedThreadBaseline(store);
    UUID turnEnd =
        insertClosedInputTurn(baseline.sessionId(), baseline.rootEntryId(), T1, branchSettings());
    UUID fork =
        insertChildEntry(
            store, baseline.sessionId(), turnEnd, ForkPayload.branch(baseline.rootEntryId()));

    store.transaction(
        tx -> {
          EntrySlice afterRoot = tx.entries().loadSlice(fork, baseline.rootEntryId());
          List<UUID> full = tx.entries().loadPath(fork).entries().stream().map(Entry::id).toList();
          assertEquals(baseline.rootEntryId(), afterRoot.baseEntryId());
          assertEquals(
              full.subList(1, full.size()), afterRoot.entries().stream().map(Entry::id).toList());

          EntrySlice atHead = tx.entries().loadSlice(fork, fork);
          assertEquals(fork, atHead.baseEntryId());
          assertTrue(atHead.entries().isEmpty());

          EntrySlice complete = tx.entries().loadSlice(fork, null);
          assertNull(complete.baseEntryId());
          assertEquals(full, complete.entries().stream().map(Entry::id).toList());
          return null;
        });
  }

  /** 测试意图：压缩节点仍留在增量链中，且只从压缩 TURN_START 之后截取。 */
  @Test
  void loadSliceKeepsCompactionNodesAfterTheRequestedAncestor() {
    Baseline baseline = seedThreadBaseline(store);
    UUID inputTurnEnd =
        insertClosedInputTurn(baseline.sessionId(), baseline.rootEntryId(), T1, branchSettings());
    UUID compactionEnd = insertClosedCompactionTurn(baseline.sessionId(), inputTurnEnd, T2);

    store.transaction(
        tx -> {
          EntrySlice slice = tx.entries().loadSlice(compactionEnd, inputTurnEnd);
          assertEquals(inputTurnEnd, slice.baseEntryId());
          assertEquals(3, slice.entries().size());
          assertInstanceOf(TurnStartPayload.class, slice.entries().get(0).payload());
          assertEquals(
              TurnStartReason.COMPACTION,
              ((TurnStartPayload) slice.entries().get(0).payload()).reason());
          assertInstanceOf(CompactionPayload.class, slice.entries().get(1).payload());
          assertEquals(compactionEnd, slice.entries().get(2).id());
          return null;
        });
  }

  /** 测试意图：旁支、另一 Session 与未知游标都退回完整链，不把无关节点当成 base。 */
  @Test
  void loadSliceReturnsFullPathForNonAncestorOtherSessionAndUnknownCursor() {
    Baseline baseline = seedThreadBaseline(store);
    UUID branchA =
        insertClosedInputTurn(baseline.sessionId(), baseline.rootEntryId(), T1, branchSettings());
    UUID branchB =
        insertClosedInputTurn(baseline.sessionId(), baseline.rootEntryId(), T2, branchSettings());
    UUID otherHead =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              tx.sessions().insert(session(sessionId));
              tx.entries().insert(rootEntry(rootId, sessionId));
              return rootId;
            });

    store.transaction(
        tx -> {
          List<UUID> full =
              tx.entries().loadPath(branchA).entries().stream().map(Entry::id).toList();
          EntrySlice sibling = tx.entries().loadSlice(branchA, branchB);
          EntrySlice otherSession = tx.entries().loadSlice(branchA, otherHead);
          EntrySlice unknown = tx.entries().loadSlice(branchA, TestIds.id(4242));
          assertNull(sibling.baseEntryId());
          assertNull(otherSession.baseEntryId());
          assertNull(unknown.baseEntryId());
          assertEquals(full, sibling.entries().stream().map(Entry::id).toList());
          assertEquals(full, otherSession.entries().stream().map(Entry::id).toList());
          assertEquals(full, unknown.entries().stream().map(Entry::id).toList());
          return null;
        });
  }

  /** 测试意图：缺失 head 与空 head 参数沿 loadPath 失败，不返回空增量。 */
  @Test
  void loadSliceRejectsMissingHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.entries().loadSlice(TestIds.id(42), null)));
    assertThrows(
        NullPointerException.class,
        () -> store.transaction(tx -> tx.entries().loadSlice(null, TestIds.id(1))));
  }

  @Test
  void loadEntryPathRejectsUnknownHead() {
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.entries().loadPath(TestIds.id(42))));
  }

  @Test
  void loadEntriesBySessionIdReturnsImmutableStableOrderAndRejectsMissingSession() {
    Baseline baseline = seedThreadBaseline(store);
    UUID otherSessionId =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootId = tx.nextId();
              tx.sessions().insert(session(sessionId));
              tx.entries().insert(rootEntry(rootId, sessionId));
              return sessionId;
            });
    UUID sameTimeLarge = new UUID(0x8000000000000000L, 2L);
    UUID sameTimeSmall = new UUID(0x8000000000000000L, 1L);
    UUID laterTurnStartId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.entries()
                  .insert(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          // 同 createdAt 先写入较大 UUID，再写入较小 UUID，证明排序不是插入序。
          tx.entries()
              .insert(
                  new Entry(
                      sameTimeLarge,
                      baseline.sessionId(),
                      laterTurnStartId,
                      userMessagePayload(),
                      T2));
          tx.entries()
              .insert(
                  new Entry(
                      sameTimeSmall,
                      baseline.sessionId(),
                      laterTurnStartId,
                      userMessagePayload(),
                      T2));
        });

    store.transaction(
        tx -> {
          List<Entry> entries = tx.entries().loadBySession(baseline.sessionId());
          assertEquals(
              List.of(baseline.rootEntryId(), laterTurnStartId, sameTimeSmall, sameTimeLarge),
              entries.stream().map(Entry::id).toList());
          assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
          assertEquals(
              1, tx.entries().loadBySession(otherSessionId).size(), "must filter by session");
          return null;
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> inTransaction(store, tx -> tx.entries().loadBySession(TestIds.id(999))));
  }

  @Test
  void insertThreadRequiresExistingHeadEntry() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            inTransaction(
                store,
                tx ->
                    tx.threads()
                        .insert(thread(TestIds.id(100), TestIds.id(888), TestIds.id(999)))));
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
                    tx.threads()
                        .insert(
                            thread(
                                baseline.threadId(),
                                baseline.sessionId(),
                                baseline.rootEntryId()))));
  }

  @Test
  void findAndLockThreadReturnEmptyForMissingIds() {
    UUID missingId = TestIds.id(1);
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().find(missingId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.threads().lock(missingId).isEmpty()));
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
                    tx.threads()
                        .update(
                            thread(
                                baseline.threadId(),
                                baseline.sessionId(),
                                baseline.rootEntryId()))));
  }

  @Test
  void updateThreadAfterLockCommitsNewCurrentState() {
    Baseline baseline = seedThreadBaseline(store);
    inTransaction(
        store,
        tx -> {
          ThreadState locked = tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads()
              .update(
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
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());
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
    ThreadState stored =
        store.transaction(tx -> tx.threads().find(baseline.threadId())).orElseThrow();
    ThreadState renamed = stored.renameThread("branch 分析", T2);
    inTransaction(
        store,
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.threads().update(renamed);
        });
    ThreadState committed =
        store.transaction(tx -> tx.threads().find(baseline.threadId())).orElseThrow();
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
                  ThreadState locked = tx.threads().lock(baseline.threadId()).orElseThrow();
                  tx.threads()
                      .update(
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
                  ThreadState locked = tx.threads().lock(baseline.threadId()).orElseThrow();
                  tx.threads()
                      .update(
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
              tx.threads()
                  .insert(
                      StoreTestSupport.siblingRoot(
                          id, baseline.sessionId(), baseline.rootEntryId(), "sibling"));
              ThreadState inserted = tx.threads().find(id).orElseThrow();
              tx.threads()
                  .update(
                      StoreTestSupport.threadState(
                          inserted.id(),
                          inserted.sessionId(),
                          inserted.headEntryId(),
                          "sibling",
                          true,
                          inserted.nextCommandSequence(),
                          inserted.version() + 1,
                          inserted.createdAt(),
                          T2));
              return id;
            });
    assertTrue(
        store.<Boolean>transaction(tx -> tx.threads().find(threadId).orElseThrow().yoloEnabled()));
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
                  tx.entries()
                      .insert(
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
                  tx.entries()
                      .insert(
                          turnStartEntry(
                              turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
                  UUID assistantId = tx.nextId();
                  tx.entries()
                      .insert(
                          new Entry(
                              assistantId,
                              baseline.sessionId(),
                              turnStartId,
                              assistantPayload(),
                              T1));
                }));
    // 合法的 TURN_START -> USER -> ASSISTANT 链会被接受并以 path 形式读出
    UUID[] ids =
        store.transaction(
            tx -> {
              UUID turnStartId = tx.nextId();
              UUID userId = tx.nextId();
              UUID assistantId = tx.nextId();
              tx.entries()
                  .insert(
                      turnStartEntry(
                          turnStartId, baseline.sessionId(), baseline.rootEntryId(), T1));
              tx.entries()
                  .insert(
                      new Entry(
                          userId, baseline.sessionId(), turnStartId, userMessagePayload(), T1));
              tx.entries()
                  .insert(
                      new Entry(assistantId, baseline.sessionId(), userId, assistantPayload(), T1));
              return new UUID[] {turnStartId, userId, assistantId};
            });
    store.transaction(
        tx -> {
          EntryPath path = tx.entries().loadPath(ids[2]);
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
                  tx.threads().lock(baseline.threadId());
                  tx.threads()
                      .update(
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
              tx.entries()
                  .insert(turnStartEntry(id, baseline.sessionId(), baseline.rootEntryId(), T1));
              return id;
            });
    inTransaction(
        store,
        tx -> {
          tx.threads().lock(baseline.threadId());
          tx.threads()
              .update(
                  StoreTestSupport.threadState(
                      baseline.threadId(), baseline.sessionId(), turnStartEntryId, 1, 1, T0, T2));
        });
    UUID committedHead =
        store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow().headEntryId());
    assertEquals(turnStartEntryId, committedHead);
  }

  /** 测试意图：findRootEntry 成功返回已持久化的 ROOT Entry，且返回 Entry 必为 ROOT 类型。 */
  @Test
  void findRootEntryReturnsRootWhenPresent() {
    Baseline baseline = seedThreadBaseline(store);
    store.transaction(
        tx -> {
          Entry root = tx.entries().findRoot(baseline.sessionId()).orElseThrow();
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
          tx.sessions().insert(session(sessionId));
          assertTrue(tx.entries().findRoot(sessionId).isEmpty());
          return null;
        });
  }

  /** 测试意图：对不存在的未知 Session，findRootEntry 返回 empty。 */
  @Test
  void findRootEntryReturnsEmptyForUnknownSession() {
    UUID unknownSessionId = TestIds.id(999);
    store.transaction(
        tx -> {
          assertTrue(tx.entries().findRoot(unknownSessionId).isEmpty());
          return null;
        });
  }

  /** 测试意图：findRootEntry 拒绝 null sessionId 入参。 */
  @Test
  void findRootEntryRejectsNullSessionId() {
    assertThrows(
        NullPointerException.class, () -> store.transaction(tx -> tx.entries().findRoot(null)));
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
          List<Entry> entries = tx.entries().loadContributorCustom(custom2, "test.contributor");
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
              tx.entries().loadContributorCustom(branch1Alpha, "contributor.alpha");
          assertEquals(
              List.of(sharedAlpha, branch1Alpha),
              branch1AlphaEntries.stream().map(Entry::id).toList());

          List<Entry> branch2AlphaEntries =
              tx.entries().loadContributorCustom(branch2Alpha, "contributor.alpha");
          assertEquals(
              List.of(sharedAlpha, branch2Alpha),
              branch2AlphaEntries.stream().map(Entry::id).toList());

          List<Entry> branch1BetaEntries =
              tx.entries().loadContributorCustom(branch1Alpha, "contributor.beta");
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
              tx.entries().loadContributorCustom(baseline.rootEntryId(), "any.contributor");
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
              tx.entries().loadContributorCustom(otherCustom, "target.contributor");
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
                tx -> tx.entries().loadContributorCustom(TestIds.id(99999), "any.contributor")));
  }

  /** 测试意图：loadContributorCustomEntriesOnPath 拒绝 null headEntryId 与 null contributorId。 */
  @Test
  void loadContributorCustomEntriesOnPathRejectsNullArguments() {
    Baseline baseline = seedThreadBaseline(store);
    assertThrows(
        NullPointerException.class,
        () -> store.transaction(tx -> tx.entries().loadContributorCustom(null, "any.contributor")));
    assertThrows(
        NullPointerException.class,
        () ->
            store.transaction(
                tx -> tx.entries().loadContributorCustom(baseline.rootEntryId(), null)));
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
              tx.entries().loadContributorCustom(otherHeadCustom, "target.contributor");
          assertEquals(List.of(targetCustom), targetEntries.stream().map(Entry::id).toList());

          // 查询第三方 contributor：两者的 entry 均不应泄漏，返回空
          List<Entry> thirdEntries =
              tx.entries().loadContributorCustom(otherHeadCustom, "third.contributor");
          assertTrue(thirdEntries.isEmpty());

          // 查询 other.contributor：此时 head 本身是匹配的，应正确包含 head
          List<Entry> otherEntries =
              tx.entries().loadContributorCustom(otherHeadCustom, "other.contributor");
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
    BranchSettings root =
        store.transaction(tx -> tx.entries().loadBranchSettings(baseline.rootEntryId()));
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
          assertEquals(
              goalSettings(firstGoal, "first goal"), tx.entries().loadBranchSettings(firstTurnEnd));
          assertEquals(
              goalSettings(secondGoal, "second goal"),
              tx.entries().loadBranchSettings(secondTurnEnd));
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
              tx.entries()
                  .insert(
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
                              CompactionStart.pending(
                                  CompactionPhase.FULL,
                                  CompactionTrigger.MANUAL,
                                  inputTurnEnd,
                                  null,
                                  null),
                              null),
                          T2));
              return id;
            });

    store.transaction(
        tx -> {
          assertEquals(
              goalSettings(goal, "user goal"),
              tx.entries().loadBranchSettings(compactionTurnStart));
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
          assertEquals(
              goalSettings(goalA, "branch A goal"), tx.entries().loadBranchSettings(branchAEnd));
          assertEquals(
              goalSettings(goalB, "branch B goal"), tx.entries().loadBranchSettings(branchBEnd));
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
              tx.entries()
                  .insert(
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
          assertEquals(
              goalSettings(goal, "open turn goal"), tx.entries().loadBranchSettings(openTurnStart));
          return null;
        });
  }

  /** 测试意图：unknown head 与 null 参数必须 fail closed。 */
  @Test
  void loadBranchSettingsRejectsUnknownHeadAndNullArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.transaction(tx -> tx.entries().loadBranchSettings(TestIds.id(99999))));
    assertThrows(
        NullPointerException.class,
        () -> store.transaction(tx -> tx.entries().loadBranchSettings(null)));
  }

  /** 插入一个已关闭的 COMPACTION turn，返回其 TURN_END Entry id。 */
  private UUID insertClosedCompactionTurn(UUID sessionId, UUID parentId, Instant createdAt) {
    return store.transaction(
        tx -> {
          UUID turnStartId = tx.nextId();
          UUID summaryId = tx.nextId();
          UUID turnEndId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      sessionId,
                      parentId,
                      new TurnStartPayload(
                          TurnStartReason.COMPACTION,
                          branchSettings(),
                          StoreTestSupport.OWNER_THREAD_ID,
                          StoreTestSupport.CONTEXT_WINDOW,
                          StoreTestSupport.MAX_OUTPUT_TOKENS,
                          CompactionStart.pending(
                              CompactionPhase.FULL, CompactionTrigger.MANUAL, parentId, null, null),
                          null),
                      createdAt));
          tx.entries()
              .insert(
                  new Entry(
                      summaryId,
                      sessionId,
                      turnStartId,
                      new CompactionPayload("compacted history", null),
                      createdAt));
          tx.entries()
              .insert(
                  new Entry(
                      turnEndId,
                      sessionId,
                      summaryId,
                      new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, false, null, null),
                      createdAt));
          return turnEndId;
        });
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
          tx.entries()
              .insert(
                  new Entry(
                      turnStartId,
                      sessionId,
                      parentId,
                      new TurnStartPayload(
                          TurnStartReason.INPUT, settings, StoreTestSupport.OWNER_THREAD_ID),
                      createdAt));
          tx.entries()
              .insert(new Entry(userId, sessionId, turnStartId, userMessagePayload(), createdAt));
          tx.entries()
              .insert(new Entry(assistantId, sessionId, userId, assistantPayload(), createdAt));
          tx.entries()
              .insert(
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
