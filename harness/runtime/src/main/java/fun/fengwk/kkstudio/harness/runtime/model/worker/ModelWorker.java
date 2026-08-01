package fun.fengwk.kkstudio.harness.runtime.model.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Callback-driven durable ModelInvocation worker.
 *
 * <p>The worker is the small orchestration entry point of Model execution. It claims durable work,
 * resolves the short-lived execution resource, hands provider I/O to {@link
 * ModelExecutionCallback}, and owns only the process-local execution map. Callback lifecycle,
 * streaming accumulation, terminal convergence, and watchdogs remain package-local cohesive
 * collaborators rather than a shared Model/Tool abstraction.
 *
 * <p>All durable mutations are fenced through {@link ModelInvocationTransactions}. A failed or late
 * CAS only tears down the process-local handle. The worker never writes Entry/head/Usage directly.
 * {@code workerTokenSupplier} must return a fresh non-blank token for every claim.
 *
 * <p>调度完全由 {@code harness_execution_target} 统一表达：transaction adapter 在同一事务内原子写入 Invocation
 * 与目标行（claim reschedule / renew reschedule / scheduleRetry reschedule / terminal delete+schedule
 * Thread）。本 worker 因此没有 outbound activation dependency，也不本地调度 retry
 * wake；lease/deadline/idle/activity-flush watchdog 仍是 process-local best-effort。
 */
@Slf4j
public final class ModelWorker {

  private final ModelInvocationTransactions transactions;
  private final ModelExecutionResolver executionResolver;
  private final RealtimeEventSink realtimeEventSink;
  private final ModelWorkerConfig config;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final Supplier<String> workerTokenSupplier;
  private final ModelTerminalCompleter terminalCompleter;
  private final ConcurrentHashMap<Long, ModelExecutionCallback> executions =
      new ConcurrentHashMap<>();

