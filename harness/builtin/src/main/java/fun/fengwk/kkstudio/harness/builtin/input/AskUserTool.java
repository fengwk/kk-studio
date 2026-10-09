package fun.fengwk.kkstudio.harness.builtin.input;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Objects;

/**
 * 内置人工输入工具 {@code ask_user} 的模型侧契约：问卷形状与「等待用户作答」语义。
 *
 * <p>它不是可执行动作，而是等待：Runtime 按冻结 provenance（contributor {@code builtin} 且 descriptor name {@code
 * ask_user}）识别本工具，在 READY 边界把 ToolInvocation 冻结为 WAITING_INPUT，因此既不进入权限审批，也不进入 Gateway/preflight
 * 派发。工具本身因此声明 {@link ToolRequirements#none()}、{@link ToolSideEffect#READ_ONLY} 且没有执行 deadline。
 *
 * <p>本类只拥有模型可见的 descriptor（说明与问卷 schema）。问卷原文随 Assistant ToolCall 冻结，答案由用户在会话界面提交后由 Runtime 校验并物化为
 * ToolResult：本工具不解析问卷、不保存答案，也绝不把任何结果伪装成用户输入。
 */
public final class AskUserTool implements Tool {

  /** 模型可见工具名；Runtime 的 provenance 判据之一由它构成。 */
  public static final String NAME = "ask_user";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          AskUserPrompts.instructions(),
          NAME,
          AskUserPrompts.inputSchema(),
          ToolSideEffect.READ_ONLY,
          Duration.ZERO);

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  /**
   * 防御性执行：Runtime 已在 READY 边界把内置 {@code ask_user} 冻结为 WAITING_INPUT，正常路径绝不会 dispatch 到本方法。
   *
   * <p>若仍被调用（provenance 未被识别或调用方绕过运行时），则以确定性错误收敛：绝不伪造答案，也不回显问卷原文。
   */
  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    listener.onComplete(
        ToolResult.error(
            request.call().id(),
            ToolErrorGuidance.message(
                "ask_user waits for the user's answer in the conversation surface and is never"
                    + " executed as a tool",
                ExecutionFact.NOT_EXECUTED,
                "Submit the questionnaire as a frozen call and wait for the user's answer")));
    return CompletedToolExecutionHandle.INSTANCE;
  }
}
