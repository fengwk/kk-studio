package fun.fengwk.kkstudio.core.harness.model;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionHandle;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionListener;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutionRequest;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-call {@link ModelExecutor} closure that captures a frozen {@link
 * ProviderResolutionService.ResolvedExecution}.
 *
 * <p>Each {@link DatabaseModelExecutionResolver#resolve} call builds exactly one {@code
 * ProviderCallExecutor}; the {@code ModelWorker} then keeps that executor alive for the lifetime of
 * one durable attempt. The transport work is delegated to the injected virtual-thread {@link
 * ExecutorService}, whose lifecycle is owned by Spring composition.
 *
 * <p>Behaviour contracts:
 *
 * <ul>
 *   <li>The transport total timeout is {@code min(configured total, positive remaining durable
 *       deadline)} evaluated at submission time against {@link Clock#instant()}; when the durable
 *       deadline has already elapsed, execution throws {@link ProviderException} synchronously with
 *       kind {@link ProviderErrorKind#TRANSIENT}.
 *   <li>{@link ModelExecutionHandle#cancel()} is idempotent and best-effort: cancel-before-task
 *       prevents the submission from running, cancel-before-stream-bind marks the local handle
 *       cancelled so the bridge cancels the {@link ProviderStream} as soon as it appears, and
 *       cancel-after-bind cancels the live stream immediately. {@link
 *       ModelExecutionHandle#isCancelled()} returns true when either the local handle or the
 *       underlying stream is cancelled.
 *   <li>The SDK {@link ProviderStreamHandler} is bridged onto the Runtime {@link
 *       ModelExecutionListener}: terminal callbacks are delivered at most once, deltas after a
 *       terminal are discarded, and a sync or async SDK failure is normalised into a {@link
 *       ProviderException}. {@link ProviderException} thrown by the SDK is forwarded as-is; any
 *       other {@link RuntimeException} is reported as kind {@link
 *       ProviderErrorKind#INVALID_REQUEST} (setup failure). Double terminal is prevented.
 * </ul>
 */
final class ProviderCallExecutor implements ModelExecutor {

  private static final Duration MIN_TRANSPORT_TIMEOUT = Duration.ofMillis(1L);

  private final ProviderResolutionService.ResolvedExecution resolved;
  private final ExecutorService executor;
  private final Clock clock;

  ProviderCallExecutor(
      ProviderResolutionService.ResolvedExecution resolved, ExecutorService executor, Clock clock) {
    this.resolved = Objects.requireNonNull(resolved, "resolved");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ModelExecutionHandle execute(
      ModelExecutionRequest request, ModelExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    Instant now = clock.instant();
    Instant deadlineAt = request.deadlineAt();
    if (!deadlineAt.isAfter(now)) {
      throw new ProviderException(
          ProviderErrorKind.TRANSIENT, "model execution deadline already expired");
    }
    Duration remaining = Duration.between(now, deadlineAt);
    if (remaining.compareTo(MIN_TRANSPORT_TIMEOUT) < 0) {
      throw new ProviderException(
          ProviderErrorKind.TRANSIENT,
          "model execution deadline has less than one millisecond remaining");
    }
    Duration transportTotal = resolved.timeoutPolicy().modelCallTimeout();
    Duration effectiveTotal = transportTotal.compareTo(remaining) <= 0 ? transportTotal : remaining;
    ModelCallTimeoutPolicy effectiveTimeoutPolicy =
        new ModelCallTimeoutPolicy(effectiveTotal, resolved.timeoutPolicy().modelCallIdleTimeout());

    DelayedHandle handle = new DelayedHandle();
    TransportTask task = new TransportTask(handle, request, listener, effectiveTimeoutPolicy);

    try {
      executor.execute(task);
    } catch (RejectedExecutionException rejected) {
      throw new ProviderException(
          ProviderErrorKind.TRANSIENT, "model executor rejected submission", rejected);
    } catch (RuntimeException submissionFailure) {
      throw new ProviderException(
          ProviderErrorKind.TRANSIENT,
          "cannot submit model execution: " + submissionFailure.getMessage(),
          submissionFailure);
    }
    return handle;
  }

  private static final class DelayedHandle implements ModelExecutionHandle {

    private boolean cancelled;
    private boolean cancellationDelivered;
    private ProviderStream boundStream;

    @Override
    public void cancel() {
      ProviderStream stream;
      synchronized (this) {
        if (cancelled) {
          return;
        }
        cancelled = true;
        stream = cancellationTarget();
      }
      if (stream != null) {
        stream.cancel();
      }
    }

    @Override
    public boolean isCancelled() {
      ProviderStream stream;
      synchronized (this) {
        if (cancelled) {
          return true;
        }
        stream = boundStream;
      }
      return stream != null && stream.isCancelled();
    }

    private void bindStream(ProviderStream stream) {
      Objects.requireNonNull(stream, "stream");
      ProviderStream cancellationTarget;
      synchronized (this) {
        if (boundStream == null) {
          boundStream = stream;
        } else if (boundStream != stream) {
          throw new IllegalArgumentException("provider callback returned inconsistent streams");
        }
        cancellationTarget = cancellationTarget();
      }
      if (cancellationTarget != null) {
        try {
          cancellationTarget.cancel();
        } catch (RuntimeException ignored) {
          // Auto-cancellation happens on the Provider I/O thread. Runtime-triggered cancel failures
          // still propagate to ModelWorker, but a delayed bind cannot alter the durable outcome.
        }
      }
    }

    private ProviderStream cancellationTarget() {
      if (!cancelled || boundStream == null || cancellationDelivered) {
        return null;
      }
      cancellationDelivered = true;
      return boundStream;
    }
  }

  private final class TransportTask implements Runnable {

    private final DelayedHandle handle;
    private final ModelExecutionRequest executionRequest;
    private final ModelExecutionListener listener;
    private final ModelCallTimeoutPolicy effectiveTimeoutPolicy;

    TransportTask(
        DelayedHandle handle,
        ModelExecutionRequest executionRequest,
        ModelExecutionListener listener,
        ModelCallTimeoutPolicy effectiveTimeoutPolicy) {
      this.handle = handle;
      this.executionRequest = executionRequest;
      this.listener = listener;
      this.effectiveTimeoutPolicy = effectiveTimeoutPolicy;
    }

    @Override
    public void run() {
      if (handle.isCancelled()) {
        return;
      }
      BridgingHandler bridge = new BridgingHandler(listener, handle);
      ProviderStream stream;
      try {
        ModelProvider provider = resolved.openProvider(effectiveTimeoutPolicy);
        if (handle.isCancelled()) {
          return;
        }
        stream = provider.stream(executionRequest.request(), bridge);
      } catch (ProviderException providerException) {
        bridge.reportErrorOnce(providerException);
        return;
      } catch (RuntimeException runtimeFailure) {
        bridge.reportErrorOnce(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST,
                runtimeFailure.getMessage() == null
                    ? "cannot start provider stream"
                    : runtimeFailure.getMessage(),
                runtimeFailure));
        return;
      }
      if (stream == null) {
        bridge.reportErrorOnce(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "provider returned a null stream"));
        return;
      }
      try {
        handle.bindStream(stream);
      } catch (RuntimeException failure) {
        bridge.reportErrorOnce(BridgingHandler.classify(failure, "cannot bind provider stream"));
      }
    }
  }

  private static final class BridgingHandler implements ProviderStreamHandler {

    private final ModelExecutionListener listener;
    private final DelayedHandle handle;
    private boolean terminal;

    BridgingHandler(ModelExecutionListener listener, DelayedHandle handle) {
      this.listener = Objects.requireNonNull(listener, "listener");
      this.handle = Objects.requireNonNull(handle, "handle");
    }

    @Override
    public synchronized void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      if (terminal) {
        return;
      }
      if (!bind(stream) || event == null) {
        if (event == null) {
          reportErrorOnce(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "provider returned a null stream event"));
        }
        return;
      }
      try {
        listener.onDelta(event);
      } catch (ProviderException providerException) {
        reportErrorOnce(providerException);
      } catch (RuntimeException failure) {
        reportErrorOnce(classify(failure));
      }
    }

    @Override
    public synchronized void onComplete(ProviderResponse response, ProviderStream stream) {
      if (terminal) {
        return;
      }
      if (!bind(stream) || response == null) {
        if (response == null) {
          reportErrorOnce(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "provider returned a null response"));
        }
        return;
      }
      terminal = true;
      try {
        listener.onComplete(response);
      } catch (RuntimeException ignored) {
        // The Runtime listener owns durable outcome handling. A listener failure after complete
        // cannot be translated into a second terminal callback.
      }
    }

    @Override
    public synchronized void onError(ProviderException error, ProviderStream stream) {
      if (terminal) {
        return;
      }
      if (!bind(stream) || error == null) {
        if (error == null) {
          reportErrorOnce(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "provider returned a null failure"));
        }
        return;
      }
      terminal = true;
      try {
        listener.onError(error);
      } catch (RuntimeException ignored) {
        // Listener errors during error reporting cannot be re-reported without breaking the
        // terminal-once contract.
      }
    }

    private synchronized void reportErrorOnce(ProviderException error) {
      if (terminal) {
        return;
      }
      terminal = true;
      reportErrorDirectly(error);
    }

    private void reportErrorDirectly(ProviderException error) {
      try {
        listener.onError(error);
      } catch (RuntimeException ignored) {
        // already terminal; nothing safe to do.
      }
    }

    private boolean bind(ProviderStream stream) {
      if (stream == null) {
        reportErrorOnce(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "provider callback returned a null stream"));
        return false;
      }
      try {
        handle.bindStream(stream);
        return true;
      } catch (RuntimeException failure) {
        reportErrorOnce(classify(failure, "invalid provider stream handle"));
        return false;
      }
    }

    private static ProviderException classify(RuntimeException failure) {
      return classify(failure, "invalid provider stream event");
    }

    private static ProviderException classify(RuntimeException failure, String fallback) {
      if (failure instanceof ProviderException providerException) {
        return providerException;
      }
      return new ProviderException(
          ProviderErrorKind.INVALID_REQUEST,
          failure.getMessage() == null ? fallback : failure.getMessage(),
          failure);
    }
  }
}
