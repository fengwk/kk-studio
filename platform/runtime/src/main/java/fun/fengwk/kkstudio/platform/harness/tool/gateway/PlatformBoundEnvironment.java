package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Platform 的 {@link BoundEnvironment} 适配实现：把异步 {@link EnvironmentCapabilityTransport} 收敛为阻塞调用。
 *
 * <p>每次执行生成独立 execution id，发送前经 {@link ToolGateway.RemoteExecutionObserver#tryStart} 持久登记；登记失败绝不发送。
 */
final class PlatformBoundEnvironment implements BoundEnvironment {

  private final EnvironmentId environmentId;
  private final EnvironmentCapabilityTransport capabilityTransport;
  private final String modelCallId;
  private final ExpectedFile expectedFile;
  private final ToolGateway.RemoteExecutionObserver remoteExecutions;

  PlatformBoundEnvironment(
      EnvironmentId environmentId,
      EnvironmentCapabilityTransport capabilityTransport,
      String modelCallId,
      ExpectedFile expectedFile,
      ToolGateway.RemoteExecutionObserver remoteExecutions) {
    this.environmentId = Objects.requireNonNull(environmentId, "environmentId");
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.modelCallId = Objects.requireNonNull(modelCallId, "modelCallId");
    this.expectedFile = expectedFile;
    this.remoteExecutions = Objects.requireNonNull(remoteExecutions, "remoteExecutions");
  }

  @Override
  public EnvironmentId environmentId() {
    return environmentId;
  }

  /**
   * 发送并阻塞等待单一绝对 deadline。{@code request.timeout} 是远端执行预算，{@code terminalDeliveryGrace}
   * 是终态传输恢复窗；两者只合成一次从发送前起算的等待上界，partial 与重连都不延长它。{@code timeout} 为 0 表示显式无界，不会因恢复窗变成有界等待。
   *
   * <p>发送前异常（Busy / Unavailable / SendUncertain）原样向上抛出，由 Gateway 按类型分类。拿到句柄后，任何未能以真实 terminal
   * 收敛的异常退出都会先补一个异常终态并调用 {@code terminate(CANCELLED)}，把远端在途执行与 transfer 释放掉；清理自身的失败只作为 suppressed
   * 附加到原异常，绝不覆盖或吞掉原始失败。等待超时以 {@code TIMED_OUT} 收尾并抛出 {@link
   * EnvironmentCapabilitySendUncertainException}，调用线程中断则以 {@code CANCELLED} 收尾并原样抛出 {@link
   * InterruptedException}。
   */
  @Override
  public ToolResult execute(
      EnvironmentCapabilityDescriptor capability,
      ToolExecutionRequest request,
      Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progress, "progress");
    // 每次实际远端调用拥有独立身份；参数构造校验必须先于登记，登记成功才允许发送。
    UUID remoteExecutionId = UUID.randomUUID();
    String remoteCallId = remoteExecutionId.toString();
    EnvironmentCapabilityCall capabilityCall =
        new EnvironmentCapabilityCall(remoteCallId, request.call().argumentsJson());
    EnvironmentCapabilityExecutionRequest capabilityRequest =
        new EnvironmentCapabilityExecutionRequest(
            capability,
            capabilityCall,
            request.timeout(),
            expectedFile,
            request.resourceDownload());
    CompletableFuture<EnvironmentCapabilityResult> terminal = new CompletableFuture<>();
    EnvironmentCapabilityExecutionListener listener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            if (!terminal.isDone()) {
              progress.accept(
                  RemoteToolExecution.toToolResult(partial, remoteExecutionId, modelCallId));
            }
          }

          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            terminal.complete(result);
          }

          @Override
          public void onError(Throwable error) {
            terminal.completeExceptionally(error);
          }

          @Override
          public void onExitConfirmed() {
            // 退出 ACK 独立于结果 future：即使结果已异常完成，迟到 ACK 仍必须确认本次远端执行。
            remoteExecutions.confirmExit(remoteExecutionId);
          }
        };
    if (!remoteExecutions.tryStart(remoteExecutionId, environmentId)) {
      throw new EnvironmentCapabilityCancelledException(
          "remote execution was not started because the tool generation is stopped or no"
              + " longer owned");
    }
    long startNanos = System.nanoTime();
    EnvironmentCapabilityExecutionHandle transportHandle;
    try {
      transportHandle = capabilityTransport.invoke(environmentId, capabilityRequest, listener);
    } catch (EnvironmentCapabilityBusyException
        | EnvironmentCapabilityUnavailableException notExecuted) {
      // 只有传输端口明确保证的发送前 Busy / Unavailable 才能确认未启动；其它异常不构成未发送证据。
      try {
        remoteExecutions.confirmNotStarted(remoteExecutionId);
      } catch (RuntimeException | Error confirmationFailure) {
        confirmationFailure.addSuppressed(notExecuted);
        if (confirmationFailure instanceof RuntimeException) {
          // Unknown 分类会记录异常 message；只提供安全诊断，原始持久化失败仍作为 cause 保留。
          throw new IllegalStateException(
              "phase=REMOTE_NOT_STARTED_CONFIRM; exception="
                  + confirmationFailure.getClass().getSimpleName(),
              confirmationFailure);
        }
        throw confirmationFailure;
      }
      throw notExecuted;
    }
    if (transportHandle == null) {
      throw new IllegalStateException(
          "remote capability returned a null execution handle; outcome cannot be confirmed");
    }
    try {
      Duration timeout = request.timeout();
      Duration grace = transportHandle.terminalDeliveryGrace();
      if (grace == null) {
        throw new IllegalStateException(
            "remote capability returned a null terminal delivery grace;"
                + " outcome cannot be confirmed");
      }
      if (grace.isNegative()) {
        throw new IllegalStateException(
            "remote capability returned a negative terminal delivery grace: "
                + grace
                + "; outcome cannot be confirmed");
      }
      EnvironmentCapabilityResult result;
      if (timeout.isZero()) {
        // 0 是显式无界执行预算：grace 只描述恢复窗，不能把它缩短成有界等待。
        result = terminal.get();
      } else {
        Duration waitBudget = timeout.plus(grace);
        long remainingNanos = Math.max(0L, waitBudget.toNanos() - (System.nanoTime() - startNanos));
        try {
          result = terminal.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
          EnvironmentCapabilitySendUncertainException timeoutException =
              new EnvironmentCapabilitySendUncertainException(
                  "remote capability timed out waiting for terminal outcome after " + waitBudget,
                  timedOut);
          // 只有仍无终态时才由 runtime 抢占；若终态已在竞态中完成，优先交付真实结果或真实失败。
          if (terminal.completeExceptionally(timeoutException)) {
            RemoteToolExecution.terminate(
                transportHandle, EnvironmentCapabilityTerminationCause.TIMED_OUT, timeoutException);
            throw timeoutException;
          }
          result = terminal.get();
        }
      }
      if (result == null) {
        throw new IllegalStateException(
            "remote capability completed without a result; outcome cannot be confirmed");
      }
      return RemoteToolExecution.toToolResult(result, remoteExecutionId, modelCallId);
    } catch (InterruptedException interrupted) {
      RemoteToolExecution.releaseInFlight(
          terminal, transportHandle, EnvironmentCapabilityTerminationCause.CANCELLED, interrupted);
      throw interrupted;
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(
          "remote capability failed with a non-runtime error; outcome cannot be confirmed",
          failed.getCause());
    } catch (RuntimeException | Error failure) {
      RemoteToolExecution.releaseInFlight(
          terminal, transportHandle, EnvironmentCapabilityTerminationCause.CANCELLED, failure);
      throw failure;
    }
  }
}
