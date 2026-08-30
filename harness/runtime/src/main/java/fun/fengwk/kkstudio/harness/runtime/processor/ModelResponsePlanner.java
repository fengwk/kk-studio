package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.common.schema.InputValidator;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 纯函数：把已经通过 {@link ModelResponseValidator} canonical 校验的 {@link ProviderResponse} 规划为 Thread 可执行
 * 的模型结果。
 *
 * <p>决策顺序固定为 generation stop reason -&gt; frozen binding lookup -&gt; tool schema validation，与
 * {@code ProviderResponse.toolCalls()} 正交。每个 observed tool call 恰好产生一个 {@link
 * ModelResponsePlan.ToolSlot}（保持 mixed batch 的完整 ordinal）：COMPLETE 下 valid 为 READY、 schema-invalid
 * 为 FAILED(INVALID_TOOL_ARGUMENTS)、unknown 为 FAILED(UNKNOWN_TOOL)；LENGTH 下全部为
 * FAILED(MODEL_OUTPUT_TRUNCATED)，binding 尽力查找（可空）。invalid canonical response 不会到达这里—— {@link
 * ModelExecution} 在 SUCCEEDED 前已把它转为 {@code INVALID_RESPONSE} retry。
 */
public final class ModelResponsePlanner {

  private static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
  private static final String INVALID_TOOL_ARGUMENTS = "INVALID_TOOL_ARGUMENTS";
  private static final String MODEL_OUTPUT_TRUNCATED = "MODEL_OUTPUT_TRUNCATED";

  public ModelResponsePlan plan(ProviderResponse response, List<ToolBinding> frozenBindings) {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(frozenBindings, "frozenBindings");
    return switch (response.stopReason()) {
      case COMPLETE -> planComplete(response, frozenBindings);
      case LENGTH -> planLength(response, frozenBindings);
      case FILTERED -> new ModelResponsePlan.Failed(TurnEndReason.CONTENT_FILTERED);
    };
  }

  private static ModelResponsePlan planComplete(
      ProviderResponse response, List<ToolBinding> frozenBindings) {
    if (response.toolCalls().isEmpty()) {
      return new ModelResponsePlan.Completed();
    }
    List<ModelResponsePlan.ToolSlot> slots = new ArrayList<>(response.toolCalls().size());
    for (ProviderToolCall call : response.toolCalls()) {
      ToolBinding binding = findBinding(frozenBindings, call.name());
      if (binding == null) {
        slots.add(
            new ModelResponsePlan.ToolSlot(
                toToolCall(call),
                null,
                ToolInvocationStatus.FAILED,
                new ToolInvocationError(UNKNOWN_TOOL, "unknown tool: " + call.name())));
        continue;
      }
      ToolInvocationError schemaFailure = validateArguments(call, binding);
      if (schemaFailure != null) {
        slots.add(
            new ModelResponsePlan.ToolSlot(
                toToolCall(call), binding, ToolInvocationStatus.FAILED, schemaFailure));
        continue;
      }
      slots.add(
          new ModelResponsePlan.ToolSlot(
              toToolCall(call), binding, ToolInvocationStatus.READY, null));
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
              findBinding(frozenBindings, call.name()),
              ToolInvocationStatus.FAILED,
              new ToolInvocationError(
                  MODEL_OUTPUT_TRUNCATED,
                  "model output truncated; tool call not executed: " + call.name())));
    }
    return new ModelResponsePlan.ToolBatch(List.copyOf(slots));
  }

  private static ToolCall toToolCall(ProviderToolCall call) {
    // validator 已保证 id/name 非空且 arguments 是合法 JSON object；ToolCall 构造只会做等价归一化。
    return new ToolCall(call.id(), call.name(), call.argumentsJson());
  }

  /** 在冻结 binding 中按 descriptor name 匹配 call name；无匹配返回 null。 */
  private static ToolBinding findBinding(List<ToolBinding> bindings, String toolName) {
    for (ToolBinding binding : bindings) {
      if (binding.descriptor().name().equals(toolName)) {
        return binding;
      }
    }
    return null;
  }

  /** 参数不符合 binding schema 时返回 INVALID_TOOL_ARGUMENTS 错误；合法返回 null。 */
  private static ToolInvocationError validateArguments(ProviderToolCall call, ToolBinding binding) {
    try {
      InputValidator.validate(call.argumentsJson(), binding.descriptor().inputSchema());
      return null;
    } catch (IllegalArgumentException failure) {
      // ToolInvocationError 拒绝 blank message：schema 校验失败 message 可能为空，回退为稳定描述。
      String message = failure.getMessage();
      return new ToolInvocationError(
          INVALID_TOOL_ARGUMENTS,
          message == null || message.isBlank()
              ? "tool arguments do not conform to the tool schema"
              : message);
    }
  }
}
