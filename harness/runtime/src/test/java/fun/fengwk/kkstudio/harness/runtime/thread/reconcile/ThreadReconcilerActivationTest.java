package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Process-local activation coalescing belongs to {@link ThreadReconciler}, not a second port. */
class ThreadReconcilerActivationTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void coalescesEachThreadAndRetainsOneRerunEdge() {
    AtomicInteger thread11Claims = new AtomicInteger();
    AtomicInteger thread12Claims = new AtomicInteger();
    TestTransactions transactions =
        new TestTransactions(
            (threadId, ignored) -> {
              if (threadId == 11L) {
                thread11Claims.incrementAndGet();
              } else if (threadId == 12L) {
                thread12Claims.incrementAndGet();
              }
            });
    Queue<Runnable> tasks = new ArrayDeque<>();
    ThreadReconciler reconciler = new ThreadReconciler(transactions, CLOCK, 16, tasks::add);

    reconciler.activate(11L);
    reconciler.activate(11L);
    reconciler.activate(12L);

    assertEquals(2, tasks.size(), "one task per distinct Thread must be scheduled");
    tasks.remove().run();
    tasks.remove().run();
    assertEquals(2, thread11Claims.get());
    assertEquals(1, thread12Claims.get());

    reconciler.activate(11L);
    assertEquals(1, tasks.size(), "completed activation must be removable and schedulable again");
  }

  @Test
  void activationDuringReconcileTriggersExactlyOneAdditionalRun() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    AtomicReference<ThreadReconciler> reconcilerRef = new AtomicReference<>();
    AtomicInteger claims = new AtomicInteger();
    TestTransactions transactions =
        new TestTransactions(
            (threadId, ignored) -> {
              if (claims.getAndIncrement() == 0) {
                reconcilerRef.get().activate(threadId);
                reconcilerRef.get().activate(threadId);
              }
            });
    ThreadReconciler reconciler = new ThreadReconciler(transactions, CLOCK, 16, tasks::add);
    reconcilerRef.set(reconciler);

    reconciler.activate(21L);
    tasks.remove().run();

    assertEquals(2, claims.get());
    assertEquals(0, tasks.size());
  }

  @Test
  void activationFailuresAreIsolatedAndDoNotLeaveInflightState() {
    TestTransactions transactions = new TestTransactions((ignored, token) -> {});
    transactions.failFirstClaim = true;
    ThreadReconciler reconciler = new ThreadReconciler(transactions, CLOCK, 16, Runnable::run);

    assertDoesNotThrow(() -> reconciler.activate(41L));
    assertDoesNotThrow(() -> reconciler.activate(41L));

    assertEquals(2, transactions.claimAttempts.get());
  }

  @Test
  void executorRejectionRemovesInflightStateAndPropagates() {
    AtomicInteger claims = new AtomicInteger();
    TestTransactions transactions =
        new TestTransactions((ignoredThread, ignoredToken) -> claims.incrementAndGet());
    AtomicBoolean reject = new AtomicBoolean(true);
    Executor executor =
        task -> {
          if (reject.getAndSet(false)) {
            throw new RejectedExecutionException("full");
          }
          task.run();
        };
    ThreadReconciler reconciler = new ThreadReconciler(transactions, CLOCK, 16, executor);

    assertThrows(RejectedExecutionException.class, () -> reconciler.activate(51L));
    assertDoesNotThrow(() -> reconciler.activate(51L));
    assertEquals(1, claims.get());
  }

  @Test
  void validatesActivationConstructionAndThreadIdentity() {
    TestTransactions transactions = new TestTransactions((ignored, token) -> {});

    assertThrows(
        NullPointerException.class, () -> new ThreadReconciler(transactions, CLOCK, 16, null));

    ThreadReconciler reconciler = new ThreadReconciler(transactions, CLOCK, 16, Runnable::run);
    assertThrows(IllegalArgumentException.class, () -> reconciler.activate(0L));
  }

  /** Minimal durable adapter: activation exits after the first failed lease renewal. */
  private static final class TestTransactions implements ThreadReconcileTransactions {
    private final BiConsumer<Long, String> onClaim;
    private final AtomicInteger claimAttempts = new AtomicInteger();
    private boolean failFirstClaim;

    private TestTransactions(BiConsumer<Long, String> onClaim) {
      this.onClaim = onClaim;
    }

    @Override
    public Optional<ThreadOwnership> claim(long threadId, String processorToken, Instant now) {
      if (failFirstClaim && claimAttempts.getAndIncrement() == 0) {
        throw new IllegalStateException("database unavailable");
      }
      if (!failFirstClaim) {
        claimAttempts.incrementAndGet();
      }
      onClaim.accept(threadId, processorToken);
      return Optional.of(ReconcileTestSupport.ownership(threadId, 0L, processorToken));
    }

    @Override
    public boolean renew(ThreadOwnership ownership, Instant now) {
      return false;
    }

    @Override
    public Optional<ThreadReconcileSnapshot> loadOwnedSnapshot(
        ThreadOwnership ownership, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ApplyOutcome applyTerminalModel(
        ThreadOwnership ownership, long modelInvocationId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ApplyOutcome applyTerminalToolResults(
        ThreadOwnership ownership, long assistantEntryId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ApplyOutcome applyPlanningFailure(
        ThreadOwnership ownership, PlanningFailure failure, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SuspendOutcome suspendAndRecheck(
        ThreadOwnership ownership, ContinuationRef expectedBlocker, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ApplyOutcome harvestBatch(ThreadOwnership ownership, TurnInputBatch batch, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ModelCreationOutcome createModelInvocationAndRelease(
        ThreadOwnership ownership, ModelInvocationPlan plan, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public QuiesceOutcome quiesceAndRecheck(ThreadOwnership ownership, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void bestEffortRelease(ThreadOwnership ownership, Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
