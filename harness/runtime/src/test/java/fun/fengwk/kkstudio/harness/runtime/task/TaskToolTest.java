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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    TaskTool tool = new TaskTool(runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    RecordingListener listener = new RecordingListener();

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("call", "task", "{\"subagent_type\":\"Coder\",\"prompt\":\"Implement it\"}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(11, 12, 13)),
            listener);

    assertEquals(1, runtime.starts);
    assertFalse(listener.completed.await(20, TimeUnit.MILLISECONDS));
    runtime.terminal = true;
    assertTrue(listener.completed.await(1, TimeUnit.SECONDS));
    assertFalse(handle.isCancelled());
    assertFalse(listener.result.error());
    assertTrue(text(listener.result).contains("<task_result>done</task_result>"));
  }

  /** Cancel propagation is a durable, idempotent tree request and does not synthesize a terminal result. */
  @Test
  void cancelRequestsTreeOnce() {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool = new TaskTool(runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    ToolExecutionHandle handle =
        tool.execute(
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("call", "task", "{\"subagent_type\":\"Explorer\",\"prompt\":\"Inspect\"}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(21, 22, 23)),
            new RecordingListener());

    handle.cancel();
    handle.cancel();

    assertTrue(handle.isCancelled());
    assertEquals(1, runtime.cancels);
    assertEquals(21L, runtime.cancelledInvocationId);
  }

  /** Strict parsing rejects unknown keys before the runtime can create a durable child. */
  @Test
  void rejectsUnknownArguments() throws Exception {
    RecordingRuntime runtime = new RecordingRuntime();
    TaskTool tool = new TaskTool(runtime, scheduler, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolExecutionRequest(
                tool.descriptor(),
                new ToolCall("call", "task", "{\"subagent_type\":\"Coder\",\"prompt\":\"x\",\"bad\":1}"),
                tool.descriptor().timeout(),
                new ToolExecutionContext(31, 32, 33)));

    assertEquals(0, runtime.starts);
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
    private int starts;
    private int cancels;
    private long cancelledInvocationId;

    @Override
    public TaskInspection startOrResume(
        ToolExecutionContext context, TaskCommand command, Instant ignored) {
      starts++;
      return inspection(context.invocationId(), false);
    }

    @Override
    public TaskInspection inspect(long parentInvocationId, Instant ignored) {
      return inspection(parentInvocationId, terminal);
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
              WorkspacePolicy.FORK,
              50,
              null,
              state,
              null,
              now,
              now);
      TaskReport report =
          completed ? new TaskReport(3, 4, state, "done", List.of(), 1, 2, null) : null;
      return new TaskInspection(task, report);
    }
  }
}
