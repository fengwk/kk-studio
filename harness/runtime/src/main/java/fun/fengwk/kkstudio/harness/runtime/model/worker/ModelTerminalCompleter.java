package fun.fengwk.kkstudio.harness.runtime.model.worker;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyResolver;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Owns fenced Model terminal transitions and the retry-or-final-failure decision. */
@Slf4j
final class ModelTerminalCompleter {
  private final ModelInvocationTransactions transactions;
  private final InvocationRetryPolicyResolver retryPolicyResolver;
  private final Clock clock;

  ModelTerminalCompleter(
      ModelInvocationTransactions transactions,
      InvocationRetryPolicyResolver retryPolicyResolver,
      Clock clock) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Applies a successful terminal fence after the callback has durably recorded its final safe
   * stream snapshot.
   *
   * @return whether this callback still owned the durable row and committed success
   */
  boolean completeSuccess(
      ClaimedModelInvocation claimed, ProviderResponse response, Instant lastObservedActivityAt) {
    try {
      return transactions.completeSuccess(
              claimed, response, lastObservedActivityAt, clock.instant())
          == ModelInvocationUpdateOutcome.APPLIED;
    } catch (RuntimeException failure) {
      log.warn("cannot persist model success for {}", claimed.invocation().id(), failure);
      return false;
    }
  }

  /** Classifies a Provider failure as retry, cancellation, or terminal failure. */
  void completeFailureOrRetry(
      ClaimedModelInvocation claimed, ProviderException error, Instant lastObservedActivityAt) {
    if (error.kind() == ProviderErrorKind.TRANSIENT) {
      scheduleRetryOrFail(claimed, error, lastObservedActivityAt);
      return;
    }
    if (error.kind() == ProviderErrorKind.CANCELLED) {
      completeCancelled(claimed, lastObservedActivityAt);
      return;
    }
    completeFinalFailure(claimed, error, lastObservedActivityAt);
  }

  /** Applies a fenced cancellation terminal transition. */
  void completeCancelled(ClaimedModelInvocation claimed, Instant lastObservedActivityAt) {
    try {
      transactions.completeCancelled(claimed, lastObservedActivityAt, clock.instant());
    } catch (RuntimeException failure) {
      log.warn("cannot persist model cancellation for {}", claimed.invocation().id(), failure);
    }
  }

  private void scheduleRetryOrFail(
      ClaimedModelInvocation claimed, ProviderException error, Instant lastObservedActivityAt) {
    InvocationRetryPolicy policy;
    try {
      policy = Objects.requireNonNull(retryPolicyResolver.resolve(), "retry policy");
    } catch (RuntimeException failure) {
      completeFinalFailure(
          claimed,
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST,
              "cannot resolve invocation retry policy: "
                  + message(failure, "unknown policy failure"),
              failure),
          lastObservedActivityAt);
      return;
    }
    int retryOrdinal = claimed.invocation().attempt();
    Instant now = clock.instant();
    if (policy.allowsRetry(retryOrdinal)) {
      Instant retryAt = now.plus(policy.delayBeforeRetry(retryOrdinal));
      if (retryAt.isBefore(claimed.invocation().deadlineAt())) {
        try {
          transactions.scheduleRetry(claimed, retryAt, lastObservedActivityAt, now);
        } catch (RuntimeException failure) {
          log.warn("cannot persist model retry for {}", claimed.invocation().id(), failure);
        }
        return;
      }
    }
    completeFinalFailure(claimed, error, lastObservedActivityAt);
  }

  private void completeFinalFailure(
      ClaimedModelInvocation claimed, ProviderException error, Instant lastObservedActivityAt) {
    ModelInvocationError snapshot =
        new ModelInvocationError(error.kind(), message(error, "model provider failed"));
    try {
      transactions.completeFailure(claimed, snapshot, lastObservedActivityAt, clock.instant());
    } catch (RuntimeException failure) {
      log.warn("cannot persist model failure for {}", claimed.invocation().id(), failure);
    }
  }

  private static String message(Throwable failure, String fallback) {
    if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
      return fallback;
    }
    return failure.getMessage();
  }
}
