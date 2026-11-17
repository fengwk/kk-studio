package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.api.SpawnQuotaExceededException;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
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
    TaskTool tool = taskTool((request, accepted) -> acceptance());
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
        () -> createRequest(taskTool((request, accepted) -> null), "not json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(taskTool((request, accepted) -> null), "[\"coder\"]"));
    TaskTool tool =
        taskTool(
            (request, accepted) -> {
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
    TaskTool tool = taskTool((request, accepted) -> acceptance());
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
        taskTool(
            (request, accepted) -> {
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
  void taskPersistsChildWaitAndReturnsWithoutAResult() {
    AtomicReference<ToolResult> running = new AtomicReference<>();
    TaskTool tool =
        taskTool(
            (request, accepted) -> {
              accepted.accept(acceptance());
              return acceptance();
            });

    ToolOutcome outcome =
        tool.execute(
            createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"work\"}"), running::set);

    assertEquals("running", status(running.get()));
    assertTrue(outcome.waitingForChild());
    assertEquals(null, outcome.result());
  }

  @Test
  void materializesTaskReceiptsFromJoinWithoutRetainingAnExecutionThread() {
    ThreadJoin join = join();
    ToolResult failure =
        TaskTool.resultFromJoin(
            JsonMapper.builder().build(),
            "call-task",
            CHILD_SESSION_ID,
            join,
            receipt(ThreadJoinOutcome.ERROR, null, "compiler failed", "partial output"));
    ToolResult cancellation =
        TaskTool.resultFromJoin(
            JsonMapper.builder().build(),
            "call-task",
            CHILD_SESSION_ID,
            join,
            receipt(ThreadJoinOutcome.CANCELLED, null, "Cancelled by parent.", null));
    ToolResult noAnswer =
        TaskTool.resultFromJoin(
            JsonMapper.builder().build(),
            "call-task",
            CHILD_SESSION_ID,
            join,
            receipt(ThreadJoinOutcome.COMPLETED, null, null, null));

    assertTrue(failure.error());
    assertEquals("failed", status(failure));
    assertTrue(text(failure).contains("compiler failed"));
    assertTrue(text(failure).contains("partial output"));
    assertTrue(cancellation.error());
    assertEquals("cancelled", status(cancellation));
    assertTrue(text(cancellation).contains("Cancelled by parent."));
    assertTrue(noAnswer.error());
    assertEquals("failed", status(noAnswer));
    assertTrue(text(noAnswer).contains("without a final answer"));
  }

  /** 额度满由类型判定（可被调用链包装），不依赖异常文本。 */
  @Test
  void concurrencyLimitReturnsExplicitNotStartedError() {
    TaskTool tool =
        taskTool(
            (request, accepted) -> {
              throw new IllegalStateException(
                  "admission failed", new SpawnQuotaExceededException("any wording"));
            });

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertTrue(
        text(result)
            .contains(
                "not started: the subagent concurrency limit is reached; retry in a later batch"));
  }

  @Test
  void eachAcceptedSubagentReturnsADeferredOutcome() {
    TaskTool tool = taskTool((request, accepted) -> acceptance());

    ToolOutcome first =
        tool.execute(
            createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"a\"}"), ignored -> {});
    ToolOutcome second =
        tool.execute(
            createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"b\"}"), ignored -> {});

    assertTrue(first.waitingForChild());
    assertTrue(second.waitingForChild());
  }

  @Test
  void reportsRunnerFailureWithCause() {
    TaskTool tool =
        taskTool(
            (request, accepted) -> {
              throw new IllegalStateException("subagent failed unexpectedly");
            });

    ToolResult result = execute(tool);

    assertTrue(result.error());
    assertTrue(text(result).contains("subagent failed unexpectedly"));
  }

  @Test
  void invalidArgumentsAreReportedWithoutStartingSubagent() {
    TaskTool tool = taskTool((request, accepted) -> acceptance());

    ToolResult result =
        tool.execute(
                createRequest(tool, "{\"subagent_type\":\"coder\",\"prompt\":\" \"}"),
                ignored -> {})
            .result();

    assertTrue(result.error());
    assertTrue(text(result).contains("prompt is required"));
  }

  /** 测试统一用生产构造器：runner + 默认 ObjectMapper；工具行为只由 runner 与 arguments 决定。 */
  private static TaskTool taskTool(SubagentRunner runner) {
    return new TaskTool(runner, JsonMapper.builder().build());
  }

  private static ThreadJoin join() {
    return new ThreadJoin(
        INVOCATION_ID,
        "0".repeat(64),
        PARENT_THREAD_ID,
        CHILD_THREAD_ID,
        1L,
        "coder",
        10,
        0L,
        null,
        null,
        null,
        Instant.parse("2026-06-01T00:00:00Z"),
        Instant.parse("2026-06-01T00:00:00Z"),
        JoinDelivery.SETTLE_TOOL_RESULT,
        null);
  }

  private static ThreadJoinReceipt receipt(
      ThreadJoinOutcome outcome, String report, String error, String partial) {
    return new ThreadJoinReceipt(
        INVOCATION_ID, CHILD_THREAD_ID, "coder", outcome, "work", report, partial, error);
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
