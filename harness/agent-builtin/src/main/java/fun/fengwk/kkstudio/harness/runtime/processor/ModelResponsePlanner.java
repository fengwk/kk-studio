package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 纯函数：把已经通过 {@link ModelResponseValidator} canonical 校验的 {@link ProviderResponse} 规划为 Thread 可执行
 * 的模型结果。
 *
 * <p>决策顺序固定为 generation stop reason -&gt; frozen binding lookup -&gt; {@link
 * ToolCall#validateFor(ToolDescriptor)} 归一化兼容校验，与 {@code ProviderResponse.toolCalls()} 正交。CONTINUE
 * 没有 tool intent，直接规划为 {@link ModelResponsePlan.Continue}。其余每个 observed tool call 恰好产生一个 {@link
 * ModelResponsePlan.ToolSlot}（保持 mixed batch 的完整 callIndex）：COMPLETE 下 valid 为 READY（仍保留 raw
 * canonical call，执行边界再确定性归一化）、schema-invalid 为 FAILED(INVALID_TOOL_ARGUMENTS)、unknown 为
 * FAILED(UNKNOWN_TOOL)；LENGTH 下全部为 FAILED(MODEL_OUTPUT_TRUNCATED)，binding 尽力查找（可空）。invalid
 * canonical response 不会到达这里—— {@link ModelExecution} 在 SUCCEEDED 前已把它转为 {@code INVALID_RESPONSE}
 * retry。
 */
public final class ModelResponsePlanner {

  private static final String MODEL_OUTPUT_TRUNCATED = "MODEL_OUTPUT_TRUNCATED";

  private final ToolDeclarationPlanner declarationPlanner = new ToolDeclarationPlanner();

  public ModelResponsePlan plan(ProviderResponse response, List<ToolBinding> frozenBindings) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(frozenBindings, "frozenBindings");
    return switch (response.stopReason()) {
      case COMPLETE -> planComplete(response, frozenBindings);
      case LENGTH -> planLength(response, frozenBindings);
      case FILTERED -> new ModelResponsePlan.Failed(TurnEndReason.CONTENT_FILTERED);
      case CONTINUE -> new ModelResponsePlan.Continue();
    };
  }

  private ModelResponsePlan planComplete(
      ProviderResponse response, List<ToolBinding> frozenBindings) {
    if (response.toolCalls().isEmpty()) {
      return new ModelResponsePlan.Completed();
    }
    List<ModelResponsePlan.ToolSlot> slots = new ArrayList<>(response.toolCalls().size());
    for (ProviderToolCall call : response.toolCalls()) {
      ToolDeclarationPlanner.Declaration declaration =
          declarationPlanner.plan(toToolCall(call), frozenBindings);
      slots.add(
          new ModelResponsePlan.ToolSlot(
              declaration.call(),
              declaration.binding(),
              declaration.valid() ? ToolInvocationStatus.READY : ToolInvocationStatus.FAILED,
              declaration.error()));
    }
    return new ModelResponsePlan.ToolBatch(List.copyOf(slots));
  }

  private static ModelResponsePlan planLength(
      ProviderResponse response, List<ToolBinding> frozenBindings) {
    if (response.toolCalls().isEmpty()) {
      return new ModelResponsePlan.Failed(TurnEndReason.OUTPUT_TRUNCATED);
    }
    List<ModelResponsePlan.ToolSlot> slots = new ArrayList<>(response.toolCalls().size());
    for (ProviderToolCall call : response.toolCalls()) {
      slots.add(
          new ModelResponsePlan.ToolSlot(
              toToolCall(call),
              ToolDeclarationPlanner.findBinding(frozenBindings, call.name()),
              ToolInvocationStatus.FAILED,
              new ToolInvocationError(
                  MODEL_OUTPUT_TRUNCATED,
                  ToolErrorGuidance.message(
                      "model output truncated; tool call not executed: " + call.name(),
                      ExecutionFact.NOT_EXECUTED,
                      "Reduce the response size and call the tool again"))));
    }
    return new ModelResponsePlan.ToolBatch(List.copyOf(slots));
  }

  private static ToolCall toToolCall(ProviderToolCall call) {
    // validator 已保证 id/name 非空且 arguments 是合法 JSON object；ToolCall 构造只会做等价归一化。
    return new ToolCall(call.id(), call.name(), call.argumentsJson());
  }
}
