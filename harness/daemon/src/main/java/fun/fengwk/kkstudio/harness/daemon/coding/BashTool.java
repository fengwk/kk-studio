package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes a Cloud-authorized shell command after enforcing only the explicit workspace workdir.
 *
 * <p>Static parsing cannot sandbox shell internals; command authorization belongs to Cloud
 * permission. The Daemon nevertheless validates workdir and terminates the complete process tree on
 * timeout or cancellation.
 */
public final class BashTool implements Tool {

  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
  private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1);
  private final CodingToolsConfig config;
  private final WorkspacePathBoundary boundary;
  private final ToolDescriptor descriptor;

  public BashTool(CodingToolsConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    boundary = new WorkspacePathBoundary(config);
    descriptor =
        new ToolDescriptor(
            "bash",
            "1",
            "Execute an already Cloud-authorized bash command in a validated workspace workdir.",
            null,
            new ToolParamsSchema(
                "Bash parameters",
                Map.of(
                    "command", new ToolStringSchema("Cloud-authorized shell command"),
                    "workdir", new ToolStringSchema("Optional workspace-relative directory")),
                Set.of("command"),
                false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.NON_IDEMPOTENT,
            Duration.ofMinutes(5));
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
    EXECUTOR.execute(() -> run(request, listener, handle));
    return handle;
  }

  private void run(
      ToolExecutionRequest request, ToolExecutionListener listener, BashHandle handle) {
    try {
      JsonNode args = AbstractCodingTool.arguments(request);
      String command = AbstractCodingTool.string(args, "command");
      Path workdir = boundary.workdir(AbstractCodingTool.optionalString(args, "workdir"));
      Process process =
          new ProcessBuilder(config.bashExecutable(), "-lc", command)
              .directory(workdir.toFile())
              .redirectErrorStream(true)
              .start();
      handle.process = process;
      if (handle.cancelled.get()) {
        handle.stopProcessTree();
      }
      SCHEDULER.schedule(
          () -> {
            if (handle.timedOut.compareAndSet(false, true)) {
              handle.stopProcessTree();
            }
          },
          request.effectiveTimeout().toMillis(),
          TimeUnit.MILLISECONDS);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (InputStream input = process.getInputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
          output.write(buffer, 0, count);
          if (!handle.cancelled.get() && !handle.timedOut.get()) {
            listener.onPartial(
                new ToolResult(
                    request.call().id(),
                    List.of(
                        new TextToolContent(new String(buffer, 0, count, StandardCharsets.UTF_8))),
                    false,
                    "{}",
                    false));
          }
        }
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

  private static final class BashHandle implements ToolExecutionHandle {
    private final String callId;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Process process;

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
        listener.onComplete(result);
      }
    }
  }
}
