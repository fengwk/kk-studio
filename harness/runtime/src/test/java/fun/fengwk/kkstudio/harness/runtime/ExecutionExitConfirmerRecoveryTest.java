package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalStage;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.MemoryExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.driver.DriverThreadDeletion;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;
import fun.fengwk.kkstudio.harness.spi.store.ExecutionExitStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.ThreadStore;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** 首轮恢复的崩溃窗口：EXIT_PROOF 重放、旧 PREPARED 对账、当前 PREPARED 不被误判。 */
class ExecutionExitConfirmerRecoveryTest {

  @Test
  void replaysExitProofThenConfirmsPreparedOwnerExit() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionExitFact proofed = register(store, baseline.threadId(), TestIds.id(10));
    journal.write(
        ExecutionExitJournalRecord.exitProof(
            proofed, ExecutionExitState.STUDIO_EXITED, T0.plusMillis(10)));
    ExecutionExitFact prepared = register(store, baseline.threadId(), TestIds.id(11));
    journal.write(prepared(prepared));
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();

    assertEquals(List.of(prepared.executionId()), report.ownerExited());
    assertTrue(report.cleaned().isEmpty());
    assertTrue(report.retained().isEmpty());
    assertEquals(ExecutionExitState.STUDIO_EXITED, stored(store, proofed.executionId()).state());
    assertEquals(ExecutionExitState.OWNER_EXITED, stored(store, prepared.executionId()).state());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void cleansPreparedWhoseFactAlreadyHasEvidenceWithoutRewritingIt() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(12));
    store.transaction(
        tx -> {
          tx.threads().lock(fact.threadId());
          return tx.exits().confirm(fact, ExecutionExitState.STUDIO_EXITED, T0.plusMillis(3));
        });
    journal.write(prepared(fact));
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();

    assertEquals(List.of(fact.executionId()), report.cleaned());
    ExecutionExitFact after = stored(store, fact.executionId());
    assertEquals(ExecutionExitState.STUDIO_EXITED, after.state());
    assertEquals(T0.plusMillis(3), after.confirmedAt());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void cleansPreparedWithoutFactOnlyAfterAcquiringTheThreadLock() {
    InMemoryHarnessStore memory = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(memory);
    AtomicBoolean factReadBeforeThreadLock = new AtomicBoolean();
    HarnessStore store = recordingStore(memory, factReadBeforeThreadLock);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    LocalExecutionIdentity identity =
        new LocalExecutionIdentity(
            TestIds.id(13),
            baseline.threadId(),
            TestIds.id(14),
            1,
            TestIds.id(15),
            ExecutionExitKind.TOOL);
    journal.write(ExecutionExitJournalRecord.prepared(identity));
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();

    assertFalse(
        factReadBeforeThreadLock.get(),
        "the authoritative fact must not be read before the Thread lock");
    assertEquals(List.of(identity.executionId()), report.cleaned());
    assertTrue(journal.loadAll().records().isEmpty());
    assertTrue(memory.transaction(tx -> tx.exits().find(identity.executionId())).isEmpty());
  }

  @Test
  void cleansPreparedWhoseThreadWasDeleted() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    LocalExecutionIdentity identity =
        new LocalExecutionIdentity(
            TestIds.id(16),
            baseline.threadId(),
            TestIds.id(17),
            1,
            TestIds.id(18),
            ExecutionExitKind.TOOL);
    journal.write(ExecutionExitJournalRecord.prepared(identity));
    store.transaction(
        tx -> {
          tx.threads().lockTree(baseline.threadId());
          tx.threads().lock(baseline.threadId());
          tx.threads().delete(List.of(baseline.threadId()), DriverThreadDeletion.NONE);
          return null;
        });
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();

    assertEquals(List.of(identity.executionId()), report.cleaned());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void retainsIdentityConflictWithoutTouchingTheDurableFact() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionExitFact conflicting = register(store, baseline.threadId(), TestIds.id(20));
    LocalExecutionIdentity recorded =
        new LocalExecutionIdentity(
            conflicting.executionId(),
            conflicting.threadId(),
            TestIds.id(21),
            conflicting.attempt(),
            conflicting.ownerLeaseToken(),
            conflicting.kind());
    journal.write(ExecutionExitJournalRecord.prepared(recorded));
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();

    assertEquals(List.of(conflicting.executionId()), report.retained());
    assertTrue(report.ownerExited().isEmpty());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(store, conflicting.executionId()).state());
    assertEquals(1, journal.loadAll().records().size());
  }

  @Test
  void periodicReplayNeverTreatsCurrentProcessPreparedAsOwnerExit() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());
    confirmer.recoverPreviousRun();

    ExecutionExitFact current = register(store, baseline.threadId(), TestIds.id(22));
    journal.write(prepared(current));
    confirmer.replayPending();

    assertEquals(ExecutionExitState.UNCONFIRMED, stored(store, current.executionId()).state());
    ExecutionExitJournalRecord record = journal.loadAll().records().get(0);
    assertEquals(current.executionId(), record.executionId());
    assertEquals(ExecutionExitJournalStage.PREPARED, record.stage());
  }

  @Test
  void databaseFailureKeepsTheFrozenPreparedForTheSameReplayer() {
    InMemoryHarnessStore memory = new InMemoryHarnessStore();
    MutableClock clock = new MutableClock();
    HarnessRuntimeTestSupport.useClock(memory, clock);
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(memory);
    MemoryExecutionExitJournal journal = new MemoryExecutionExitJournal();
    ExecutionExitFact fact = register(memory, baseline.threadId(), TestIds.id(23));
    journal.write(prepared(fact));
    AtomicBoolean failNext = new AtomicBoolean(true);
    HarnessStore failing =
        (HarnessStore)
            Proxy.newProxyInstance(
                HarnessStore.class.getClassLoader(),
                new Class<?>[] {HarnessStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("transaction")
                      && failNext.compareAndSet(true, false)) {
                    throw new IllegalStateException("database unavailable");
                  }
                  return method.invoke(memory, args);
                });
    ExecutionExitConfirmer confirmer = new ExecutionExitConfirmer(failing, journal, clock);

    LocalExecutionRecovery report = confirmer.recoverPreviousRun();
    assertEquals(List.of(fact.executionId()), report.retained());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(memory, fact.executionId()).state());
    assertEquals(1, journal.loadAll().records().size());

    clock.advance(Duration.ofSeconds(2));
    confirmer.replayPending();
    assertEquals(ExecutionExitState.OWNER_EXITED, stored(memory, fact.executionId()).state());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void corruptJournalFileIsRetainedWhileHealthyRecordsAreRecovered(@TempDir Path directory)
      throws IOException {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    ExecutionExitFact proofed = register(store, baseline.threadId(), TestIds.id(24));
    ExecutionExitFact prepared = register(store, baseline.threadId(), TestIds.id(25));
    Path corrupt = directory.resolve(UUID.randomUUID() + ".json");
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      journal.write(
          ExecutionExitJournalRecord.exitProof(
              proofed, ExecutionExitState.STUDIO_EXITED, T0.plusMillis(10)));
      journal.write(prepared(prepared));
      Files.writeString(corrupt, "{\"bogus\":true}");
      ExecutionExitConfirmer confirmer = confirmer(store, journal, fixedClock());

      LocalExecutionRecovery report = confirmer.recoverPreviousRun();

      assertEquals(List.of(prepared.executionId()), report.ownerExited());
      ExecutionExitJournalSnapshot snapshot = journal.loadAll();
      assertTrue(snapshot.records().isEmpty());
      assertEquals(1, snapshot.failures().size());
      assertTrue(Files.exists(corrupt));
    }
    assertEquals(ExecutionExitState.STUDIO_EXITED, stored(store, proofed.executionId()).state());
    assertEquals(ExecutionExitState.OWNER_EXITED, stored(store, prepared.executionId()).state());
  }

  @Test
  void unreadableDirectoryRefusesStartupAndCannotBeRecoveredTwice() {
    // 无法冻结旧记录时必须拒绝启动，不能先放行新执行后再猜测旧 owner。
    FaultJournal journal = new FaultJournal();
    journal.failLoad = true;
    ExecutionExitConfirmer confirmer = confirmer(fixedStore(), journal, fixedClock());
    assertThrows(ExecutionExitJournalException.class, confirmer::recoverPreviousRun);
    journal.failLoad = false;
    assertThrows(IllegalStateException.class, confirmer::recoverPreviousRun);
  }

  @Test
  void preparedCleanupFailureIsRetainedAndReplayedWithoutInventingAnExit() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    FaultJournal journal = new FaultJournal();
    MutableClock clock = new MutableClock();
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(31));
    store.transaction(
        tx -> {
          tx.threads().lock(fact.threadId());
          return tx.exits().confirm(fact, ExecutionExitState.NOT_STARTED, T0.plusMillis(1));
        });
    journal.write(prepared(fact));
    journal.failDelete = true;
    ExecutionExitConfirmer confirmer = confirmer(store, journal, clock);

    assertEquals(List.of(fact.executionId()), confirmer.recoverPreviousRun().retained());
    assertEquals(
        ExecutionExitJournalStage.PREPARED, journal.loadAll().records().getFirst().stage());
    journal.failDelete = false;
    clock.advance(Duration.ofSeconds(2));
    confirmer.replayPending();

    // 文件删除失败只重放清理；数据库中先前的真实结论与时间不得改变。
    assertTrue(journal.loadAll().records().isEmpty());
    assertEquals(ExecutionExitState.NOT_STARTED, stored(store, fact.executionId()).state());
    assertEquals(T0.plusMillis(1), stored(store, fact.executionId()).confirmedAt());
  }

  @Test
  void ownerExitProofWriteFailureRetainsPreparedUntilDurableReplay() {
    InMemoryHarnessStore store = fixedStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    FaultJournal journal = new FaultJournal();
    MutableClock clock = new MutableClock();
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(32));
    journal.write(prepared(fact));
    journal.failWrite = true;
    ExecutionExitConfirmer confirmer = confirmer(store, journal, clock);

    assertEquals(List.of(fact.executionId()), confirmer.recoverPreviousRun().retained());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(store, fact.executionId()).state());
    assertEquals(
        ExecutionExitJournalStage.PREPARED, journal.loadAll().records().getFirst().stage());
    journal.failWrite = false;
    clock.advance(Duration.ofSeconds(2));
    confirmer.replayPending();

    assertEquals(ExecutionExitState.OWNER_EXITED, stored(store, fact.executionId()).state());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  /** 只注入持久日志边界故障，不改变记录的阶段和身份语义。 */
  private static final class FaultJournal implements ExecutionExitJournal {
    private final MemoryExecutionExitJournal delegate = new MemoryExecutionExitJournal();
    private boolean failLoad;
    private boolean failWrite;
    private boolean failDelete;

    @Override
    public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
      if (failWrite) {
        throw new ExecutionExitJournalException("proof storage unavailable");
      }
      return delegate.write(record);
    }

    @Override
    public void delete(UUID executionId) {
      if (failDelete) {
        throw new ExecutionExitJournalException("cleanup unavailable");
      }
      delegate.delete(executionId);
    }

    @Override
    public ExecutionExitJournalSnapshot loadAll() {
      if (failLoad) {
        throw new ExecutionExitJournalException("directory unavailable");
      }
      return delegate.loadAll();
    }

    @Override
    public void holdUntilProcessExit() {
      delegate.holdUntilProcessExit();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static InMemoryHarnessStore fixedStore() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.useClock(store, fixedClock());
    return store;
  }

  private static ExecutionExitConfirmer confirmer(
      HarnessStore store, ExecutionExitJournal journal, Clock clock) {
    return new ExecutionExitConfirmer(store, journal, clock);
  }

  private static Clock fixedClock() {
    return Clock.fixed(T0, ZoneOffset.UTC);
  }

  private static ExecutionExitJournalRecord prepared(ExecutionExitFact fact) {
    return ExecutionExitJournalRecord.prepared(
        new LocalExecutionIdentity(
            fact.executionId(),
            fact.threadId(),
            fact.invocationId(),
            fact.attempt(),
            fact.ownerLeaseToken(),
            fact.kind()));
  }

  private static ExecutionExitFact register(
      InMemoryHarnessStore store, UUID threadId, UUID invocationId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId);
          return tx.exits()
              .register(
                  new ExecutionExitFact(
                      tx.nextId(),
                      threadId,
                      invocationId,
                      1,
                      tx.nextId(),
                      ExecutionExitKind.TOOL,
                      null,
                      null,
                      ExecutionExitState.UNCONFIRMED,
                      tx.now(),
                      null,
                      null));
        });
  }

  private static ExecutionExitFact stored(HarnessStore store, UUID executionId) {
    return store.transaction(tx -> tx.exits().find(executionId)).orElseThrow();
  }

  /** 记录“事实读取是否先于 Thread 锁”的 store 包装；不改变其它语义。 */
  private static HarnessStore recordingStore(
      HarnessStore delegate, AtomicBoolean factReadBeforeThreadLock) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("transaction")) {
                return method.invoke(delegate, args);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, Object> callback =
                  (Function<HarnessStore.Transaction, Object>) args[0];
              AtomicBoolean threadLocked = new AtomicBoolean();
              return delegate.transaction(
                  tx ->
                      callback.apply(
                          recordingTransaction(tx, threadLocked, factReadBeforeThreadLock)));
            });
  }

  private static HarnessStore.Transaction recordingTransaction(
      HarnessStore.Transaction delegate,
      AtomicBoolean threadLocked,
      AtomicBoolean factReadBeforeThreadLock) {
    return (HarnessStore.Transaction)
        Proxy.newProxyInstance(
            HarnessStore.Transaction.class.getClassLoader(),
            new Class<?>[] {HarnessStore.Transaction.class},
            (proxy, method, args) -> {
              if (method.getName().equals("threads")) {
                ThreadStore threads = (ThreadStore) method.invoke(delegate, args);
                return Proxy.newProxyInstance(
                    ThreadStore.class.getClassLoader(),
                    new Class<?>[] {ThreadStore.class},
                    (threadProxy, threadMethod, threadArgs) -> {
                      if (threadMethod.getName().equals("lock")) {
                        threadLocked.set(true);
                      }
                      return threadMethod.invoke(threads, threadArgs);
                    });
              }
              if (method.getName().equals("exits")) {
                if (!threadLocked.get()) {
                  factReadBeforeThreadLock.set(true);
                }
                return (ExecutionExitStore) method.invoke(delegate, args);
              }
              return method.invoke(delegate, args);
            });
  }

  /** 可推进的毫秒时钟：用于跨过冻结记录的重试退避。 */
  private static final class MutableClock extends Clock {

    private Instant now = T0;

    private synchronized void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public synchronized Instant instant() {
      return now;
    }
  }
}
