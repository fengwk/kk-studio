package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 远端 Capability 执行：装配阻塞执行绑定，并挂接既有在途执行。
 *
 * <p>挂接只把本地执行体接到该次远端执行上：不重新准入、不需要执行上下文事实，也不读取分支视图、会话或媒体能力。
 */
final class RemoteToolExecution {

  private final EnvironmentCapabilityTransport capabilityTransport;
  private final ToolResultFinalizer finalizer;

  RemoteToolExecution(
      EnvironmentCapabilityTransport capabilityTransport, ToolResultFinalizer finalizer) {
    this.capabilityTransport = Objects.requireNonNull(capabilityTransport, "capabilityTransport");
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
  }

  /** 装配本次执行的阻塞 Environment 绑定；只有带 Environment 路由身份的调用才需要。 */
  BoundEnvironment bind(
      EnvironmentId environmentId,
      String modelCallId,
      ExpectedFile expectedFile,
      ToolGateway.RemoteExecutionObserver remoteExecutions) {
    return new PlatformBoundEnvironment(
        environmentId, capabilityTransport, modelCallId, expectedFile, remoteExecutions);
  }

  /**
   * 挂接既有远端执行。
   *
   * <p>挂接前到达的 partial 先缓冲，执行体开始后按到达顺序转发；退出 ACK 独立于终态 future 直接确认。
   *
   * @throws EnvironmentCapabilityUnavailableException 发送前目标不可用
   */
  ToolGateway.Run reattach(ToolGateway.ReattachExecution reattach)
      throws EnvironmentCapabilityUnavailableException {
    EnvironmentId environmentId = reattach.environmentId();
    UUID remoteExecutionId = reattach.remoteExecutionId();
    String modelCallId = reattach.request().call().id();

    CompletableFuture<EnvironmentCapabilityResult> terminal = new CompletableFuture<>();
    AtomicReference<Consumer<ToolResult>> progressRef = new AtomicReference<>();
    ConcurrentLinkedQueue<EnvironmentCapabilityResult> bufferedPartials =
        new ConcurrentLinkedQueue<>();

    EnvironmentCapabilityExecutionListener listener =
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onPartial(EnvironmentCapabilityResult partial) {
            if (!terminal.isDone()) {
              Consumer<ToolResult> progress = progressRef.get();
              if (progress != null) {
                progress.accept(toToolResult(partial, remoteExecutionId, modelCallId));
              } else {
                bufferedPartials.add(partial);
              }
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
            reattach.remoteExecutions().confirmExit(remoteExecutionId);
          }
        };

    EnvironmentCapabilityExecutionHandle transportHandle =
        capabilityTransport.attach(environmentId, remoteExecutionId, listener);
    if (transportHandle == null) {
      throw new IllegalStateException(
          "remote capability returned a null execution handle; outcome cannot be confirmed");
    }
    return new ReattachRun(
        transportHandle,
        terminal,
        finalizer,
        reattach.request().call().toolName(),
        modelCallId,
        remoteExecutionId,
        reattach.timeout(),
        progressRef,
        bufferedPartials);
  }

  /** 以本地失败收敛尚未完成的 terminal，并终止远端在途执行。 */
  static void releaseInFlight(
      CompletableFuture<EnvironmentCapabilityResult> terminal,
      EnvironmentCapabilityExecutionHandle transportHandle,
      EnvironmentCapabilityTerminationCause cause,
      Throwable failure) {
    if (!terminal.completeExceptionally(failure)) {
      return;
    }
    terminate(transportHandle, cause, failure);
  }

  /** 终止远端；清理失败不替换原始异常。 */
  static void terminate(
      EnvironmentCapabilityExecutionHandle transportHandle,
      EnvironmentCapabilityTerminationCause cause,
      Throwable failure) {
    try {
      transportHandle.terminate(cause);
    } catch (RuntimeException | Error cleanup) {
      if (cleanup != failure && failure != null) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  /** 远端结果映射回模型 call id：反向回显（模型 id）不是合法远端关联，保留为不匹配。 */
  static ToolResult toToolResult(
      EnvironmentCapabilityResult result, UUID remoteExecutionId, String modelCallId) {
    // 远端只应回显本次执行 id；映射回模型 call id。模型 id 回显不是合法远端关联，保留为不匹配。
    String remoteCallId = remoteExecutionId.toString();
    String toolCallId = result.callId();
    if (remoteCallId.equals(toolCallId)) {
      toolCallId = modelCallId;
    } else if (modelCallId.equals(toolCallId)) {
      toolCallId = remoteCallId;
    }
    return new ToolResult(toolCallId, result.contents(), result.error(), result.detailsJson());
  }
}
