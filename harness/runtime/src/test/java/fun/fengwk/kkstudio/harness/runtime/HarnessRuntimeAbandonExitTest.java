package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** 显式放弃：STOPPED-only、版本 CAS、跨 Thread 拒绝、滞后重放幂等与真实证据升级。 */
class HarnessRuntimeAbandonExitTest {

  @Test
  void abandonmentRequiresStoppedOwnershipAndCurrentVersion() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline first = seedBaseline(store);
    HarnessRuntimeTestSupport.Baseline other = seedBaseline(store);
    long runningVersion = runtime.getThreadSnapshot(first.threadId()).thread().version();
    ExecutionExitFact runningFact = register(store, first.threadId(), TestIds.id(1), T0);

    HarnessRuntimeConflictException notStopped =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.abandonExecution(
                    first.threadId(), runningFact.executionId(), runningVersion));
    assertEquals(
        HarnessRuntimeConflictException.Reason.EXIT_ABANDON_NOT_APPLICABLE, notStopped.reason());

    long stoppedVersion =
        runtime
            .stop(new StopCommand(first.threadId(), TestIds.id(2), runningVersion))
            .thread()
            .version();
    UUID foreignExecutionId = register(store, other.threadId(), TestIds.id(3), T0).executionId();
    HarnessRuntimeConflictException foreign =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> runtime.abandonExecution(first.threadId(), foreignExecutionId, stoppedVersion));
    assertEquals(
        HarnessRuntimeConflictException.Reason.EXIT_ABANDON_NOT_APPLICABLE, foreign.reason());

    HarnessRuntimeConflictException stale =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.abandonExecution(
                    first.threadId(), runningFact.executionId(), stoppedVersion - 1));
    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, stale.reason());
    assertEquals(
        ExecutionExitState.UNCONFIRMED,
        store.transaction(tx -> tx.exits().find(runningFact.executionId()).orElseThrow().state()));
  }

  @Test
  void abandonmentReplayToleratesStaleVersionAndRealEvidenceUpgradesIt() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    long version = runtime.getThreadSnapshot(baseline.threadId()).thread().version();
    long stoppedVersion =
        runtime
            .stop(new StopCommand(baseline.threadId(), TestIds.id(1), version))
            .thread()
            .version();
    ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(2), T0);

    ThreadSnapshot abandoned =
        runtime.abandonExecution(baseline.threadId(), fact.executionId(), stoppedVersion);
    assertEquals(stoppedVersion + 1, abandoned.thread().version());
    assertTrue(abandoned.unconfirmedExecutions().isEmpty());
    ExecutionExitFact abandonedFact =
        store.transaction(tx -> tx.exits().find(fact.executionId()).orElseThrow());
    assertEquals(ExecutionExitState.ABANDONED, abandonedFact.state());
    assertEquals(T0, abandonedFact.abandonedAt());
    assertNull(abandonedFact.confirmedAt());

    ThreadSnapshot replayed = runtime.abandonExecution(baseline.threadId(), fact.executionId(), 0L);
    assertEquals(abandoned.thread().version(), replayed.thread().version());
    assertTrue(replayed.unconfirmedExecutions().isEmpty());

    Instant confirmedAt = T0.plusMillis(1);
    ExecutionExitFact confirmed =
        store.transaction(
            tx -> {
              tx.threads().lock(baseline.threadId());
              return tx.exits()
                  .confirm(fact, ExecutionExitState.STUDIO_EXITED, confirmedAt)
                  .orElseThrow();
            });
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.abandonedAt());
    assertEquals(confirmedAt, confirmed.confirmedAt());

    ThreadSnapshot upgradedReplay =
        runtime.abandonExecution(baseline.threadId(), fact.executionId(), 0L);
    assertEquals(abandoned.thread().version(), upgradedReplay.thread().version());
    assertTrue(upgradedReplay.unconfirmedExecutions().isEmpty());
  }

  @Test
  void abandonmentRejectsUnknownThreadExecutionAndNegativeVersion() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntime runtime =
        HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);

    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.abandonExecution(UUID.randomUUID(), UUID.randomUUID(), 0L));
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> runtime.abandonExecution(baseline.threadId(), UUID.randomUUID(), 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.abandonExecution(baseline.threadId(), UUID.randomUUID(), -1L));
  }

  private static ExecutionExitFact register(
      InMemoryHarnessStore store, UUID threadId, UUID invocationId, Instant registeredAt) {
    ExecutionExitFact fact =
        new ExecutionExitFact(
            UUID.randomUUID(),
            threadId,
            invocationId,
            1,
            "lease-1",
            ExecutionExitKind.TOOL,
            null,
            ExecutionExitState.UNCONFIRMED,
            registeredAt,
            null,
            null);
    store.transaction(
        tx -> {
          tx.threads().lock(threadId);
          return tx.exits().register(fact);
        });
    return fact;
  }
}
