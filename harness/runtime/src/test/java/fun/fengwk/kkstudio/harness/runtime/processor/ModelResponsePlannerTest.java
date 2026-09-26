package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ModelResponsePlanner} 决策矩阵单元测试：generation stop reason -&gt; frozen binding lookup -&gt;
 * schema validation，每个 observed call 一个 callIndex 槽位。
 */
class ModelResponsePlannerTest {

  private static final ModelResponsePlanner PLANNER = new ModelResponsePlanner();

  @Test
  void completeWithoutCallsCompletesTheTurn() {
    ModelResponsePlan plan =
        PLANNER.plan(response(GenerationStopReason.COMPLETE, List.of()), bindings());

    assertSame(ModelResponsePlan.Completed.class, plan.getClass());
  }

  /** thinking-only 是有效模型输出；COMPLETE 且无工具调用时必须正常结束回合。 */
  @Test
  void completeWithThinkingOnlyCompletesTheTurn() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                "", "reasoning without visible text", GenerationStopReason.COMPLETE, List.of()),
            bindings());

    assertSame(ModelResponsePlan.Completed.class, plan.getClass());
  }

  @Test
  void completeWithValidCallsCreatesReadySlotsInOrdinalOrder() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(
                    new ProviderToolCall("call-1", "bash", "{}"),
                    new ProviderToolCall("call-2", "bash", "{}"))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    assertEquals(2, batch.tools().size());
    for (int callIndex = 0; callIndex < 2; callIndex++) {
      ModelResponsePlan.ToolSlot slot = batch.tools().get(callIndex);
      assertEquals("call-" + (callIndex + 1), slot.call().id());
      assertEquals("bash", slot.call().toolName());
      assertEquals(ToolInvocationStatus.READY, slot.status());
      assertNull(slot.error());
      assertEquals("bash", slot.binding().descriptor().name());
    }
  }

  /** schema-invalid 但已知的 call：FAILED(INVALID_TOOL_ARGUMENTS)，binding 保留（供 history rendererKey）。 */
  @Test
  void completeWithSchemaInvalidCallFailsWithInvalidToolArguments() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(new ProviderToolCall("call-1", "bash", "{\"unexpected\":1}"))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    assertEquals(1, batch.tools().size());
    ModelResponsePlan.ToolSlot slot = batch.tools().getFirst();
    assertEquals(ToolInvocationStatus.FAILED, slot.status());
    assertEquals("INVALID_TOOL_ARGUMENTS", slot.error().kind());
    assertEquals("bash", slot.binding().descriptor().name());
    assertTrue(slot.error().message().contains("argumentsJson"));
  }

  /** unknown tool：FAILED(UNKNOWN_TOOL)，binding 为 null（planner 不做可见性校验，unknown 直接降级）。 */
  @Test
  void completeWithUnknownToolFailsWithUnknownToolAndNullBinding() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(new ProviderToolCall("call-1", "undeclared", "{}"))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    ModelResponsePlan.ToolSlot slot = batch.tools().getFirst();
    assertEquals(ToolInvocationStatus.FAILED, slot.status());
    assertEquals("UNKNOWN_TOOL", slot.error().kind());
    assertNull(slot.binding());
  }

  /** mixed batch：每 call 一个槽位，READY 与 FAILED 按 callIndex 并存。 */
  @Test
  void completeWithMixedBatchKeepsFullOrdinal() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(
                    new ProviderToolCall("call-1", "bash", "{}"),
                    new ProviderToolCall("call-2", "missing", "{}"),
                    new ProviderToolCall("call-3", "bash", "{\"unexpected\":1}"))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    assertEquals(3, batch.tools().size());
    assertEquals(ToolInvocationStatus.READY, batch.tools().get(0).status());
    assertEquals(ToolInvocationStatus.FAILED, batch.tools().get(1).status());
    assertEquals("UNKNOWN_TOOL", batch.tools().get(1).error().kind());
    assertNull(batch.tools().get(1).binding());
    assertEquals(ToolInvocationStatus.FAILED, batch.tools().get(2).status());
    assertEquals("INVALID_TOOL_ARGUMENTS", batch.tools().get(2).error().kind());
    assertEquals("call-2", batch.tools().get(1).call().id());
    assertEquals("call-3", batch.tools().get(2).call().id());
  }

  /** 可选 null 与数字字符串只要可确定性归一化就进入 READY；durable 槽位仍保留 raw call 与 assistant history 对齐。 */
  @Test
  void readySlotAcceptsNormalizableArgumentsWithoutMutatingDurableCall() {
    InputSchema schema =
        new InputSchema(
            "arguments",
            Map.of("path", new StringSchema(null), "limit", new IntegerSchema(null)),
            Set.of(),
            false);
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(
                    new ProviderToolCall("call-1", "bash", "{\"path\":null,\"limit\":\"10\"}"))),
            bindings(toolBinding("bash", schema)));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    ModelResponsePlan.ToolSlot slot = batch.tools().getFirst();
    assertEquals(ToolInvocationStatus.READY, slot.status());
    assertEquals("{\"path\":null,\"limit\":\"10\"}", slot.call().argumentsJson());
  }

  /** schema-invalid 的 FAILED 槽位保留 raw canonical call（不携带归一化结果）供审计。 */
  @Test
  void invalidSlotKeepsRawCanonicalCall() {
    InputSchema schema =
        new InputSchema(
            "arguments",
            Map.of("path", new StringSchema(null), "limit", new IntegerSchema(null)),
            Set.of("path"),
            false);
    String rawArguments = "{\"path\":null,\"limit\":\"10\"}";
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(new ProviderToolCall("call-1", "bash", rawArguments))),
            bindings(toolBinding("bash", schema)));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    ModelResponsePlan.ToolSlot slot = batch.tools().getFirst();
    assertEquals(ToolInvocationStatus.FAILED, slot.status());
    assertEquals("INVALID_TOOL_ARGUMENTS", slot.error().kind());
    assertEquals(rawArguments, slot.call().argumentsJson());
  }

  /** unknown tool 的 FAILED 槽位同样保留 raw canonical call，便于 audit 未声明工具的实际入参。 */
  @Test
  void unknownToolSlotKeepsRawCanonicalCall() {
    String rawArguments = "{\"anything\":\"10\"}";
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.COMPLETE,
                List.of(new ProviderToolCall("call-1", "undeclared", rawArguments))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolSlot slot = ((ModelResponsePlan.ToolBatch) plan).tools().getFirst();
    assertEquals(ToolInvocationStatus.FAILED, slot.status());
    assertEquals("UNKNOWN_TOOL", slot.error().kind());
    assertEquals(rawArguments, slot.call().argumentsJson());
  }

  @Test
  void lengthWithoutCallsFailsWithOutputTruncated() {
    ModelResponsePlan plan =
        PLANNER.plan(response(GenerationStopReason.LENGTH, List.of()), bindings());

    ModelResponsePlan.Failed failed = (ModelResponsePlan.Failed) plan;
    assertEquals(TurnEndReason.OUTPUT_TRUNCATED, failed.reason());
  }

  /** LENGTH 有 calls：全部 immediate FAILED(MODEL_OUTPUT_TRUNCATED)，执行零个，binding 尽力查找。 */
  @Test
  void lengthWithManyCallsFailsEverySlotWithModelOutputTruncated() {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      calls.add(new ProviderToolCall("call-" + i, "bash", "{}"));
    }
    ModelResponsePlan plan =
        PLANNER.plan(response(GenerationStopReason.LENGTH, calls), bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    assertEquals(20, batch.tools().size());
    for (int callIndex = 0; callIndex < 20; callIndex++) {
      ModelResponsePlan.ToolSlot slot = batch.tools().get(callIndex);
      assertEquals("call-" + (callIndex + 1), slot.call().id());
      assertEquals(ToolInvocationStatus.FAILED, slot.status());
      assertEquals("MODEL_OUTPUT_TRUNCATED", slot.error().kind());
      assertEquals("bash", slot.binding().descriptor().name());
    }
  }

  /** LENGTH + unknown call：binding 可空，错误仍是 MODEL_OUTPUT_TRUNCATED（不执行 schema 校验）。 */
  @Test
  void lengthWithUnknownCallStillFailsWithModelOutputTruncatedAndNullBinding() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(
                GenerationStopReason.LENGTH,
                List.of(new ProviderToolCall("call-1", "undeclared", "{}"))),
            bindings(toolBinding("bash")));

    ModelResponsePlan.ToolBatch batch = (ModelResponsePlan.ToolBatch) plan;
    ModelResponsePlan.ToolSlot slot = batch.tools().getFirst();
    assertEquals(ToolInvocationStatus.FAILED, slot.status());
    assertEquals("MODEL_OUTPUT_TRUNCATED", slot.error().kind());
    assertNull(slot.binding());
  }

  @Test
  void filteredFailsWithContentFiltered() {
    ModelResponsePlan plan =
        PLANNER.plan(response(GenerationStopReason.FILTERED, List.of()), bindings());

    ModelResponsePlan.Failed failed = (ModelResponsePlan.Failed) plan;
    assertEquals(TurnEndReason.CONTENT_FILTERED, failed.reason());
  }

  /** Provider 协议续写终止态：CONTINUE 无 tool intent，直接规划为 Continue（不进入任何 tool 决策）。 */
  @Test
  void continueWithoutToolIntentPlansContinuation() {
    ModelResponsePlan plan =
        PLANNER.plan(
            response(GenerationStopReason.CONTINUE, List.of()), bindings(toolBinding("bash")));

    assertSame(ModelResponsePlan.Continue.class, plan.getClass());
  }

  @Test
  void readySlotRequiresBindingAndForbidsError() {
    ToolCall call = new ToolCall("call-1", "bash", "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelResponsePlan.ToolSlot(call, null, ToolInvocationStatus.READY, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelResponsePlan.ToolSlot(
                call, toolBinding("bash"), ToolInvocationStatus.READY, truncationError()));
  }

  @Test
  void failedSlotRequiresErrorAndOnlyAllowsFailedStatus() {
    ToolCall call = new ToolCall("call-1", "bash", "{}");
    ToolBinding binding = toolBinding("bash");
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelResponsePlan.ToolSlot(call, binding, ToolInvocationStatus.FAILED, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelResponsePlan.ToolSlot(
                call, binding, ToolInvocationStatus.READY, truncationError()));
  }

  // -----------------------------------------------------------------------------------------------
  // fixtures
  // -----------------------------------------------------------------------------------------------

  private static List<ToolBinding> bindings(ToolBinding... bindings) {
    return List.of(bindings);
  }

  private static ToolBinding toolBinding(String name) {
    return toolBinding(name, new InputSchema("arguments", Map.of(), Set.of(), false));
  }

  private static ToolBinding toolBinding(String name, InputSchema schema) {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                name,
                "description of " + name,
                name,
                schema,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", name, List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ProviderResponse response(
      GenerationStopReason stopReason, List<ProviderToolCall> calls) {
    return response("response text", "", stopReason, calls);
  }

  private static ProviderResponse response(
      String text, String thinking, GenerationStopReason stopReason, List<ProviderToolCall> calls) {
    return new ProviderResponse(
        text,
        thinking,
        calls,
        stopReason,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        "{}");
  }

  private static ToolInvocationError truncationError() {
    return new ToolInvocationError(
        "MODEL_OUTPUT_TRUNCATED", "model output truncated; tool call not executed");
  }
}
