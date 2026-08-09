package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在强制校验显式 environment workdir 后执行 Platform 授权的 shell 命令。
 *
 * <p>静态解析无法沙箱化 shell 内部行为；命令授权属于 Platform permission。但 Daemon 仍会校验 workdir，并在超时或取消时终止完整 process
 * tree。
 */
public final class BashTool implements Tool {

  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int MAX_TIMEOUT_SECONDS = 3600;
  private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();
  private final CodingToolsConfig config;
  private final EnvironmentPathBoundary boundary;
  private final ToolDescriptor descriptor;

  public BashTool(CodingToolsConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    boundary = new EnvironmentPathBoundary(config);
    descriptor = EnvironmentToolCatalog.require("bash");
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match tool descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    BashHandle handle = new BashHandle(request.call().id(), listener);
    Thread.ofVirtual().name("daemon-bash").start(() -> run(request, listener, handle));
    return handle;
  }

  private void run(
      ToolExecutionRequest request, ToolExecutionListener listener, BashHandle handle) {
    try {
      JsonNode args = AbstractCodingTool.arguments(request);
      String command = AbstractCodingTool.string(args, "command");
      Path workdir = boundary.workdir(AbstractCodingTool.optionalString(args, "workdir"));
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
          SCHEDULER.schedule(
              () -> {
                if (handle.timedOut.compareAndSet(false, true)) {
                  handle.stopProcessTree();
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
            listener, AbstractCodingTool.error(request.call().id(), "Operation cancelled"));
      } else if (handle.timedOut.get()) {
        handle.complete(
            listener, AbstractCodingTool.error(request.call().id(), "Command timed out"));
      } else {
        boolean failed = exitCode != 0;
        List<ToolContent> contents =
            OutputLimiter.limit(output.toByteArray(), "text/plain", config);
        if (failed) {
          contents = new ArrayList<>(contents);
          contents.add(new TextToolContent("\nCommand exited with code " + exitCode));
        }
        handle.complete(
            listener, new ToolResult(request.call().id(), contents, failed, "{}", false));
      }
    } catch (Exception error) {
      handle.complete(listener, AbstractCodingTool.error(request.call().id(), error.getMessage()));
    }
  }

  static int requestedTimeoutSeconds(JsonNode args) {
    return AbstractCodingTool.optionalPositiveInt(
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
      String callId, ToolExecutionListener listener, BashHandle handle, String text) {
    if (!text.isEmpty() && !handle.cancelled.get() && !handle.timedOut.get()) {
      listener.onPartial(
          new ToolResult(callId, List.of(new TextToolContent(text)), false, "{}", false));
    }
  }

  private static ScheduledThreadPoolExecutor createScheduler() {
    ScheduledThreadPoolExecutor scheduler =
        new ScheduledThreadPoolExecutor(1, threadFactory("daemon-bash-timeout"));
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  private static ThreadFactory threadFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static final class BashHandle implements ToolExecutionHandle {
    private final String callId;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Process process;
    private volatile ScheduledFuture<?> timeoutFuture;

    private BashHandle(String callId, ToolExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        stopProcessTree();
        complete(listener, AbstractCodingTool.error(callId, "Operation cancelled"));
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

    private void complete(ToolExecutionListener listener, ToolResult result) {
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
