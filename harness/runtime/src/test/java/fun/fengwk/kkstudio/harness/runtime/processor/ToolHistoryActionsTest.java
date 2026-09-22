package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.port.ToolHistoryActionResolver;
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
import java.util.Optional;
import java.util.Set;

/**
 * {@link ToolHistoryActions} 冻结契约单元测试：只有 READY（binding 存在且参数可按 schema 归一化）的成功调用才请求渲染，且任何渲染器行为
 * ——成功、缺失、blank、异常——都不得让冻结过程失败；冻结只改写 ProviderToolCall 的 historyAction，durable arguments 与调用顺序原样保留。
 *
 * <p>该冻结发生在 terminal ProviderResponse 持久化之前，因此这里的失败语义直接决定模型请求是否会失败。
 */
class ToolHistoryActionsTest {

  private static final InputSchema SCHEMA =
      new InputSchema(
          "arguments",
          Map.of("path", new StringSchema(null), "limit", new IntegerSchema(null)),
          Set.of(),
          false);

  /** READY 调用：渲染器成功时只有该调用的 historyAction 被写入，其余事实逐字保留。 */
  @Test
  void freezesRenderedActionForReadyCall() {
    ProviderResponse response =
        response(
            GenerationStopReason.COMPLETE,
            List.of(
                new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}"),
                new ProviderToolCall("call-2", "fs_read", "{\"path\":\"b.txt\"}")));
    ToolHistoryActionResolver resolver =
        (binding, call) ->
            "call-1".equals(call.id()) ? Optional.of("read a.txt") : Optional.empty();

    ProviderResponse frozen = ToolHistoryActions.freeze(response, bindings(), resolver);

