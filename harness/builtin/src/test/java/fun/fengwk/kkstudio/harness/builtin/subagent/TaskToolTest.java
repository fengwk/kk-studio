package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TaskTool 薄适配器的严格参数解析、持久接受委托与错误降级测试。
 *
 * <p>异步契约下 TaskTool 只有「持久接受」一个阶段：接受成功即回一次英文自然语言接受正文（首行 {@code Task accepted. thread_id: <uuid>.}）的
 * tool_result，不等待终态、不返回可取消句柄；busy follow-up 时正文额外说明已取代旧的 pending wait，只会有一份汇总结果。
 */
class TaskToolTest {

  private static final UUID INVOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID PARENT_THREAD_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID CHILD_SESSION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID CHILD_THREAD_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000004");

  /** descriptor 固定暴露 name/renderer/sideEffect/no-timeout 与 snake_case 参数 schema。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    TaskTool tool = new TaskTool(request -> acceptance(false));
    ToolDescriptor descriptor = tool.descriptor();

    assertEquals(TaskTool.NAME, descriptor.name());
    assertEquals(TaskTool.RENDERER_KEY, descriptor.rendererKey());
    assertEquals(ToolSideEffect.NON_IDEMPOTENT, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.defaultTimeout());
    assertFalse(descriptor.description().isBlank());
    assertEquals(ToolRequirements.none(), tool.requirements());

    InputSchema schema = descriptor.inputSchema();
    assertEquals(Set.of("subagent_type", "prompt"), schema.required());
    assertEquals(
        Set.of("subagent_type", "prompt", "max_turns", "thread_id"), schema.properties().keySet());
    assertInstanceOf(StringSchema.class, schema.properties().get("subagent_type"));
    assertInstanceOf(StringSchema.class, schema.properties().get("prompt"));
    assertInstanceOf(IntegerSchema.class, schema.properties().get("max_turns"));
    assertInstanceOf(StringSchema.class, schema.properties().get("thread_id"));
  }

  /** 合法参数完整委托给 Runner，并把接受结果立即回执为一次成功 tool_result。 */
  @Test
  void acceptsValidArgumentsAndReturnsAcceptedReceipt() {
    AtomicReference<SubagentTaskRequest> receivedRequest = new AtomicReference<>();
    SubagentRunner runner =
        request -> {
          receivedRequest.set(request);
          return acceptance(false);
        };

    TaskTool tool = new TaskTool(runner);
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request =
        createRequest(
            tool,
            "call-1",
            "{\"subagent_type\":\"coder\",\"prompt\":\"write code\",\"max_turns\":5,"
                + "\"thread_id\":\"00000000-0000-0000-0000-00000000000a\"}");

    ToolExecutionHandle handle = tool.execute(request, createListener(outcomeRef));

    assertNotNull(handle);
    assertNotNull(receivedRequest.get());
    assertEquals(INVOCATION_ID, receivedRequest.get().invocationId());
    assertEquals(PARENT_THREAD_ID, receivedRequest.get().parentThreadId());
    assertEquals("coder", receivedRequest.get().subagentType());
    assertEquals("write code", receivedRequest.get().prompt());
    assertEquals(5, receivedRequest.get().maxTurns());
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        receivedRequest.get().resumeThreadId());