  public ModelWorker(
      ModelInvocationTransactions transactions,
      ModelExecutionResolver executionResolver,
      InvocationRetryPolicyResolver retryPolicyResolver,
      RealtimeEventSink realtimeEventSink,
      ModelWorkerConfig config,
      Clock clock,
      ScheduledExecutorService scheduler,
      Supplier<String> workerTokenSupplier) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.executionResolver = Objects.requireNonNull(executionResolver, "executionResolver");
    this.realtimeEventSink = Objects.requireNonNull(realtimeEventSink, "realtimeEventSink");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.workerTokenSupplier = Objects.requireNonNull(workerTokenSupplier, "workerTokenSupplier");
    this.terminalCompleter =
        new ModelTerminalCompleter(
            transactions,
            Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver"),
            clock);
  }

  /**
   * Processes one direct dispatch for a specified durable Invocation.
   *
   * @return {@code true} only when this process successfully claimed an Invocation and either
   *     dispatched it or made a conservative terminal transition; {@code false} when no current
   *     claimable work exists
   */
  public boolean dispatch(long invocationId) {
    return claim(invocationId, false);
  }

  /**
   * Claims one durable ModelInvocation on the caller thread and starts Provider I/O on this
   * worker's scheduler.
   *
   * <p>Execution-target dispatchers use this method so their durable claim is complete before the
   * target scan proceeds, while no Provider I/O runs on the dispatcher thread.
   *
   * @return {@code true} only when this process successfully claimed an Invocation and either
   *     queued its local execution or durably converged a pre-I/O failure; {@code false} when no
   *     current claimable work exists
   */
  public boolean activate(long invocationId) {
    return claim(invocationId, true);
  }

  private boolean claim(long invocationId, boolean deferExternalStart) {
    if (invocationId <= 0) {
      throw new IllegalArgumentException("invocationId must be positive");
    }
    return transactions
        .findClaimable(invocationId, clock.instant())
        .map(candidate -> claimAndDispatch(candidate, deferExternalStart))
        .orElse(false);
  }

  /** Returns whether this JVM currently owns a local external Model execution handle. */
  public boolean hasActiveExecution() {
    return !executions.isEmpty();
  }

  /**
   * Cancels only process-local handles during shutdown. Durable rows remain RUNNING until their
   * lease recovery path takes ownership.
   */
  public void stop() {
    List.copyOf(executions.values()).forEach(ModelExecutionCallback::abandon);
  }

  private boolean claimAndDispatch(ModelInvocation candidate, boolean deferExternalStart) {
    String workerToken = nextWorkerToken();
    ModelExecutionResource resource = null;
    RuntimeException resolutionFailure = null;
    if (candidate.status() != InvocationStatus.RUNNING) {
      try {
        resource = executionResolver.resolve(candidate.request().providerRequest());
      } catch (RuntimeException error) {
        resolutionFailure = error;
      }
    }
    ModelCallTimeoutPolicy timeoutPolicy =
        resource == null ? ModelCallTimeoutPolicy.DEFAULT : resource.timeoutPolicy();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            candidate.id(),
            workerToken,
            timeoutPolicy,
            config.workerLeaseDuration(),
            clock.instant());
    if (claimed.isEmpty()) {
      return false;
    }
    ClaimedModelInvocation ownership = claimed.orElseThrow();
    requireClaimToken(ownership, workerToken);

    if (ownership.recoveredLease()) {
      markRecoveredLeaseUnknown(ownership);
      return true;
    }
    if (resolutionFailure != null) {
      failExecutionSetup(ownership, resolutionFailure);
      return true;
    }
    if (resource == null) {
      failExecutionSetup(
          ownership, new IllegalStateException("model execution resource is unavailable"));
      return true;
    }
    dispatchClaimed(ownership, resource, deferExternalStart);
    return true;
  }

  private String nextWorkerToken() {
    String token = workerTokenSupplier.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("workerTokenSupplier returned a blank token");
    }
    return token;
  }

  private static void requireClaimToken(ClaimedModelInvocation claimed, String expectedToken) {
    if (!claimed.invocation().workerLease().token().equals(expectedToken)) {
      throw new IllegalStateException(
          "claim returned a lease token different from the requested token");
    }
  }

  private void markRecoveredLeaseUnknown(ClaimedModelInvocation claimed) {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT,
            "model worker lease expired; provider outcome cannot be confirmed");
    try {
      transactions.completeUnknown(
          claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
    } catch (RuntimeException failure) {
      log.warn(
          "cannot persist UNKNOWN for recovered model invocation {}",
          claimed.invocation().id(),
          failure);
    }
  }

  private void failExecutionSetup(ClaimedModelInvocation claimed, RuntimeException failure) {
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.INVALID_REQUEST,
            "cannot resolve model execution resource: "
                + message(failure, "unknown setup failure"));
    try {
      transactions.completeFailure(
          claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
    } catch (RuntimeException terminalFailure) {
      log.warn(
          "cannot persist model execution setup failure for {}",
          claimed.invocation().id(),
          terminalFailure);
    }
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }

  private void dispatchClaimed(
      ClaimedModelInvocation claimed, ModelExecutionResource resource, boolean deferExternalStart) {
    long invocationId = claimed.invocation().id();
    ModelExecutionCallback execution =
        new ModelExecutionCallback(
            claimed,
            resource,
            transactions,
            terminalCompleter,
            realtimeEventSink,
            config,
            clock,
            scheduler,
            callback -> executions.remove(invocationId, callback));
    ModelExecutionCallback existing = executions.putIfAbsent(invocationId, execution);
    if (existing != null) {
      existing.abandon();
      ModelInvocationError error =
          new ModelInvocationError(
              ProviderErrorKind.TRANSIENT,
              "a conflicting local model execution retained this invocation handle");
      try {
        transactions.completeUnknown(
            claimed, error, claimed.invocation().lastActivityAt(), clock.instant());
      } catch (RuntimeException failure) {
        log.warn("cannot persist conflicting local model execution", failure);
      }
      return;
    }
    if (!deferExternalStart) {
      execution.start();
      return;
    }
    try {
      scheduler.execute(execution::start);
    } catch (RejectedExecutionException rejection) {
      execution.onError(
          new ProviderException(
              ProviderErrorKind.TRANSIENT,
              "cannot submit model execution to worker scheduler",
              rejection));
    }
  }
}