    assertEquals("read a.txt", frozen.toolCalls().get(0).historyAction());
    assertNull(frozen.toolCalls().get(1).historyAction());
    // 除 action 外的 durable 事实（顺序、身份、arguments、usage/cost）逐字不变。
    assertEquals(List.of("call-1", "call-2"), ids(frozen));
    assertEquals("{\"path\":\"a.txt\"}", frozen.toolCalls().get(0).argumentsJson());
    assertEquals(response.usage(), frozen.usage());
    assertEquals(response.cost(), frozen.cost());
    // 未冻结任何 action 时返回同一个实例，避免无意义的 durable 改写。
    assertSame(
        response, ToolHistoryActions.freeze(response, bindings(), (b, c) -> Optional.empty()));
  }

  /** 渲染器输入的 call 必须是按 schema 完成类型归一化的调用，而不是 raw provider 文本；binding 原样传给渲染器。 */
  @Test
  void rendersNormalizedCallAndPassesBinding() {
    ProviderResponse response =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":null,\"limit\":\"3\"}")));
    List<ToolCall> seen = new ArrayList<>();
    List<ToolBinding> seenBindings = new ArrayList<>();

    ProviderResponse frozen =
        ToolHistoryActions.freeze(
            response,
            bindings(),
            (binding, call) -> {
              seen.add(call);
              seenBindings.add(binding);
              return Optional.of("read " + call.argumentsJson());
            });

    assertEquals(1, seen.size());
    // 渲染器看到的是 schema 归一化后的调用（显式 null 被归一化掉、数字字符串归一化为整数），而 durable arguments 只保持 provider 的
    // canonical JSON 文本不变——语义渲染绝不改写 durable 事实。
    assertEquals("{\"limit\":3}", seen.getFirst().argumentsJson());
    assertEquals(
        response.toolCalls().getFirst().argumentsJson(),
        frozen.toolCalls().getFirst().argumentsJson());
    assertEquals("call-1", seen.getFirst().id());
    assertEquals("fs_read", seenBindings.getFirst().descriptor().name());
  }

  /** 无渲染器提供映射（empty）、无 resolver、无 tool call：响应原样返回，绝不写入空 action。 */
  @Test
  void absentMappingsLeaveResponseUntouched() {
    ProviderResponse response =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));

    assertSame(
        response, ToolHistoryActions.freeze(response, bindings(), (b, c) -> Optional.empty()));
    assertSame(response, ToolHistoryActions.freeze(response, bindings(), null));
    ProviderResponse withoutCalls = response(GenerationStopReason.COMPLETE, List.of());
    assertSame(
        withoutCalls,
        ToolHistoryActions.freeze(withoutCalls, bindings(), (b, c) -> Optional.of("x")));
  }

  /** 渲染异常、返回 null、返回 blank 都按「未提供映射」处理：完成路径继续成功，action 保持 null，模型请求不会因为历史语义渲染而失败。 */
  @Test
  void rendererFailureBlankAndNullDegradeToNoAction() {
    ProviderResponse response =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));

    assertNull(
        ToolHistoryActions.freeze(
                response,
                bindings(),
                (b, c) -> {
                  throw new IllegalStateException("renderer exploded");
                })
            .toolCalls()
            .getFirst()
            .historyAction());
    assertNull(
        ToolHistoryActions.freeze(response, bindings(), (b, c) -> null)
            .toolCalls()
            .getFirst()
            .historyAction());
    assertNull(
        ToolHistoryActions.freeze(response, bindings(), (b, c) -> Optional.of("  "))
            .toolCalls()
            .getFirst()
            .historyAction());
    // 单个调用渲染失败不影响同批其它调用的冻结结果。
    ProviderResponse mixed =
        response(
            GenerationStopReason.COMPLETE,
            List.of(
                new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}"),
                new ProviderToolCall("call-2", "fs_read", "{\"path\":\"b.txt\"}")));
    ProviderResponse frozen =
        ToolHistoryActions.freeze(
            mixed,
            bindings(),
            (b, c) -> {
              if ("call-1".equals(c.id())) {
                throw new IllegalStateException("renderer exploded");
              }
              return Optional.of("read b.txt");
            });
    assertNull(frozen.toolCalls().get(0).historyAction());
    assertEquals("read b.txt", frozen.toolCalls().get(1).historyAction());
  }

  /**
   * 非 READY 调用不请求渲染：unknown tool（无 binding）、schema 不兼容参数、LENGTH 截断都保持 null——它们的语义由确定性的执行失败或截断
   * 错误表达，渲染器无权改写。
   */
  @Test
  void nonReadyCallsNeverRequestRendering() {
    List<ToolCall> seen = new ArrayList<>();
    ToolHistoryActionResolver recordingResolver =
        (binding, call) -> {
          seen.add(call);
          return Optional.of("should not be frozen");
        };

    ProviderResponse unknownTool =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "undeclared", "{}")));
    assertNull(
        ToolHistoryActions.freeze(unknownTool, bindings(), recordingResolver)
            .toolCalls()
            .getFirst()
            .historyAction());

    ProviderResponse schemaInvalid =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":3}")));
    assertNull(
        ToolHistoryActions.freeze(schemaInvalid, bindings(), recordingResolver)
            .toolCalls()
            .getFirst()
            .historyAction());

    ProviderResponse truncated =
        response(
            GenerationStopReason.LENGTH,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));
    assertNull(
        ToolHistoryActions.freeze(truncated, bindings(), recordingResolver)
            .toolCalls()
            .getFirst()
            .historyAction());

    assertTrue(seen.isEmpty(), () -> "non-READY calls must not reach the renderer: " + seen);
  }

  /** 冻结过程自身的基础设施异常（规划阶段出现意外 RuntimeException）也必须收敛为「无 action」：模型调用已经成功，历史语义渲染无权让它失败。 */
  @Test
  void freezingInfrastructureFailureDegradesToNoAction() {
    ProviderResponse response =
        response(
            GenerationStopReason.COMPLETE,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));
    List<ToolBinding> corrupted = new ArrayList<>();
    corrupted.add(null);
    corrupted.addAll(bindings());

    ProviderResponse frozen =
        ToolHistoryActions.freeze(response, corrupted, (b, c) -> Optional.of("read a.txt"));

    assertSame(response, frozen);
    assertNull(frozen.toolCalls().getFirst().historyAction());
  }

  /** 规划结果不是 ToolBatch（该调用不会执行）时不得冻结任何 action，响应原样返回。 */
  @Test
  void nonToolBatchPlanLeavesResponseUntouched() {
    ProviderResponse filtered =
        response(
            GenerationStopReason.FILTERED,
            List.of(new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}")));

    assertSame(
        filtered,
        ToolHistoryActions.freeze(filtered, bindings(), (b, c) -> Optional.of("read a.txt")));
  }

  private static List<String> ids(ProviderResponse response) {
    return response.toolCalls().stream().map(ProviderToolCall::id).toList();
  }

  private static List<ToolBinding> bindings() {
    return List.of(
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    "fs_read",
                    "read a file",
                    "fs.read",
                    SCHEMA,
                    ToolSideEffect.READ_ONLY,
                    Duration.ofSeconds(30)),
                ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "fs.read", List.of()),
            EnvironmentSupport.NONE,
            null,
            null));
  }

  private static ProviderResponse response(
      GenerationStopReason stopReason, List<ProviderToolCall> calls) {
    return new ProviderResponse(
        "response text",
        "",
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
}