    ToolResult result = outcomeRef.get().result();
    assertFalse(result.error());
    // 即时回执是英文自然语言接受正文，首行显式给出 thread_id，不重复 prompt。
    assertEquals(SubagentTaskMessages.accepted(CHILD_THREAD_ID), text(result));
    assertTrue(
        text(result).startsWith("Task accepted. thread_id: " + CHILD_THREAD_ID + "."),
        text(result));
    assertFalse(text(result).contains("write code"), text(result));
    // details 与回执表达同一份事实，并保留 UI 需要的 kind/会话/幂等元数据。
    String details = result.detailsJson();
    assertTrue(details.contains("\"kind\":\"task.accepted\""), details);
    assertTrue(details.contains("\"thread_id\":\"" + CHILD_THREAD_ID + "\""), details);
    assertTrue(details.contains("\"status\":\"accepted\""), details);
    assertTrue(details.contains("\"replayed\":false"), details);
  }

  /** 可选参数缺省时正确传递 null（新任务、policy 默认预算）。 */
  @Test
  void handlesOptionalMaxTurnsAndThreadId() {
    AtomicReference<SubagentTaskRequest> receivedRequest = new AtomicReference<>();
    SubagentRunner runner =
        request -> {
          receivedRequest.set(request);
          return acceptance(false);
        };

    TaskTool tool = new TaskTool(runner);
    ToolExecutionRequest request =
        createRequest(tool, "call-2", "{\"subagent_type\":\"explorer\",\"prompt\":\"search\"}");

    tool.execute(request, createListener(new AtomicReference<>()));

    assertNotNull(receivedRequest.get());
    assertEquals("explorer", receivedRequest.get().subagentType());
    assertEquals("search", receivedRequest.get().prompt());
    assertNull(receivedRequest.get().maxTurns());
    assertNull(receivedRequest.get().resumeThreadId());
  }

  /** Schema 级参数校验拒绝在请求构造阶段抛出 IllegalArgumentException。 */
  @Test
  void rejectsSchemaViolationsOnRequestCreation() {
    TaskTool tool = new TaskTool(request -> acceptance(false));

    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(tool, "call-rej-1", "{\"prompt\":\"foo\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(tool, "call-rej-2", "{\"subagent_type\":\"coder\"}"));
    assertThrows(
        IllegalArgumentException.class, () -> createRequest(tool, "call-rej-3", "not json"));
    assertThrows(
        IllegalArgumentException.class, () -> createRequest(tool, "call-rej-4", "[\"coder\"]"));
  }

  /** 语义级参数校验拒绝：空白、前后空格、非正整轮数、非法 UUID 在执行期安全返回错误 ToolResult。 */
  @Test
  void rejectsSemanticViolationsWithDescriptiveErrors() {
    TaskTool tool = new TaskTool(request -> acceptance(false));

    assertRejection(
        tool,
        "{\"subagent_type\":\" coder \",\"prompt\":\"foo\"}",
        "subagent_type must not contain surrounding whitespace");
    assertRejection(
        tool, "{\"subagent_type\":\"  \",\"prompt\":\"foo\"}", "subagent_type is required");
    assertRejection(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"  \"}", "prompt is required");
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"max_turns\":0}",
        "max_turns must be a positive integer");
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"max_turns\":-1}",
        "max_turns must be a positive integer");
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"thread_id\":\"invalid-uuid\"}",
        "thread_id must be a canonical UUID string");
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\","
            + "\"thread_id\":\"00000000-0000-0000-0000-00000000000A\"}",
        "thread_id must be a canonical UUID string");
  }

  /** 非幂等重放：Runner 返回 replayed=true 时回执同样成功，交给父判断是否已有既有执行。 */
  @Test
  void reportsReplayedAcceptanceOnIdempotentRetry() {
    TaskTool tool = new TaskTool(request -> acceptance(true));
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request =
        createRequest(tool, "call-replay", "{\"subagent_type\":\"coder\",\"prompt\":\"p\"}");

    tool.execute(request, createListener(outcomeRef));

    ToolResult result = outcomeRef.get().result();
    assertFalse(result.error());
    assertTrue(result.detailsJson().contains("\"replayed\":true"));
  }

  /** busy follow-up：Runner 报告取代了旧未完成委派时，回执正文明确只会有最新一次的一份汇总结果。 */
  @Test
  void reportsReplacedPendingWaitOnContinuation() {
    TaskTool tool = new TaskTool(request -> replacedAcceptance());
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request =
        createRequest(tool, "call-replaced", "{\"subagent_type\":\"coder\",\"prompt\":\"p\"}");

    tool.execute(request, createListener(outcomeRef));

    ToolResult result = outcomeRef.get().result();
    assertFalse(result.error());
    assertTrue(text(result).contains("replaces the previous pending wait"), text(result));
    assertTrue(text(result).contains("only one consolidated result"), text(result));
    assertTrue(result.detailsJson().contains("\"replaced\":true"));
  }

  /** Runner 拒绝或抛异常时捕获并安全通知 listener，绝不逃出调用线程。 */
  @Test
  void catchesRunnerExceptionsAndCompletesListenerWithError() {
    SubagentRunner runner =
        request -> {
          throw new RejectedExecutionException("subagent concurrency limit reached");
        };

    TaskTool tool = new TaskTool(runner);
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request =
        createRequest(tool, "call-err", "{\"subagent_type\":\"coder\",\"prompt\":\"do\"}");

    ToolExecutionHandle handle = tool.execute(request, createListener(outcomeRef));

    assertNotNull(handle);
    assertNotNull(outcomeRef.get());
    assertTrue(outcomeRef.get().result().error());
    assertTrue(text(outcomeRef.get().result()).contains("subagent concurrency limit reached"));
  }

  /** 执行缺少 durable 上下文时快速抛出 IllegalArgumentException。 */
  @Test
  void requiresDurableExecutionContext() {
    TaskTool tool = new TaskTool(request -> acceptance(false));
    ToolExecutionRequest requestWithoutContext =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("call-no-ctx", "task", "{\"subagent_type\":\"coder\",\"prompt\":\"p\"}"),
            Duration.ZERO);

    assertThrows(
        IllegalArgumentException.class,
        () -> tool.execute(requestWithoutContext, createListener(new AtomicReference<>())));
  }

  private static SubagentTaskAcceptance acceptance(boolean replayed) {
    return new SubagentTaskAcceptance(CHILD_SESSION_ID, CHILD_THREAD_ID, replayed, false);
  }

  private static SubagentTaskAcceptance replacedAcceptance() {
    return new SubagentTaskAcceptance(CHILD_SESSION_ID, CHILD_THREAD_ID, false, true);
  }

  private static void assertRejection(
      TaskTool tool, String argumentsJson, String expectedMessagePart) {
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request = createRequest(tool, "call-rej", argumentsJson);
    ToolExecutionHandle handle = tool.execute(request, createListener(outcomeRef));
    assertNotNull(handle);
    assertNotNull(outcomeRef.get(), "listener.onComplete must be called on rejection");
    assertTrue(outcomeRef.get().result().error());
    assertTrue(
        text(outcomeRef.get().result()).contains(expectedMessagePart),
        () ->
            "expected '"
                + expectedMessagePart
                + "' but got '"
                + text(outcomeRef.get().result())
                + "'");
  }

  private static ToolExecutionRequest createRequest(
      TaskTool tool, String callId, String argumentsJson) {
    ToolExecutionContext context =
        new ToolExecutionContext(
            INVOCATION_ID, PARENT_THREAD_ID, Instant.now(), mock(BranchView.class));
    return new ToolExecutionRequest(
        tool.descriptor(),
        new ToolCall(callId, TaskTool.NAME, argumentsJson),
        Duration.ZERO,
        context);
  }

  private static ToolExecutionListener createListener(AtomicReference<ToolOutcome> outcomeRef) {
    return new ToolExecutionListener() {
      @Override
      public void onPartial(ToolResult partial) {}

      @Override
      public void onComplete(ToolOutcome outcome) {
        outcomeRef.set(outcome);
      }

      @Override
      public void onError(Throwable error) {}
    };
  }

  private static String text(ToolResult result) {
    return ((TextResultContent) result.contents().get(0)).text();
  }

  /** task 参数被拒：明确未执行、父线程保留，并列出继续参数 subagent_type/prompt/thread_id，不含重试倾向措辞。 */
  @Test
  void rejectionGuidanceStatesNotExecutedAndContinuationParameters() {
    TaskTool tool = new TaskTool(request -> acceptance(false));
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();
    ToolExecutionRequest request =
        createRequest(tool, "call-guidance", "{\"subagent_type\":\" coder \",\"prompt\":\"p\"}");

    tool.execute(request, createListener(outcomeRef));

    String message = text(outcomeRef.get().result());
    assertTrue(message.contains("subagent_type must not contain surrounding whitespace"), message);
    assertTrue(message.contains("The tool was not executed."), message);
    assertTrue(message.contains("subagent_type"), message);
    assertTrue(message.contains("prompt"), message);
    assertTrue(message.contains("thread_id"), message);
    assertFalse(message.toLowerCase().contains("do not retry"), message);
  }
}
