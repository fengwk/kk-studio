package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedThreadBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.seedTurnBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.api.thread.ExecutionRootFacts;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

class PostgresqlIdleExecutionIntegrationTest {
  @Test
  void branchSharesCutForkCopiesItAndDeletingSourcePreservesSurvivors() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    var source = seedThreadBaseline(store);
    UUID cut =
        runtime
            .stop(new StopCommand(source.threadId(), UUID.randomUUID(), 0))
            .thread()
            .headEntryId();
    assertThrows(
        DomainException.class,
        () ->
            runtime.createIdleBranch(
                source.threadId(), source.rootEntryId(), UUID.randomUUID(), "invalid"));
    var branch = runtime.createIdleBranch(source.threadId(), cut, UUID.randomUUID(), "branch");
    assertEquals(cut, branch.headEntryId());
    assertNull(branch.parentThreadId());
    assertEquals(branch.id(), branch.rootThreadId());
    assertThrows(
        DomainException.class,
        () -> runtime.createIdleBranch(source.threadId(), cut, UUID.randomUUID(), " branch "));
    var fork =
        runtime.createIdleFork(
            source.threadId(),
            cut,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "fork",
            (session, entries) -> assertEquals(3, entries.size()));
    assertEquals("main", fork.name());
    assertNotEquals(cut, fork.headEntryId());
    List<ExecutionRootFacts> facts =
        runtime.getExecutionRootFacts(List.of(source.threadId(), branch.id(), fork.id()));
    assertEquals(3, facts.size());
    assertTrue(
        facts.stream()
            .allMatch(
                f ->
                    !f.hasWork()
                        && !f.blocked()
                        && !f.waitingForHuman()
                        && f.headOutcome() == TurnEndOutcome.STOPPED));
    assertEquals(1, facts.stream().filter(ExecutionRootFacts::stopped).count());
    runtime.deleteThread(
        source.threadId(),
        (session, removed, retained) -> {
          throw new AssertionError("branch protects the shared path");
        });
    assertFalse(runtime.findThreadSessionId(source.threadId()).isPresent());
    assertEquals(cut, runtime.getThreadSnapshot(branch.id(), null).thread().headEntryId());
    runtime.deleteThread(
        branch.id(),
        (session, removed, retained) -> {
          assertEquals(source.sessionId(), session);
          assertEquals(3, removed.size());
          assertEquals(List.of(source.rootEntryId()), retained.stream().map(e -> e.id()).toList());
        });
    assertEquals(
        fork.headEntryId(), runtime.getThreadSnapshot(fork.id(), null).thread().headEntryId());
  }

  @Test
  void openTurnRejectsBothDeletionEntrypointsBeforeCleanup() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    var source = seedTurnBaseline(store);
    assertThrows(
        DomainException.class,
        () ->
            runtime.deleteThread(
                source.threadId(),
                (id, removed, retained) -> {
                  throw new AssertionError();
                }));
    assertThrows(
        DomainException.class,
        () ->
            runtime.deleteSessions(
                List.of(source.sessionId()),
                ids -> {
                  throw new AssertionError();
                },
                ids -> {
                  throw new AssertionError();
                }));
    assertTrue(runtime.findThreadSessionId(source.threadId()).isPresent());
  }

  @Test
  void unconfirmedExitBlocksAnOtherwiseIdleRootUntilConfirmed() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    var root = seedThreadBaseline(store);
    var exit =
        store.transaction(
            tx -> {
              tx.threads().lock(root.threadId()).orElseThrow();
              return tx.exits()
                  .register(
                      new ExecutionExitFact(
                          UUID.randomUUID(),
                          root.threadId(),
                          UUID.randomUUID(),
                          1,
                          UUID.randomUUID(),
                          ExecutionExitKind.DRIVER,
                          null,
                          null,
                          ExecutionExitState.UNCONFIRMED,
                          tx.now(),
                          null,
                          null));
            });
    var facts = runtime.getExecutionRootFacts(List.of(root.threadId())).getFirst();
    assertTrue(facts.blocked());
    assertFalse(facts.hasWork());
    assertThrows(
        DomainException.class,
        () -> runtime.deleteThread(root.threadId(), (id, removed, retained) -> {}));
    store.transaction(
        tx -> {
          tx.threads().lock(root.threadId()).orElseThrow();
          return tx.exits().confirm(exit, ExecutionExitState.STUDIO_EXITED, tx.now());
        });
    assertFalse(runtime.getExecutionRootFacts(List.of(root.threadId())).getFirst().blocked());
  }

  @Test
  void factsDistinguishDueClaimedAndDelayedWorkAcrossRoots() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    HarnessRuntime runtime = new HarnessRuntime(store, new DriverRegistry(), () -> null);
    var busy = seedThreadBaseline(store);
    var idle = seedThreadBaseline(store);
    List<UUID> roots = List.of(busy.threadId(), idle.threadId());
    store.transaction(
        tx -> {
          tx.threads().lock(busy.threadId()).orElseThrow();
          tx.work().request(new WorkTarget(WorkTargetType.THREAD, busy.threadId()));
          return null;
        });
    assertTrue(runtime.getExecutionRootFacts(roots).getFirst().hasWork());
    var claim =
        store.transaction(
            tx ->
                tx.work()
                    .claimNext(WorkTargetType.THREAD, UUID.randomUUID(), Duration.ofMinutes(1))
                    .orElseThrow());
    assertEquals(true, store.transaction(tx -> tx.work().renew(claim, Duration.ofMinutes(2))));
    assertTrue(runtime.getExecutionRootFacts(roots).getFirst().hasWork());
    store.transaction(
        tx -> {
          tx.threads().lock(busy.threadId()).orElseThrow();
          tx.work().reschedule(claim, Duration.ofHours(1));
          return null;
        });
    var facts = runtime.getExecutionRootFacts(roots);
    assertFalse(facts.getFirst().hasWork());
    assertTrue(facts.getFirst().blocked());
    assertFalse(facts.getLast().hasWork());
    assertFalse(facts.getLast().blocked());
  }
}
