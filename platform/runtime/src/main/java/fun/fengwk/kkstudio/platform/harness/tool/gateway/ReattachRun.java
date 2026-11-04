package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.api.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一次已挂接的远端 Capability 执行：只等待既有远端终态，不重新准入、不产生本地 effects。
 *
 * <p>挂接时可达的 partial 在 {@link #execute} 开始前缓冲，开始时按到达顺序转发；进度消费者就绪之前的 partial 不会丢失。执行线程中断以 {@code
 * CANCELLED} 终止远端在途执行，等待超时以 {@code TIMED_OUT} 终止并收敛为未知结果。远端退出仍等待独立 ACK，普通终态不替代它。
 */
final class ReattachRun extends AbstractToolRun {

  private final EnvironmentCapabilityExecutionHandle transportHandle;
  private final CompletableFuture<EnvironmentCapabilityResult> terminalOutcome;
  private final UUID remoteExecutionId;
  private final Duration timeout;
  private final AtomicReference<Consumer<ToolResult>> progressRef;
  private final ConcurrentLinkedQueue<EnvironmentCapabilityResult> bufferedPartials;

  ReattachRun(
      EnvironmentCapabilityExecutionHandle transportHandle,
      CompletableFuture<EnvironmentCapabilityResult> terminalOutcome,
      ToolResultFinalizer finalizer,
      String toolName,
      String modelCallId,
      UUID remoteExecutionId,
      Duration timeout,
      AtomicReference<Consumer<ToolResult>> progressRef,
      ConcurrentLinkedQueue<EnvironmentCapabilityResult> bufferedPartials) {
    super(finalizer, toolName, modelCallId);
    this.transportHandle = Objects.requireNonNull(transportHandle, "transportHandle");
    this.terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
    this.remoteExecutionId = Objects.requireNonNull(remoteExecutionId, "remoteExecutionId");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.progressRef = Objects.requireNonNull(progressRef, "progressRef");
    this.bufferedPartials = Objects.requireNonNull(bufferedPartials, "bufferedPartials");
  }

  @Override
  protected ToolGateway.Completion invoke(Consumer<ToolResult> guarded) {
    progressRef.set(guarded);
    try {
      EnvironmentCapabilityResult buffered;
      while ((buffered = bufferedPartials.poll()) != null) {
        guarded.accept(
            RemoteToolExecution.toToolResult(buffered, remoteExecutionId, expectedCallId()));
      }
      return awaitTerminal();
    } finally {
      progressRef.set(null);
    }
  }

  @Override
  protected void release() {
    RemoteToolExecution.terminate(
        transportHandle, EnvironmentCapabilityTerminationCause.CANCELLED, null);
  }

  private ToolGateway.Completion awaitTerminal() {
    try {
      EnvironmentCapabilityResult result;
      long timeoutNanos = timeout.toNanos();
      try {
        result = terminalOutcome.get(timeoutNanos, TimeUnit.NANOSECONDS);
      } catch (TimeoutException timedOut) {
        EnvironmentCapabilitySendUncertainException timeoutException =
            new EnvironmentCapabilitySendUncertainException(
                "remote capability timed out waiting for reattach terminal outcome after "
                    + timeout,
                timedOut);
        if (terminalOutcome.completeExceptionally(timeoutException)) {
          RemoteToolExecution.terminate(
              transportHandle, EnvironmentCapabilityTerminationCause.TIMED_OUT, timeoutException);
          throw timeoutException;
        }
        result = terminalOutcome.get();
      }
      return completion(
          RemoteToolExecution.toToolResult(result, remoteExecutionId, expectedCallId()),
          () -> ToolEffectBatch.EMPTY);
    } catch (InterruptedException interrupted) {
      RemoteToolExecution.releaseInFlight(
          terminalOutcome,
          transportHandle,
          EnvironmentCapabilityTerminationCause.CANCELLED,
          interrupted);
      return ToolErrorClassifier.cancelled();
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException runtime) {
        return ToolErrorClassifier.classify(toolName(), runtime);
      }
      return ToolErrorClassifier.classify(
          toolName(),
          new IllegalStateException(
              "remote capability failed with a non-runtime error; outcome cannot be confirmed",
              cause));
    } catch (RuntimeException error) {
      return ToolErrorClassifier.classify(toolName(), error);
    }
  }
}
