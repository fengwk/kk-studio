package fun.fengwk.kkstudio.harness.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.Failure;
import fun.fengwk.kkstudio.harness.runtime.execution.StepResult;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ThreadActivationDispatcherTest {

  @Test
  void coalescesEachThreadAndRetainsOneRerunEdge() {
    AtomicInteger thread11 = new AtomicInteger();
    AtomicInteger thread12 = new AtomicInteger();
    ThreadActivationDispatcher.ReconcileRunner reconcile =
        (threadId, ignored) -> {
          if (threadId == 11L) thread11.incrementAndGet();
          if (threadId == 12L) thread12.incrementAndGet();
          return new StepResult.Quiescent();
        };
    Queue<Runnable> tasks = new ArrayDeque<>();
    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(reconcile, tasks::add, ignored -> {});

    dispatcher.kick(11L);
    dispatcher.kick(11L);
    dispatcher.kick(12L);

    assertEquals(2, tasks.size(), "one task per distinct Thread must be scheduled");
    tasks.remove().run();
    tasks.remove().run();
    assertEquals(2, thread11.get());
    assertEquals(1, thread12.get());

    dispatcher.kick(11L);
    assertEquals(1, tasks.size(), "completed activation must be removable and schedulable again");
  }

  @Test
  void kickDuringActiveReconcileTriggersExactlyOneAdditionalRun() {
    Queue<Runnable> tasks = new ArrayDeque<>();
    AtomicReference<ThreadActivationDispatcher> dispatcherRef = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();
    ThreadActivationDispatcher.ReconcileRunner reconcile =
        (ignoredThread, ignoredToken) -> {
          if (calls.getAndIncrement() == 0) {
            dispatcherRef.get().kick(21L);
            dispatcherRef.get().kick(21L);
          }
          return new StepResult.Quiescent();
        };
    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(reconcile, tasks::add, ignored -> {});
    dispatcherRef.set(dispatcher);

    dispatcher.kick(21L);
    tasks.remove().run();

    assertEquals(2, calls.get());
    assertEquals(0, tasks.size());
  }

  @Test
  void suspendedResultNotifiesBlockerAndNotificationFailureIsIsolated() {
    ContinuationRef continuation =
        new ContinuationRef(
            new ExecutionTarget(ExecutionTargetKind.THREAD, 31L),
            new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, 32L));
    AtomicReference<ExecutionTarget> notified = new AtomicReference<>();
    ActivationNotifier notifier =
        target -> {
          notified.set(target);
          throw new IllegalStateException("redis unavailable");
        };
    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(
            (ignoredThread, ignoredToken) -> new StepResult.Suspended(continuation),
            Runnable::run,
            notifier);

    assertDoesNotThrow(() -> dispatcher.kick(31L));

    assertSame(continuation.blocker(), notified.get());

    AtomicReference<ExecutionTarget> successfulNotification = new AtomicReference<>();
    ThreadActivationDispatcher successful =
        new ThreadActivationDispatcher(
            (ignoredThread, ignoredToken) -> new StepResult.Suspended(continuation),
            Runnable::run,
            successfulNotification::set);
    successful.kick(31L);
    assertSame(continuation.blocker(), successfulNotification.get());
  }

  @Test
  void reconcileFailuresAreIsolatedAndDoNotLeaveInflightState() {
    AtomicInteger calls = new AtomicInteger();
    ThreadActivationDispatcher.ReconcileRunner reconcile =
        (ignoredThread, ignoredToken) ->
            switch (calls.getAndIncrement()) {
              case 0 -> throw new IllegalStateException("database unavailable");
              case 1 -> new StepResult.Failed(new Failure("BROKEN", "broken"));
              default -> new StepResult.Quiescent();
            };
    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(reconcile, Runnable::run, ignored -> {});

    assertDoesNotThrow(() -> dispatcher.kick(41L));
    assertDoesNotThrow(() -> dispatcher.kick(41L));
    assertDoesNotThrow(() -> dispatcher.kick(41L));

    assertEquals(3, calls.get());
  }

  @Test
  void executorRejectionRemovesInflightStateAndPropagates() {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean reject = new AtomicBoolean(true);
    Executor executor =
        task -> {
          if (reject.getAndSet(false)) {
            throw new RejectedExecutionException("full");
          }
          task.run();
        };
    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(
            (ignoredThread, ignoredToken) -> {
              calls.incrementAndGet();
              return new StepResult.Quiescent();
            },
            executor,
            ignored -> {});

    assertThrows(RejectedExecutionException.class, () -> dispatcher.kick(51L));
    assertDoesNotThrow(() -> dispatcher.kick(51L));

    assertEquals(1, calls.get());

    AtomicBoolean failWithRuntime = new AtomicBoolean(true);
    Executor runtimeFailure =
        task -> {
          if (failWithRuntime.getAndSet(false)) {
            throw new IllegalStateException("executor broken");
          }
          task.run();
        };
    ThreadActivationDispatcher retryable =
        new ThreadActivationDispatcher(
            (ignoredThread, ignoredToken) -> new StepResult.Quiescent(),
            runtimeFailure,
            ignored -> {});
    assertThrows(IllegalStateException.class, () -> retryable.kick(52L));
    assertDoesNotThrow(() -> retryable.kick(52L));
  }

  @Test
  void validatesConstructionAndThreadIdentity() {
    ThreadActivationDispatcher.ReconcileRunner reconcile =
        (ignoredThread, ignoredToken) -> new StepResult.Quiescent();
    ActivationNotifier notifier = ignored -> {};

    assertThrows(
        NullPointerException.class,
        () ->
            new ThreadActivationDispatcher(
                (ThreadActivationDispatcher.ReconcileRunner) null, Runnable::run, notifier));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadActivationDispatcher(reconcile, null, notifier));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadActivationDispatcher(reconcile, Runnable::run, null));

    ThreadActivationDispatcher dispatcher =
        new ThreadActivationDispatcher(reconcile, Runnable::run, notifier);
    assertThrows(IllegalArgumentException.class, () -> dispatcher.kick(0L));
  }
}
