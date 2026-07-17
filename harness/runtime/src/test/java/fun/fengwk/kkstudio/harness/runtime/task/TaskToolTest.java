package fun.fengwk.kkstudio.harness.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskToolTest {
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @AfterEach
  void shutdown() {
    scheduler.shutdownNow();
  }

  /** Task start returns immediately and completes only after durable polling observes the child. */
  @Test
  void startsWithoutWaitingThenCompletesFromDurableInspection() throws Exception {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall(
                    "call", "task", "{\"subagent_type\":\"Coder\",\"prompt\":\"Implement it\"}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(11, 12)),
            listener);

    assertEquals(1, runtime.starts);
    assertFalse(listener.completed.await(20, TimeUnit.MILLISECONDS));
    runtime.terminal = true;
    assertTrue(listener.completed.await(1, TimeUnit.SECONDS));
    assertFalse(handle.isCancelled());
    assertFalse(listener.result.error());
    assertTrue(text(listener.result).contains("<task_result>done</task_result>"));
  }

  /**
   * Cancel propagation is a durable, idempotent tree request and does not synthesize a terminal
   * result.
   */
  @Test
  void cancelRequestsTreeOnce() {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall(
                    "call", "task", "{\"subagent_type\":\"Explorer\",\"prompt\":\"Inspect\"}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(21, 22)),
            new RecordingListener());

    handle.cancel();
    handle.cancel();

    assertTrue(handle.isCancelled());
    assertEquals(1, runtime.cancels);
    assertEquals(21L, runtime.cancelledInvocationId);
  }

  /** Terminal starts format both report channels immediately without scheduling a poll. */
  @Test
  void completesImmediatelyForTerminalInspection() {
    RecordingRuntime runtime = new RecordingRuntime();
    runtime.terminal = true;
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    RecordingListener listener = new RecordingListener();

    tool.execute(request("{\"subagent_type\":\"Coder\",\"prompt\":\"done\"}"), listener);

    assertEquals(1, runtime.starts);
    assertEquals(0L, listener.completed.getCount());
    assertTrue(text(listener.result).contains("<task_result>done</task_result>"));
    assertFalse(listener.result.error());
  }

  /** Parser, missing durable context, and runtime errors are converted to one normal Tool error. */
  @Test
  void convertsArgumentAndStartFailuresToToolErrors() {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    for (String arguments :
        List.of(
            "{\"subagent_type\":\"\",\"prompt\":\"x\"}",
            "{\"subagent_type\":\"Coder\",\"prompt\":\"\"}",
            "{\"subagent_type\":\"Coder\",\"prompt\":\"x\",\"session_id\":\"0\"}",
            "{\"subagent_type\":\"Coder\",\"prompt\":\"x\",\"session_id\":\"999999999999999999999\"}")) {
      RecordingListener listener = new RecordingListener();
      tool.execute(request(arguments), listener);
      assertTrue(listener.result.error());
    }
    RecordingListener missingContext = new RecordingListener();
    tool.execute(requestWithoutContext(), missingContext);
    assertTrue(missingContext.result.error());
    runtime.startError = new IllegalStateException();
    RecordingListener runtimeFailure = new RecordingListener();
    tool.execute(request("{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"), runtimeFailure);
    assertEquals("Task execution failed.", text(runtimeFailure.result));
  }

  /**
   * Optional task arguments parse into a pending durable command and terminal handles ignore
   * cancel.
   */
  @Test
  void parsesOptionalSessionAndWorkingCopyPolicy() {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    ToolExecutionHandle pending =
        tool.execute(
            request(
                "{\"subagent_type\":\"Coder\",\"prompt\":\"x\",\"session_id\":\"123\",\"working_copy_policy\":\"NONE\"}"),
            new RecordingListener());
    pending.cancel();
    assertEquals(1, runtime.cancels);

    runtime.terminal = true;
    ToolExecutionHandle terminal =
        tool.execute(
            request("{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"), new RecordingListener());
    terminal.cancel();
    assertEquals(1, runtime.cancels);
  }

  /**
   * Transient inspect failures keep polling so a still-running durable child is not orphaned; the
   * parent completes only after a later successful terminal inspection.
   */
  @Test
  void keepsPollingAfterTransientInspectFailure() throws Exception {
    RecordingRuntime runtime = new RecordingRuntime();
    runtime.inspectError = new IllegalArgumentException("inspect failed");
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    RecordingListener listener = new RecordingListener();
    ToolExecutionHandle handle =
        tool.execute(request("{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"), listener);

    assertFalse(listener.completed.await(40, TimeUnit.MILLISECONDS));
    assertEquals(0, runtime.cancels);
    runtime.inspectError = null;
    runtime.terminal = true;
    assertTrue(listener.completed.await(1, TimeUnit.SECONDS));
    assertFalse(listener.result.error());
    assertFalse(handle.isCancelled());
  }

  /**
   * If the poll schedule cannot be installed after a durable child exists, cancel the tree before
   * returning a Tool error so the child is not left ownerless.
   */
  @Test
  void cancelsDurableChildWhenPollingCannotBeScheduled() {
    RecordingRuntime runtime = new RecordingRuntime();
    ScheduledExecutorService rejecting = Executors.newSingleThreadScheduledExecutor();
    rejecting.shutdown();
    try {
      TaskTool tool =
          new TaskTool(
              runtime, rejecting, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
      RecordingListener listener = new RecordingListener();
      ToolExecutionHandle handle =
          tool.execute(request("{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"), listener);

      assertEquals(0L, listener.completed.getCount());
      assertTrue(listener.result.error());
      assertTrue(text(listener.result).toLowerCase().contains("reject"));
      assertEquals(1, runtime.cancels);
      assertEquals(41L, runtime.cancelledInvocationId);
      handle.cancel();
      handle.cancel();
      assertEquals(1, runtime.cancels);
    } finally {
      rejecting.shutdownNow();
    }
  }

  /** A synchronous first poll cannot publish a periodic future after completion. */
  @Test
  void cancelsPollFutureWhenInspectionCompletesBeforeScheduleReturns() {
    RecordingRuntime runtime = new RecordingRuntime();
    runtime.terminalOnInspect = true;
    RecordingScheduledFuture future = new RecordingScheduledFuture();
    ScheduledThreadPoolExecutor inlineScheduler =
        new ScheduledThreadPoolExecutor(1) {
          @Override
          public ScheduledFuture<?> scheduleWithFixedDelay(
              Runnable command, long initialDelay, long delay, TimeUnit unit) {
            command.run();
            return future;
          }
        };
    try {
      TaskTool tool =
          new TaskTool(
              runtime,
              inlineScheduler,
              Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
              Duration.ofMillis(5));
      RecordingListener listener = new RecordingListener();

      tool.execute(request("{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"), listener);

      assertEquals(0L, listener.completed.getCount());
      assertFalse(listener.result.error());
      assertTrue(future.cancelled);
    } finally {
      inlineScheduler.shutdownNow();
    }
  }

  /** Cancellation without a durable context remains a harmless local operation. */
  @Test
  void handlesContextlessCancellation() {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    ToolExecutionHandle contextless =
        tool.execute(requestWithoutContext(), new RecordingListener());
    contextless.cancel();
    assertTrue(contextless.isCancelled());
    assertEquals(0, runtime.cancels);
  }

  /** Constructors reject invalid polling intervals before a scheduler can be used. */
  @Test
  void rejectsInvalidPollInterval() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskTool(new RecordingRuntime(), scheduler, Clock.systemUTC(), Duration.ZERO));
  }

  /** Strict parsing rejects unknown keys before the runtime can create a durable child. */
  @Test
  void rejectsUnknownArguments() throws Exception {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool =
        new TaskTool(
            runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMillis(5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall(
                    "call", "task", "{\"subagent_type\":\"Coder\",\"prompt\":\"x\",\"bad\":1}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(31, 32)));

    assertEquals(0, runtime.starts);
  }

  private ToolExecutionRequest request(String arguments) {
    TaskTool descriptorSource =
        new TaskTool(
            new RecordingRuntime(),
            scheduler,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            Duration.ofMillis(5));
    return new ToolExecutionRequest(
        descriptorSource.descriptor(),
        new ToolCall("call", "task", arguments),
        descriptorSource.descriptor().timeout(),
        new ToolExecutionContext(41, 42));
  }

  private ToolExecutionRequest requestWithoutContext() {
    TaskTool descriptorSource =
        new TaskTool(
            new RecordingRuntime(),
            scheduler,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            Duration.ofMillis(5));
    return new ToolExecutionRequest(
        descriptorSource.descriptor(),
        new ToolCall("call", "task", "{\"subagent_type\":\"Coder\",\"prompt\":\"x\"}"),
        descriptorSource.descriptor().timeout(),
        null);
  }

  private static String text(ToolResult result) {
    return ((TextToolContent) result.contents().get(0)).text();
  }

  private static final class RecordingListener implements ToolExecutionListener {
    private final CountDownLatch completed = new CountDownLatch(1);
    private ToolResult result;

    @Override
    public void onPartial(ToolResult partial) {}

    @Override
    public void onComplete(ToolResult value) {
      result = value;
      completed.countDown();
    }

    @Override
    public void onError(Throwable error) {
      throw new AssertionError(error);
    }
  }

  private static final class RecordingRuntime implements TaskRuntime {
    private final Instant now = Instant.EPOCH;
    private boolean terminal;
    private boolean terminalOnInspect;
    private RuntimeException startError;
    private RuntimeException inspectError;
    private int starts;
    private int cancels;
    private long cancelledInvocationId;

    @Override
    public TaskInspection startOrResume(
        ToolExecutionContext context, TaskCommand command, Instant ignored) {
      starts++;
      if (startError != null) {
        throw startError;
      }
      return inspection(context.invocationId(), terminal);
    }

    @Override
    public TaskInspection inspect(long parentInvocationId, Instant ignored) {
      if (inspectError != null) {
        throw inspectError;
      }
      return inspection(parentInvocationId, terminal || terminalOnInspect);
    }

    @Override
    public void cancelTree(long parentInvocationId, Instant ignored) {
      cancels++;
      cancelledInvocationId = parentInvocationId;
    }

    private TaskInspection inspection(long invocationId, boolean completed) {
      TaskState state = completed ? TaskState.SUCCEEDED : TaskState.RUNNING;
      SubagentTask task =
          new SubagentTask(
              invocationId,
              2,
              3,
              4,
              "Coder",
              WorkingCopyPolicy.FORK,
              null,
              50,
              null,
              state,
              null,
              now,
              now);
      TaskReport report =
          completed
              ? new TaskReport(3, 4, state, "done", List.of(), 1, 2, WorkingCopyPolicy.FORK, null)
              : null;
      return new TaskInspection(task, report);
    }
  }

  private static final class RecordingScheduledFuture implements ScheduledFuture<Object> {
    private boolean cancelled;

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed other) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled;
    }

    @Override
    public Object get() {
      return null;
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      return null;
    }
  }
}
