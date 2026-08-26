package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在强制校验显式 environment workdir 后执行 Platform 授权的 shell 命令。
 *
 * <p>静态解析无法沙箱化 shell 内部行为；命令授权属于 Platform permission。但 Daemon 仍会校验 workdir，并使用运行时注入的共享 scheduler
 * 在超时或取消时终止完整 process tree。
 */
public final class BashCapability implements EnvironmentCapability {

  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int MAX_TIMEOUT_SECONDS = 3600;
  private final CodingToolsConfig config;
  private final EnvironmentPathBoundary boundary;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final EnvironmentCapabilityDescriptor descriptor;

  public BashCapability(
      CodingToolsConfig config, ExecutorService executor, ScheduledExecutorService scheduler) {
    this.config = Objects.requireNonNull(config, "config");
    boundary = new EnvironmentPathBoundary(config);
    this.executor = Objects.requireNonNull(executor, "executor");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    descriptor = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC);
  }

  @Override
  public EnvironmentCapabilityDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EnvironmentCapabilityExecutionHandle execute(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match tool descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    BashHandle handle = new BashHandle(request.call().id(), listener);
    handle.worker = executor.submit(() -> run(request, listener, handle));
    return handle;
  }

  private void run(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle) {
    try {
      JsonNode args = AbstractCodingCapability.arguments(request);
      String command = AbstractCodingCapability.string(args, "command");
      Path workdir =
          boundary.workdir(
              AbstractCodingCapability.optionalString(args, "workdir"), request.workdir());
      int timeoutSeconds = requestedTimeoutSeconds(args);
      Duration processTimeout =
          effectiveProcessTimeout(request.effectiveTimeout(), Duration.ofSeconds(timeoutSeconds));
      Process process =
          new ProcessBuilder(config.bashExecutable(), "-lc", command)
              .directory(workdir.toFile())
              .redirectErrorStream(true)
              .start();
      handle.process = process;
      if (handle.cancelled.get()) {
        handle.stopProcessTree();
      }
      handle.timeoutFuture =
          scheduler.schedule(
              () -> {
                if (handle.timedOut.compareAndSet(false, true)) {
                  try {
                    executor.execute(handle::stopProcessTree);
                  } catch (RejectedExecutionException ignored) {
                    // runtime shutdown 会同步 cancel handle 并终止进程；scheduler 不执行阻塞等待。
                  }
                }
              },
              processTimeout.toMillis(),
              TimeUnit.MILLISECONDS);
      if (handle.terminal.get()) {
        handle.timeoutFuture.cancel(false);
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Utf8StreamDecoder streamDecoder = new Utf8StreamDecoder();
      try (InputStream input = process.getInputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
          output.write(buffer, 0, count);
          emitPartial(request.call().id(), listener, handle, streamDecoder.decode(buffer, count));
        }
        emitPartial(request.call().id(), listener, handle, streamDecoder.finish());
      }
      int exitCode = process.waitFor();
      if (handle.cancelled.get()) {
        handle.complete(
            listener, AbstractCodingCapability.error(request.call().id(), "Operation cancelled"));
      } else if (handle.timedOut.get()) {
        handle.complete(
            listener, AbstractCodingCapability.error(request.call().id(), "Command timed out"));
      } else {
        boolean failed = exitCode != 0;
        List<ToolContent> contents =
            OutputLimiter.limit(output.toByteArray(), "text/plain", config);
        if (failed) {
          contents = new ArrayList<>(contents);
          contents.add(new TextToolContent("\nCommand exited with code " + exitCode));
        }
        handle.complete(
            listener, new EnvironmentCapabilityResult(request.call().id(), contents, failed, "{}"));
      }
    } catch (Exception error) {
      handle.complete(
          listener, AbstractCodingCapability.error(request.call().id(), error.getMessage()));
    }
  }

  static int requestedTimeoutSeconds(JsonNode args) {
    return AbstractCodingCapability.optionalPositiveInt(
        args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
  }

  static Duration effectiveProcessTimeout(Duration invocationTimeout, Duration requestedTimeout) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Objects.requireNonNull(requestedTimeout, "requestedTimeout");
    return invocationTimeout.compareTo(requestedTimeout) <= 0
        ? invocationTimeout
        : requestedTimeout;
  }

  private static void emitPartial(
      String callId,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle,
      String text) {
    if (!text.isEmpty() && !handle.cancelled.get() && !handle.timedOut.get()) {
      listener.onPartial(
          new EnvironmentCapabilityResult(callId, List.of(new TextToolContent(text)), false, "{}"));
    }
  }

  private static final class BashHandle implements EnvironmentCapabilityExecutionHandle {
    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Process process;
    private volatile Future<?> worker;
    private volatile ScheduledFuture<?> timeoutFuture;

    private BashHandle(String callId, EnvironmentCapabilityExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> current = worker;
        if (current != null) {
          current.cancel(true);
        }
        stopProcessTree();
        complete(listener, AbstractCodingCapability.error(callId, "Operation cancelled"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    private void stopProcessTree() {
      Process current = process;
      if (current == null) {
        return;
      }
      current.toHandle().descendants().forEach(child -> child.destroyForcibly());
      current.destroy();
      try {
        if (!current.waitFor(200, TimeUnit.MILLISECONDS)) {
          current.destroyForcibly();
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        current.destroyForcibly();
      }
    }

    private void complete(
        EnvironmentCapabilityExecutionListener listener, EnvironmentCapabilityResult result) {
      if (terminal.compareAndSet(false, true)) {
        ScheduledFuture<?> timeout = timeoutFuture;
        if (timeout != null) {
          timeout.cancel(false);
        }
        listener.onComplete(result);
      }
    }
  }
}
