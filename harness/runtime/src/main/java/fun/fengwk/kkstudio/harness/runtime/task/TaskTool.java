package fun.fengwk.kkstudio.harness.runtime.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable, callback-driven CONTROL task Tool. Polling only observes durable child state; no worker
 * thread ever waits for child execution.
 */
public final class TaskTool implements Tool {
  private static final System.Logger LOGGER = System.getLogger(TaskTool.class.getName());
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "task",
          "1",
          "Delegate a task to an allowed durable subagent.",
          "task",
          new ToolParamsSchema(
              "Starts or resumes a subagent task.",
              Map.of(
                  "subagent_type", new ToolStringSchema("Allowed target agent name."),
                  "prompt", new ToolStringSchema("Complete task instruction."),
                  "session_id", new ToolStringSchema("Optional child session id to resume."),
                  "working_copy_policy",
                      new ToolEnumSchema(
                          "Working copy isolation policy.",
                          Arrays.stream(WorkingCopyPolicy.values()).map(Enum::name).toList())),
              Set.of("subagent_type", "prompt"),
              false),
          ToolExecutionMode.CONTROL,
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);

  private final TaskRuntime runtime;
  private final ScheduledExecutorService scheduler;
  private final Clock clock;
  private final Duration pollInterval;

  public TaskTool(TaskRuntime runtime, ScheduledExecutorService scheduler, Clock clock) {
    this(runtime, scheduler, clock, DEFAULT_POLL_INTERVAL);
  }

  public TaskTool(
      TaskRuntime runtime, ScheduledExecutorService scheduler, Clock clock, Duration pollInterval) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    if (pollInterval.isNegative() || pollInterval.isZero()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    Handle handle = new Handle(request, listener);
    try {
      if (request.context() == null) {
        throw new IllegalArgumentException("task requires a durable execution context");
      }
      TaskInspection inspection =
          runtime.startOrResume(
              request.context(), parse(request.call().argumentsJson()), clock.instant());
      if (inspection.terminal()) {
        complete(handle, inspection.report());
      } else {
        try {
          ScheduledFuture<?> future =
              scheduler.scheduleWithFixedDelay(
                  () -> inspect(handle),
                  pollInterval.toMillis(),
                  pollInterval.toMillis(),
                  TimeUnit.MILLISECONDS);
          handle.future = future;
          if (handle.cancelled.get() || handle.completed.get()) {
            cancelFuture(handle);
          }
        } catch (RuntimeException scheduleError) {
          // Durable child is already running; without a poll schedule the parent would orphan it.
          failAfterDurableChild(handle, scheduleError);
        }
      }
    } catch (RuntimeException error) {
      if (handle.completed.compareAndSet(false, true)) {
        listener.onComplete(ToolResult.error(request.call().id(), message(error)));
      }
    }
    return handle;
  }

  private void inspect(Handle handle) {
    if (handle.cancelled.get() || handle.completed.get()) {
      return;
    }
    try {
      TaskInspection inspection =
          runtime.inspect(handle.request.context().invocationId(), clock.instant());
      if (inspection.terminal()) {
        complete(handle, inspection.report());
      }
    } catch (RuntimeException error) {
      // Transient inspect failures must not terminalize the parent while the durable child may
      // still be running; keep the poll schedule and recover on a later tick.
      LOGGER.log(
          System.Logger.Level.WARNING,
          () ->
              "TaskTool inspect failed for invocation "
                  + handle.request.context().invocationId()
                  + "; will retry",
          error);
    }
  }

  private void failAfterDurableChild(Handle handle, RuntimeException error) {
    if (!handle.completed.compareAndSet(false, true)) {
      return;
    }
    cancelFuture(handle);
    try {
      runtime.cancelTree(handle.request.context().invocationId(), clock.instant());
    } catch (RuntimeException cancelError) {
      LOGGER.log(
          System.Logger.Level.WARNING,
          () ->
              "TaskTool failed to cancel durable child after scheduler rejection for invocation "
                  + handle.request.context().invocationId(),
          cancelError);
    }
    handle.listener.onComplete(ToolResult.error(handle.request.call().id(), message(error)));
  }

  private void complete(Handle handle, TaskReport report) {
    if (!handle.completed.compareAndSet(false, true)) {
      return;
    }
    cancelFuture(handle);
    String json = TaskResultFormatter.json(report);
    ToolResult result =
        new ToolResult(
            handle.request.call().id(),
            List.of(
                new TextToolContent(TaskResultFormatter.text(report)), new JsonToolContent(json)),
            !report.success(),
            json,
            false);
    handle.listener.onComplete(result);
  }

  private static void cancelFuture(Handle handle) {
    ScheduledFuture<?> future = handle.future;
    if (future != null) {
      future.cancel(false);
      handle.future = null;
    }
  }

  private static TaskCommand parse(String json) {
    try {
      JsonNode root = OBJECT_MAPPER.readTree(json);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("task arguments must be a JSON object");
      }
      Iterator<String> names = root.fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        if (!Set.of("subagent_type", "prompt", "session_id", "working_copy_policy")
            .contains(name)) {
          throw new IllegalArgumentException("task arguments contain unknown field: " + name);
        }
      }
      String type = text(root, "subagent_type", true);
      String prompt = text(root, "prompt", true);
      Long sessionId = null;
      if (root.has("session_id") && !root.get("session_id").isNull()) {
        sessionId = parseSnowflakeId(text(root, "session_id", true), "session_id");
      }
      String policy = text(root, "working_copy_policy", false);
      return new TaskCommand(
          type, prompt, sessionId, policy == null ? null : WorkingCopyPolicy.parse(policy));
    } catch (IOException error) {
      throw new IllegalArgumentException("task arguments must be valid JSON", error);
    }
  }

  private static String message(RuntimeException error) {
    return error.getMessage() == null || error.getMessage().isBlank()
        ? "Task execution failed."
        : error.getMessage();
  }

  private static long parseSnowflakeId(String value, String field) {
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(field + " must be a positive decimal string");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " is outside the signed 64-bit range", error);
    }
  }

  private static String text(JsonNode root, String field, boolean required) {
    JsonNode value = root.get(field);
    if (value == null || value.isNull()) {
      if (required) {
        throw new IllegalArgumentException(field + " is required");
      }
      return null;
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(field + " must be a string");
    }
    return value.textValue();
  }

  private final class Handle implements ToolExecutionHandle {
    private final ToolExecutionRequest request;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();
    private volatile ScheduledFuture<?> future;

    private Handle(ToolExecutionRequest request, ToolExecutionListener listener) {
      this.request = request;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }
      cancelFuture(this);
      if (!completed.get() && request.context() != null) {
        runtime.cancelTree(request.context().invocationId(), clock.instant());
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
