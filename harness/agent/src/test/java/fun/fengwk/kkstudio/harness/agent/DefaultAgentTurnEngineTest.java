package fun.fengwk.kkstudio.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.ModelCapability;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolArraySchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolEnumSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolNumberSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolObjectSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultAgentTurnEngineTest {

  /** 流式正文、推理和工具参数均由最终快照补齐，并只返回通过 schema 的工具调用。 */
  @Test
  void aggregatesDeltasAndFillsFinalGaps() {
    ProviderResponse response =
        response(
            "hello",
            "think",
            List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")),
            ProviderStopReason.TOOL_CALLS);
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .delta(new ProviderStreamEvent.TextDelta("hel"))
            .delta(new ProviderStreamEvent.ThinkingDelta("th"))
            .delta(new ProviderStreamEvent.ToolCallDelta(0, "call-", "re", "{\"path\":\"REA"))
            .complete(response)
            .build();
    RecordingHandler handler = new RecordingHandler();

    new DefaultAgentTurnEngine(provider).execute(request(), handler);

    assertEquals(
        List.of("started", "delta", "delta", "delta", "delta", "delta", "delta", "completed"),
        handler.events);
    assertEquals("hello", handler.result.assistantMessage().text());
    assertEquals("think", handler.result.assistantMessage().thinking());
    assertEquals("call-1", handler.result.toolCalls().get(0).id());
    assertEquals("read", handler.result.toolCalls().get(0).toolName());
    assertEquals("{\"path\":\"README.md\"}", handler.result.toolCalls().get(0).argumentsJson());
    assertEquals("read", provider.request().tools().get(0).name());
    assertFalse(handler.failed);
  }

  /** 截断响应携带工具调用时必须失败，避免 Runtime 将不完整参数交给执行层。 */
  @Test
  void rejectsLengthLimitedToolCalls() {
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .complete(
                response(
                    "",
                    "",
                    List.of(new ProviderToolCall("call-1", "read", "{\"path\":\"README.md\"}")),
                    ProviderStopReason.LENGTH))
            .build();
    RecordingHandler handler = new RecordingHandler();

    new DefaultAgentTurnEngine(provider).execute(request(), handler);

    assertTrue(handler.failed);
    assertEquals(1, handler.events.stream().filter("failed"::equals).count());
    assertFalse(handler.events.contains("completed"));
  }

  /** 未声明工具、非法 JSON 或 schema 不匹配都在 Turn 内失败，不会产生可执行结果。 */
  @Test
  void rejectsMalformedToolCallsBeforeReturningResult() {
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .complete(
                response(
                    "",
                    "",
                    List.of(new ProviderToolCall("call-1", "read", "{\"other\":true}")),
                    ProviderStopReason.TOOL_CALLS))
            .build();
    RecordingHandler handler = new RecordingHandler();

    new DefaultAgentTurnEngine(provider).execute(request(), handler);

    assertTrue(handler.failed);
    assertEquals(null, handler.result);
  }

  /** abort 传播给 Provider，且与任何后续终态回调竞争时只投递一个失败终态。 */
  @Test
  void abortsExactlyOnce() {
    FakeModelProvider provider = FakeModelProvider.sequence().build();
    RecordingHandler handler = new RecordingHandler();
    AgentTurnHandle handle = new DefaultAgentTurnEngine(provider).execute(request(), handler);

    handle.cancel();
    handle.cancel();

    assertTrue(handle.isCancelled());
    assertTrue(provider.stream().isCancelled());
    assertEquals(List.of("started", "failed"), handler.events);
  }

  /** Provider 自身报告取消时也必须归为失败终态，而不是写入不完整消息。 */
  @Test
  void treatsProviderCancellationAsFailure() {
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .complete(response("", "", List.of(), ProviderStopReason.CANCELLED))
            .build();
    RecordingHandler handler = new RecordingHandler();

    new DefaultAgentTurnEngine(provider).execute(request(), handler);

    assertEquals(List.of("started", "failed"), handler.events);
    assertTrue(handler.failed);
  }

  /** Provider 错误为终态，后续声明式事件不会污染该 Turn 的结果。 */
  @Test
  void ignoresEventsAfterProviderFailure() {
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .fail(new ProviderException(ProviderErrorKind.TRANSIENT, "network"))
            .delta(new ProviderStreamEvent.TextDelta("ignored"))
            .complete(response("ignored", "", List.of(), ProviderStopReason.COMPLETED))
            .build();
    RecordingHandler handler = new RecordingHandler();

    new DefaultAgentTurnEngine(provider).execute(request(), handler);

    assertEquals(List.of("started", "failed"), handler.events);
    assertTrue(handler.failed);
  }

  /** Provider 工具声明保留所有受支持 JSON schema 节点，避免 schema 在模型边界丢失。 */
  @Test
  void serializesEverySupportedToolSchema() {
    AgentTurnRequest base = request();
    ToolParamsSchema schema =
        new ToolParamsSchema(
            "parameters",
            Map.of(
                "string", new ToolStringSchema("string"),
                "integer", new ToolIntegerSchema("integer"),
                "number", new ToolNumberSchema("number"),
                "boolean", new ToolBooleanSchema("boolean"),
                "enum", new ToolEnumSchema("enum", List.of("a", "b")),
                "array", new ToolArraySchema("array", new ToolStringSchema("item")),
                "object",
                    new ToolObjectSchema(
                        "object",
                        Map.of("nested", new ToolStringSchema("")),
                        Set.of("nested"),
                        false)),
            Set.of("string"),
            false);
    AgentTurnRequest request =
        new AgentTurnRequest(
            base.model(),
            base.variant(),
            base.messages(),
            List.of(
                new ToolDescriptor(
                    "all",
                    "1",
                    "All schemas",
                    null,
                    schema,
                    ToolExecutionMode.CLOUD,
                    ToolSideEffect.READ_ONLY,
                    Duration.ZERO)));
    FakeModelProvider provider =
        FakeModelProvider.sequence()
            .complete(response("ok", "", List.of(), ProviderStopReason.COMPLETED))
            .build();

    new DefaultAgentTurnEngine(provider).execute(request, new RecordingHandler());

    String definition = provider.request().tools().get(0).inputSchemaJson();
    assertTrue(definition.contains("\"integer\""));
    assertTrue(definition.contains("\"number\""));
    assertTrue(definition.contains("\"boolean\""));
    assertTrue(definition.contains("\"enum\""));
    assertTrue(definition.contains("\"array\""));
  }

  private static AgentTurnRequest request() {
    return new AgentTurnRequest(
        new ModelDescriptor(
            "provider",
            "model",
            "Model",
            1024,
            256,
            Set.of(ModelInputModality.TEXT),
            Set.of(ModelCapability.TEXT, ModelCapability.TOOLS),
            List.of(new ModelVariant("default", null, null, null, null, List.of())),
            new ModelPricing(
                "USD",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)),
        new ModelVariant("default", null, null, null, null, List.of()),
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))),
        List.of(
            new ToolDescriptor(
                "read",
                "1",
                "Read a file",
                null,
                new ToolParamsSchema(
                    "", Map.of("path", new ToolStringSchema("")), Set.of("path"), false),
                ToolExecutionMode.CLOUD,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(5))));
  }

  private static ProviderResponse response(
      String text, String thinking, List<ProviderToolCall> calls, ProviderStopReason stopReason) {
    ModelUsage usage = new ModelUsage(1, 1, 0, 0, 0);
    return new ProviderResponse(
        text, thinking, calls, stopReason, usage, new ModelCost("USD", BigDecimal.ONE));
  }

  private static final class RecordingHandler implements AgentTurnEventHandler {

    private final List<String> events = new ArrayList<>();
    private AgentTurnResult result;
    private boolean failed;

    @Override
    public void onStarted() {
      events.add("started");
    }

    @Override
    public void onDelta(ProviderStreamEvent event) {
      events.add("delta");
    }

    @Override
    public void onCompleted(AgentTurnResult result) {
      events.add("completed");
      this.result = result;
    }

    @Override
    public void onFailed(ProviderException error) {
      events.add("failed");
      failed = true;
    }
  }
}
