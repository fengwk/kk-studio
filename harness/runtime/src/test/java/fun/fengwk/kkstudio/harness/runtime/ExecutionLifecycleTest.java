package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalStage;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionLifecycle;
import fun.fengwk.kkstudio.harness.runtime.execution.MemoryExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionDrain;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionHandle;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 物理生命周期端口的定向行为：PREPARED 先于登记、失权正常返回、精确对账、排空与锁保留。 */
class ExecutionLifecycleTest {

  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  private InMemoryHarnessStore store;
  private HarnessRuntimeTestSupport.Baseline baseline;
  private MemoryExecutionExitJournal journal;
  private ExecutionLifecycle lifecycle;
  private long sequence;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.useClock(store, CLOCK);
    baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    journal = new MemoryExecutionExitJournal();
    lifecycle =
        new ExecutionLifecycle(store, journal, new ExecutionExitConfirmer(store, journal, CLOCK));
    lifecycle.start();
  }

  @Test
  void preparedIsDurableBeforeRegistrationAndProvenExitSettles() {
    LocalExecutionIdentity identity = identity();
    List<UUID> prepared = new ArrayList<>();
    LocalExecutionHandle handle =
        lifecycle.begin(
            identity,
            () -> {
              ExecutionExitJournalRecord record = journal.loadAll().records().get(0);
              assertEquals(ExecutionExitJournalStage.PREPARED, record.stage());
              assertNull(record.targetState());
              prepared.add(record.executionId());
              return register(identity);
            });

    assertTrue(handle.registered());
    assertEquals(identity.executionId(), handle.fact().executionId());
    assertEquals(List.of(identity.executionId()), prepared);

    handle.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertTrue(journal.loadAll().records().isEmpty());
    assertEquals(ExecutionExitState.STUDIO_EXITED, stored(identity).state());
    assertTrue(lifecycle.drain(Duration.ZERO).settled());
  }

  @Test
  void registrationReturningNullIsNormalLostOwnershipAndCleansPreparedAfterResources() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> null);

    assertFalse(handle.registered());
    assertNull(handle.fact());
    // 资源尚未释放：PREPARED 必须还在，供崩溃恢复识别。
    assertEquals(1, journal.loadAll().records().size());

    handle.provenExit(ExecutionExitState.NOT_STARTED);
    assertTrue(journal.loadAll().records().isEmpty());
    assertTrue(store.transaction(tx -> tx.exits().find(identity.executionId())).isEmpty());
  }

  @Test
  void registrationFailureAfterCommitReconcilesUnderThreadLockAndConfirmsNotStarted() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle =
        lifecycle.begin(
            identity,
            () -> {
              register(identity);
              throw new IllegalStateException("commit result unknown");
            });

    assertFalse(handle.registered());
    assertNotNull(handle.fact());
    handle.provenExit(ExecutionExitState.NOT_STARTED);
    assertEquals(ExecutionExitState.NOT_STARTED, stored(identity).state());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void registrationFailureWithoutFactKeepsPreparedUntilNotStarted() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle =
        lifecycle.begin(
            identity,
            () -> {
              throw new IllegalStateException("database unavailable");
            });

    assertFalse(handle.registered());
    assertNull(handle.fact());
    assertEquals(1, journal.loadAll().records().size());

    handle.provenExit(ExecutionExitState.NOT_STARTED);
    assertTrue(journal.loadAll().records().isEmpty());
    assertTrue(store.transaction(tx -> tx.exits().find(identity.executionId())).isEmpty());
  }

  @Test
  void duplicateExecutionIdCannotReplaceTheActivePhysicalHandle() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle first = lifecycle.begin(identity, () -> register(identity));

    assertThrows(
        IllegalStateException.class, () -> lifecycle.begin(identity, () -> register(identity)));
    assertEquals(1, journal.loadAll().records().size());

    first.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertTrue(journal.loadAll().records().isEmpty());
    assertTrue(lifecycle.drain(Duration.ZERO).settled());
  }

  @Test
  void drainUsesMonotonicDeadlineAndCancelsTheUnfinishedBody() throws Exception {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));
    CountDownLatch attached = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    Thread body =
        Thread.ofPlatform()
            .name("physically-running-body")
            .start(
                () -> {
                  if (handle.attachBody(Thread.currentThread())) {
                    attached.countDown();
                    try {
                      Thread.sleep(30_000);
                    } catch (InterruptedException error) {
                      interrupted.countDown();
                    }
                  }
                });
    assertTrue(attached.await(5, TimeUnit.SECONDS));

    long startedAt = System.nanoTime();
    LocalExecutionDrain drain = lifecycle.drain(Duration.ofMillis(50));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

    assertFalse(drain.settled());
    assertEquals(List.of(identity.executionId()), drain.unfinishedExecutionIds());
    assertTrue(drain.retainsJournalLock());
    // 物理等待只能由单调时钟与有界超时决定，不得被固定的业务 Clock 卡住。
    assertTrue(elapsedMillis >= 40, "drain returned before its deadline: " + elapsedMillis);
    assertTrue(elapsedMillis < 10_000, "drain ignored its bounded timeout: " + elapsedMillis);
    assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    assertTrue(journal.holdRequests() >= 1);

    body.join(5_000);
    handle.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertEquals(ExecutionExitState.STUDIO_EXITED, stored(identity).state());
  }

  @Test
  void beginRequiresStartOnceAndDrainStopsAcceptance() {
    ExecutionLifecycle fresh =
        new ExecutionLifecycle(store, journal, new ExecutionExitConfirmer(store, journal, CLOCK));
    LocalExecutionIdentity identity = identity();
    assertThrows(IllegalStateException.class, () -> fresh.begin(identity, () -> null));
    assertTrue(journal.loadAll().records().isEmpty());

    fresh.start();
    assertThrows(IllegalStateException.class, fresh::start);
    LocalExecutionHandle handle = fresh.begin(identity, () -> null);
    handle.provenExit(ExecutionExitState.NOT_STARTED);
    assertTrue(fresh.accepting());

    fresh.drain(Duration.ZERO);
    assertFalse(fresh.accepting());
    assertThrows(IllegalStateException.class, () -> fresh.begin(identity(), () -> null));
  }

  @Test
  void closeWithActivePhysicalHandleRetainsTheJournalLock() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));

    lifecycle.close();
    assertFalse(lifecycle.accepting());
    assertEquals(1, journal.holdRequests());

    handle.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void exitNotProvenKeepsPreparedAndUnconfirmedFactUntilRestartRecovery() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));

    handle.exitNotProven();
    assertEquals(1, journal.loadAll().records().size());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
    assertEquals(1, journal.holdRequests());

    // 结束方法幂等：第一次无法证明后不能被后来的证明覆盖。
    handle.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
    assertFalse(journal.loadAll().records().isEmpty());
  }

  @Test
  void cancelBeforeAttachIsRememberedAndRefusesTheBody() {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));

    handle.requestCancel();

    assertFalse(handle.attachBody(Thread.currentThread()));
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
    assertEquals(1, journal.loadAll().records().size());

    handle.provenExit(ExecutionExitState.NOT_STARTED);
    assertEquals(ExecutionExitState.NOT_STARTED, stored(identity).state());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void bodyAttachIsRefusedWhenNotRegisteredOrAlreadySettled() {
    LocalExecutionHandle absent = lifecycle.begin(identity(), () -> null);
    assertFalse(absent.attachBody(Thread.currentThread()));
    absent.provenExit(ExecutionExitState.NOT_STARTED);

    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle settled = lifecycle.begin(identity, () -> register(identity));
    assertTrue(settled.attachBody(Thread.currentThread()));
    settled.detachBody();
    settled.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertFalse(settled.attachBody(Thread.currentThread()));
  }

  @Test
  void provenExitRefusesWhileTheAttachedBodyIsStillAlive() throws Exception {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread body =
        Thread.ofPlatform()
            .name("still-running-body")
            .start(
                () -> {
                  assertTrue(handle.attachBody(Thread.currentThread()));
                  running.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(running.await(5, TimeUnit.SECONDS));

    assertThrows(
        IllegalStateException.class, () -> handle.provenExit(ExecutionExitState.STUDIO_EXITED));
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
    assertEquals(1, journal.loadAll().records().size());

    handle.detachBody();
    handle.provenExit(ExecutionExitState.STUDIO_EXITED);
    assertEquals(ExecutionExitState.STUDIO_EXITED, stored(identity).state());
    release.countDown();
    body.join(5_000);
  }

  @Test
  void exitNotProvenKeepsALiveBodyInThePhysicalSetUntilItEnds() throws Exception {
    LocalExecutionIdentity identity = identity();
    LocalExecutionHandle handle = lifecycle.begin(identity, () -> register(identity));
    CountDownLatch running = new CountDownLatch(1);
    Thread body =
        Thread.ofPlatform()
            .name("unproven-live-body")
            .start(
                () -> {
                  assertTrue(handle.attachBody(Thread.currentThread()));
                  running.countDown();
                  try {
                    Thread.sleep(30_000);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                });
    assertTrue(running.await(5, TimeUnit.SECONDS));

    handle.exitNotProven();
    assertEquals(1, journal.loadAll().records().size());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
    assertTrue(journal.holdRequests() >= 1);

    // body 仍存活：物理句柄不能被移除，排空必须报告未结束。
    LocalExecutionDrain liveDrain = lifecycle.drain(Duration.ZERO);
    assertEquals(List.of(identity.executionId()), liveDrain.unfinishedExecutionIds());
    assertTrue(liveDrain.retainsJournalLock());

    body.join(5_000);
    handle.exitNotProven();
    LocalExecutionDrain endedDrain = lifecycle.drain(Duration.ZERO);
    assertTrue(endedDrain.unfinishedExecutionIds().isEmpty());
    assertTrue(endedDrain.retainsJournalLock());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored(identity).state());
  }

  @Test
  void registrationIdentityMismatchKeepsThePhysicalHandleAndJournalLock() {
    LocalExecutionIdentity identity = identity();

    IllegalStateException mismatch =
        assertThrows(
            IllegalStateException.class,
            () ->
                lifecycle.begin(
                    identity, () -> registerWithInvocation(identity, TestIds.id(4000))));

    assertTrue(mismatch.getMessage().contains("does not match"));
    assertEquals(1, journal.loadAll().records().size());
    assertTrue(journal.holdRequests() >= 1);
    LocalExecutionDrain drain = lifecycle.drain(Duration.ZERO);
    assertEquals(List.of(identity.executionId()), drain.unfinishedExecutionIds());
    assertTrue(drain.retainsJournalLock());
  }

  private LocalExecutionIdentity identity() {
    sequence++;
    return new LocalExecutionIdentity(
        TestIds.id(1000 + sequence),
        baseline.threadId(),
        TestIds.id(2000 + sequence),
        1,
        TestIds.id(3000 + sequence),
        ExecutionExitKind.TOOL);
  }

  private ExecutionExitFact register(LocalExecutionIdentity identity) {
    return registerWithInvocation(identity, identity.invocationId());
  }

  private ExecutionExitFact registerWithInvocation(
      LocalExecutionIdentity identity, UUID invocationId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(identity.threadId());
          return tx.exits()
              .register(
                  new ExecutionExitFact(
                      identity.executionId(),
                      identity.threadId(),
                      invocationId,
                      identity.attempt(),
                      identity.ownerLeaseToken(),
                      identity.kind(),
                      null,
                      null,
                      ExecutionExitState.UNCONFIRMED,
                      Instant.ofEpochMilli(T0.toEpochMilli()),
                      null,
                      null));
        });
  }

  private ExecutionExitFact stored(LocalExecutionIdentity identity) {
    return store.transaction(tx -> tx.exits().find(identity.executionId())).orElseThrow();
  }
}
