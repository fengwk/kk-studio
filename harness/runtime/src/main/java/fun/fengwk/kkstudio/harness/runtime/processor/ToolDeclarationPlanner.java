package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 纯工具声明规划器。
 *
 * <p>它只按冻结 binding 查找名称并用 {@link ToolCall#validateFor} 做 schema 兼容校验；返回的 {@link Declaration} 始终保留
 * Provider 原始 canonical arguments，绝不把归一化参数写回历史。未知工具没有 binding，只能形成 immediate FAILED 槽位。
 */
public final class ToolDeclarationPlanner {

  public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";
  public static final String INVALID_TOOL_ARGUMENTS = "INVALID_TOOL_ARGUMENTS";

  /** 一个已规划、但尚未写入 ToolInvocation 的调用；invalid/unknown 使用 {@code FAILED}。 */
  public record Declaration(
      ToolCall call, ToolBinding binding, boolean valid, ToolInvocationError error) {

    public Declaration {
      call = Objects.requireNonNull(call, "call");
      if (valid) {
        if (binding == null || error != null) {
          throw new IllegalArgumentException("a valid declaration requires binding and no error");
        }
      } else if (error == null) {
        throw new IllegalArgumentException("an invalid declaration requires an error");
      }
    }
  }

  public Declaration plan(ToolCall rawCall, List<ToolBinding> frozenBindings) {
    Objects.requireNonNull(rawCall, "rawCall");
    Objects.requireNonNull(frozenBindings, "frozenBindings");
    ToolBinding binding = findBinding(frozenBindings, rawCall.toolName());
    if (binding == null) {
      return new Declaration(rawCall, null, false, unknownTool(rawCall.toolName(), frozenBindings));
    }
    try {
      rawCall.validateFor(binding.descriptor());
    } catch (IllegalArgumentException failure) {
      return new Declaration(rawCall, binding, false, invalidArguments(failure));
    }
    return new Declaration(rawCall, binding, true, null);
  }

  public static ToolBinding findBinding(List<ToolBinding> bindings, String toolName) {
    for (ToolBinding binding : bindings) {
      if (binding.descriptor().name().equals(toolName)) {
        return binding;
      }
    }
    return null;
  }

  /** unknown call 的模型可见错误：只列出冻结 spec 允许的工具，并明确未执行与下一步。 */
  public static ToolInvocationError unknownTool(String toolName, List<ToolBinding> frozenBindings) {
    TreeSet<String> allowed = new TreeSet<>();
    for (ToolBinding binding : frozenBindings) {
      allowed.add(binding.descriptor().name());
    }
    String allowedNames = allowed.isEmpty() ? "none" : String.join(", ", allowed);
    return new ToolInvocationError(
        UNKNOWN_TOOL,
        ToolErrorGuidance.message(
            "unknown tool \"" + toolName + "\"; allowed tools: " + allowedNames,
            ExecutionFact.NOT_EXECUTED,
            "Call one of the allowed tools"));
  }

  public static ToolInvocationError invalidArguments(IllegalArgumentException failure) {
    String message = failure.getMessage();
    String whatFailed =
        message == null || message.isBlank()
            ? "tool arguments do not conform to the tool schema"
            : message;
    return new ToolInvocationError(
        INVALID_TOOL_ARGUMENTS,
        ToolErrorGuidance.message(
            whatFailed,
            ExecutionFact.NOT_EXECUTED,
            "Correct the arguments to match the tool schema, then call the tool again"));
  }
}
