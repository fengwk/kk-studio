package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class TaskToolTest {

  private static final UUID INVOCATION_ID = id(1);
  private static final UUID PARENT_THREAD_ID = id(2);
  private static final UUID CHILD_SESSION_ID = id(3);
  private static final UUID CHILD_THREAD_ID = id(4);
  private static final UUID PARENT_SESSION_ID = id(5);
  private static final UUID ROOT_THREAD_ID = id(6);

  @Test
  void descriptorKeepsCanonicalTaskArgumentSchema() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) ->
                execution(ThreadJoinOutcome.COMPLETED, "done", null, null));
    ToolDescriptor descriptor = tool.descriptor();
    InputSchema schema = descriptor.inputSchema();

    assertEquals(TaskTool.NAME, descriptor.name());
    assertEquals("task", descriptor.rendererKey());
    assertEquals(ToolSideEffect.NON_IDEMPOTENT, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertEquals(Set.of("subagent_type", "prompt"), schema.required());
    assertEquals(
        Set.of("subagent_type", "prompt", "max_turns", "thread_id"), schema.properties().keySet());
    assertInstanceOf(StringSchema.class, schema.properties().get("subagent_type"));
    assertInstanceOf(StringSchema.class, schema.properties().get("prompt"));
    assertInstanceOf(IntegerSchema.class, schema.properties().get("max_turns"));
    assertInstanceOf(StringSchema.class, schema.properties().get("thread_id"));
  }

  @Test
  void rejectsMalformedAndInvalidTaskArgumentsBeforeStartingRunner() {
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(new TaskTool((request, async, accepted) -> null), "not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(new TaskTool((request, async, accepted) -> null), "[\"coder\"]"));
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) -> {
              throw new AssertionError("invalid arguments must not start a subagent");
            });

    assertRejected(
        tool, "{\"subagent_type\":\" \",\"prompt\":\"work\"}", "subagent_type is required");
    assertRejected(
        tool,
        "{\"subagent_type\":\" coder \",\"prompt\":\"work\"}",
        "subagent_type must not contain surrounding whitespace");
    assertRejected(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"work\",\"max_turns\":0}",
        "max_turns must be a positive integer");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            createRequest(
                tool, "{\"subagent_type\":\"coder\",\"prompt\":\"work\",\"max_turns\":1.5}"));
    assertRejected(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"work\",\"thread_id\":\"invalid\"}",
        "thread_id must be a canonical UUID string");
  }

  @Test
  void requiresDurableToolExecutionContext() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) ->
                execution(ThreadJoinOutcome.COMPLETED, "done", null, null));
    ToolExecutionRequest request =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall(
                "call-task", TaskTool.NAME, "{\"subagent_type\":\"coder\",\"prompt\":\"work\"}"),
            Duration.ZERO);

    assertThrows(IllegalArgumentException.class, () -> tool.execute(request, ignored -> {}));
  }

  @Test
  void validationRejectionExplainsThatTaskWasNotExecuted() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) -> {
              throw new AssertionError("rejected task must not be executed");
            });

    ToolResult result =
        tool.execute(
                createRequest(tool, "{\"subagent_type\":\" coder \",\"prompt\":\"work\"}"),
                ignored -> {})
            .result();

    assertTrue(result.error());
    assertTrue(text(result).contains("The tool was not executed."));
    assertTrue(text(result).contains("thread_id"));
  }

  @Test
  void taskHidesAsyncAndReturnsOnlyAfterSuccessfulJoinResult() {
    AtomicReference<Boolean> async = new AtomicReference<>();
    AtomicReference<ToolResult> running = new AtomicReference<>();
    TaskTool tool =
        new TaskTool(
            (request, asyncMode, accepted) -> {
              async.set(asyncMode);
              accepted.accept(acceptance());
              return execution(ThreadJoinOutcome.COMPLETED, "done", null, null);
            });

    ToolOutcome outcome =
        tool.execute(
            createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"work\"}"), running::set);

    assertFalse(async.get());
    assertEquals("running", status(running.get()));
    assertFalse(outcome.result().error());
    assertEquals("done", text(outcome.result()));
    assertEquals("completed", status(outcome.result()));
    assertTrue(outcome.result().detailsJson().contains(CHILD_THREAD_ID.toString()));
  }

  @Test
  void reportsSubagentFailureAndPartialWorkAsToolError() {
    TaskTool tool = tool(ThreadJoinOutcome.ERROR, null, "compiler failed", "partial output");

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertEquals("failed", status(result));
    assertTrue(text(result).contains("compiler failed"));
    assertTrue(text(result).contains("partial output"));
  }

  @Test
  void reportsSubagentCancellationAsToolError() {
    TaskTool tool = tool(ThreadJoinOutcome.CANCELLED, null, "Cancelled by parent.", null);

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertEquals("cancelled", status(result));
    assertTrue(text(result).contains("Cancelled by parent."));
  }

  @Test
  void rejectsCompletedJoinWithoutFinalAnswer() {
    TaskTool tool = tool(ThreadJoinOutcome.COMPLETED, null, null, null);

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertEquals("failed", status(result));
    assertTrue(text(result).contains("without a final answer"));
  }

  @Test
  void concurrencyLimitReturnsExplicitNotStartedError() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) -> {
              throw new RejectedExecutionException("subagent concurrency quota exceeded");
            });

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertTrue(
        text(result)
            .contains(
                "not started: the subagent concurrency limit is reached; retry in a later batch"));
  }

  @Test
  void parallelTasksReturnOnlyAfterBothJoinResultsAreReady() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch releaseSecond = new CountDownLatch(1);
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) -> {
              assertFalse(async);
              accepted.accept(acceptance());
              started.countDown();
              CountDownLatch release = request.prompt().equals("a") ? releaseFirst : releaseSecond;
              try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test Join was not released");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test Join wait was interrupted", interrupted);
              }
              return execution(ThreadJoinOutcome.COMPLETED, "done-" + request.prompt(), null, null);
            });

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ToolOutcome> first =
          workers.submit(
              () ->
                  tool.execute(
                      createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"a\"}"),
                      ignored -> {}));
      Future<ToolOutcome> second =
          workers.submit(
              () ->
                  tool.execute(
                      createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"b\"}"),
                      ignored -> {}));
      assertTrue(started.await(1, TimeUnit.SECONDS));
      releaseFirst.countDown();
      assertEquals("done-a", text(first.get(1, TimeUnit.SECONDS).result()));
      assertFalse(second.isDone());
      releaseSecond.countDown();
      assertEquals("done-b", text(second.get(1, TimeUnit.SECONDS).result()));
    }
  }

  @Test
  void reportsRunnerFailureWithCause() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) -> {
              throw new IllegalStateException("subagent failed unexpectedly");
            });

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertTrue(text(result).contains("subagent failed unexpectedly"));
  }

  @Test
  void invalidArgumentsAreReportedWithoutStartingSubagent() {
    TaskTool tool =
        new TaskTool(
            (request, async, accepted) ->
                execution(ThreadJoinOutcome.COMPLETED, "done", null, null));

    ToolResult result =
        tool.execute(
                createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\" \"}"),
                ignored -> {})
            .result();

    assertTrue(result.error());
    assertTrue(text(result).contains("prompt is required"));
  }

  private static TaskTool tool(
      ThreadJoinOutcome outcome, String report, String error, String partial) {
    return new TaskTool((request, async, accepted) -> execution(outcome, report, error, partial));
  }

  private static SubagentTaskResult execution(
      ThreadJoinOutcome outcome, String report, String error, String partial) {
    ThreadJoinReceipt receipt =
        new ThreadJoinReceipt(
            INVOCATION_ID, CHILD_THREAD_ID, "coder", outcome, "work", report, partial, error);
    return new SubagentTaskResult(acceptance(), receipt);
  }

  private static SubagentTaskAcceptance acceptance() {
    return new SubagentTaskAcceptance(CHILD_SESSION_ID, CHILD_THREAD_ID, false, false);
  }

  private static ToolResult execute(TaskTool tool) {
    return tool.execute(
            createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"work\"}"), ignored -> {})
        .result();
  }

  private static ToolExecutionRequest createRequest(TaskTool tool, String arguments) {
    ToolExecutionContext context =
        new ToolExecutionContext(
            INVOCATION_ID,
            PARENT_THREAD_ID,
            CHILD_SESSION_ID,
            ROOT_THREAD_ID,
            PARENT_SESSION_ID,
            UUID.randomUUID(),
            Instant.now(),
            TurnMediaCapabilities.NONE,
            Optional.empty());
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall("call-task", TaskTool.NAME, arguments),
        Duration.ZERO,
        context);
  }

  private static void assertRejected(TaskTool tool, String arguments, String expected) {
    ToolResult result = tool.execute(createRequest(tool, arguments), ignored -> {}).result();
    assertTrue(result.error());
    assertTrue(text(result).contains(expected), text(result));
  }

  private static String status(ToolResult result) {
    return JsonMapper.builder().build().readTree(result.detailsJson()).get("status").stringValue();
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static UUID id(long value) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
  }
}
