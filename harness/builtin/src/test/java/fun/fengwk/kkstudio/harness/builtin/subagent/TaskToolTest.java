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

import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** TaskTool 薄适配器的严格参数解析、Runner 委托、异常降级与取消传递测试。 */
class TaskToolTest {

  private static final UUID INVOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID THREAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  /** descriptor 固定暴露 name/version/renderer/sideEffect/no-timeout 与必填 schema 及 requirements。 */
  @Test
  void exposesCanonicalDescriptorContract() {
    TaskTool tool = new TaskTool((request, listener) -> mock(ToolExecutionHandle.class));
    ToolDescriptor descriptor = tool.descriptor();

    assertEquals(TaskTool.NAME, descriptor.name());
    assertEquals(TaskTool.VERSION, descriptor.version());
    assertEquals(TaskTool.RENDERER_KEY, descriptor.rendererKey());
    assertEquals(ToolSideEffect.NON_IDEMPOTENT, descriptor.sideEffect());
    assertEquals(Duration.ZERO, descriptor.timeout());
    assertFalse(descriptor.description().isBlank());
    assertEquals(ToolRequirements.none(), tool.requirements());

    ToolParamsSchema schema = descriptor.inputSchema();
    assertEquals(Set.of("subagent_type", "prompt"), schema.required());
    assertEquals(
        Set.of("subagent_type", "prompt", "maxTurns", "session_id"), schema.properties().keySet());
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("subagent_type"));
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("prompt"));
    assertInstanceOf(ToolIntegerSchema.class, schema.properties().get("maxTurns"));
    assertInstanceOf(ToolStringSchema.class, schema.properties().get("session_id"));
  }

  /** 合法参数正常解析并完整委托给 SubagentRunner 执行。 */
  @Test
  void parsesValidArgumentsAndDelegatesToRunner() {
    AtomicReference<SubagentTaskRequest> receivedRequest = new AtomicReference<>();
    ToolExecutionHandle mockHandle = mock(ToolExecutionHandle.class);

    SubagentRunner runner =
        (taskRequest, listener) -> {
          receivedRequest.set(taskRequest);
          listener.onComplete(ToolResult.error("call-1", "done"));
          return mockHandle;
        };

    TaskTool tool = new TaskTool(runner);
    AtomicReference<ToolOutcome> outcomeRef = new AtomicReference<>();

    ToolExecutionRequest request =
        createRequest(
            tool,
            "call-1",
            "{\"subagent_type\":\"coder\",\"prompt\":\"write"
                + " code\",\"maxTurns\":5,\"session_id\":\"00000000-0000-0000-0000-000000000003\"}");

    ToolExecutionHandle handle = tool.execute(request, createListener(outcomeRef));

    assertEquals(mockHandle, handle);
    assertNotNull(receivedRequest.get());
    assertEquals(INVOCATION_ID, receivedRequest.get().invocationId());
    assertEquals(THREAD_ID, receivedRequest.get().threadId());
    assertEquals("coder", receivedRequest.get().subagentType());
    assertEquals("write code", receivedRequest.get().prompt());
    assertEquals(5, receivedRequest.get().maxTurns());
    assertEquals(
        UUID.fromString("00000000-0000-0000-0000-000000000003"), receivedRequest.get().sessionId());
  }

  /** 可选参数缺省时正确传递 null。 */
  @Test
  void handlesOptionalMaxTurnsAndSessionId() {
    AtomicReference<SubagentTaskRequest> receivedRequest = new AtomicReference<>();
    SubagentRunner runner =
        (taskRequest, listener) -> {
          receivedRequest.set(taskRequest);
          return mock(ToolExecutionHandle.class);
        };

    TaskTool tool = new TaskTool(runner);
    ToolExecutionRequest request =
        createRequest(tool, "call-2", "{\"subagent_type\":\"explorer\",\"prompt\":\"search\"}");

    tool.execute(request, createListener(new AtomicReference<>()));

    assertNotNull(receivedRequest.get());
    assertEquals("explorer", receivedRequest.get().subagentType());
    assertEquals("search", receivedRequest.get().prompt());
    assertNull(receivedRequest.get().maxTurns());
    assertNull(receivedRequest.get().sessionId());
  }

  /** Schema 级参数校验拒绝：缺少必填字段、非法 JSON、非对象结构会在请求构造阶段抛出 IllegalArgumentException。 */
  @Test
  void rejectsSchemaViolationsOnRequestCreation() {
    TaskTool tool = new TaskTool((req, listener) -> mock(ToolExecutionHandle.class));

    // Missing subagent_type
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(tool, "call-rej-1", "{\"prompt\":\"foo\"}"));

    // Missing prompt
    assertThrows(
        IllegalArgumentException.class,
        () -> createRequest(tool, "call-rej-2", "{\"subagent_type\":\"coder\"}"));

    // Invalid JSON
    assertThrows(
        IllegalArgumentException.class, () -> createRequest(tool, "call-rej-3", "not json"));

    // Non-object JSON
    assertThrows(
        IllegalArgumentException.class, () -> createRequest(tool, "call-rej-4", "[\"coder\"]"));
  }

  /** 语义级参数校验拒绝：空白、前后空格、非正整轮数、非法 UUID 在执行期安全返回错误 ToolResult。 */
  @Test
  void rejectsSemanticViolationsWithDescriptiveErrors() {
    SubagentRunner runner = (req, listener) -> mock(ToolExecutionHandle.class);
    TaskTool tool = new TaskTool(runner);

    // Subagent type with whitespace
    assertRejection(
        tool,
        "{\"subagent_type\":\" coder \",\"prompt\":\"foo\"}",
        "subagent_type must not contain surrounding whitespace");

    // Subagent type blank
    assertRejection(
        tool, "{\"subagent_type\":\"  \",\"prompt\":\"foo\"}", "subagent_type is required");

    // Prompt blank
    assertRejection(tool, "{\"subagent_type\":\"coder\",\"prompt\":\"  \"}", "prompt is required");

    // Invalid maxTurns non-positive (0)
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"maxTurns\":0}",
        "maxTurns must be a positive integer");

    // Invalid maxTurns negative (-1)
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"maxTurns\":-1}",
        "maxTurns must be a positive integer");

    // Invalid session_id not UUID
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"session_id\":\"invalid-uuid\"}",
        "session_id must be a canonical UUID string");

    // Invalid session_id uppercase
    assertRejection(
        tool,
        "{\"subagent_type\":\"coder\",\"prompt\":\"foo\",\"session_id\":\"00000000-0000-0000-0000-00000000000A\"}",
        "session_id must be a canonical UUID string");
  }

  /** Runner 抛出 RuntimeException（如 RejectedExecutionException）时捕获并安全通知 listener。 */
  @Test
  void catchesRunnerExceptionsAndCompletesListenerWithError() {
    SubagentRunner runner =
        (taskRequest, listener) -> {
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
    TaskTool tool = new TaskTool((req, listener) -> mock(ToolExecutionHandle.class));
    ToolExecutionRequest requestWithoutContext =
        new ToolExecutionRequest(
            tool.descriptor(),
            new ToolCall("call-no-ctx", "task", "{\"subagent_type\":\"coder\",\"prompt\":\"p\"}"),
            Duration.ZERO);

    assertThrows(
        IllegalArgumentException.class,
        () -> tool.execute(requestWithoutContext, createListener(new AtomicReference<>())));
  }

  /** 取消句柄正常委托至 Runner 返回的 handle。 */
  @Test
  void delegatesCancellationToRunnerHandle() {
    AtomicBoolean cancelled = new AtomicBoolean();
    ToolExecutionHandle handleStub =
        new ToolExecutionHandle() {
          @Override
          public void cancel() {
            cancelled.set(true);
          }

          @Override
          public boolean isCancelled() {
            return cancelled.get();
          }
        };

    SubagentRunner runner = (taskRequest, listener) -> handleStub;
    TaskTool tool = new TaskTool(runner);

    ToolExecutionRequest request =
        createRequest(tool, "call-cancel", "{\"subagent_type\":\"coder\",\"prompt\":\"p\"}");
    ToolExecutionHandle returnedHandle =
        tool.execute(request, createListener(new AtomicReference<>()));

    assertFalse(returnedHandle.isCancelled());
    returnedHandle.cancel();
    assertTrue(returnedHandle.isCancelled());
    assertTrue(cancelled.get());
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
        new ToolExecutionContext(INVOCATION_ID, THREAD_ID, Instant.now(), mock(BranchView.class));
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
    return ((TextToolContent) result.contents().get(0)).text();
  }
}
