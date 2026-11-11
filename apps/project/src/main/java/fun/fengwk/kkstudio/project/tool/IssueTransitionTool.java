package fun.fengwk.kkstudio.project.tool;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code issue_transition}：Issue Agent 唯一显式选择的业务写工具，用于请求把当前阶段交接到 workflow 声明的下一阶段。
 *
 * <p>工具是 SELECTABLE 贡献：由 Issue Agent 的配置显式声明，不按 Thread owner 隐式注入。执行身份只来自 durable {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext}：调用 Thread 与该调用的 assistant 入口都由
 * Harness 冻结，模型参数里没有、也不接受任何 Run 身份。全部授权、合法边与调用归属校验都在 {@link IssueTransitionService} 的事务内按当前活动 Run
 * 重新核验，工具本身不做任何状态判断。
 *
 * <p>参数 schema 只能声明状态编码的形状，合法目标集合取决于当前阶段的实时 workflow，因此在执行期校验而不是在静态 schema 里枚举。
 */
public final class IssueTransitionTool implements Tool {

  /** 模型可见工具名（工具名只允许字母/数字/下划线/连字符）。 */
  public static final String NAME = "issue_transition";

  /** Contributor 内的稳定贡献名。 */
  public static final String LOCAL_NAME = "issue.transition";

  private static final String FIELD_TO_STATE = "to_state";
  private static final String ROOT = "fun/fengwk/kkstudio/project/prompts/";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          PromptRenderer.render(ROOT + "tools/issue-transition.md", Map.of()),
          NAME,
          new InputSchema(
              PromptRenderer.render(ROOT + "tools/issue-transition-request.md", Map.of())
                  .stripTrailing(),
              Map.of(
                  FIELD_TO_STATE,
                  new StringSchema(
                      PromptRenderer.render(ROOT + "tools/issue-transition-target.md", Map.of())
                          .stripTrailing())),
              Set.of(FIELD_TO_STATE),
              false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ofSeconds(30));

  private final IssueTransitionService transitionService;

  public IssueTransitionTool(IssueTransitionService transitionService) {
    this.transitionService = Objects.requireNonNull(transitionService, "transitionService");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolRequirements requirements() {
    // 交接只读写 Project 事实：执行身份来自 Harness 冻结的执行上下文，不读取任何 branch custom state。
    return ToolRequirements.none();
  }

  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
    Objects.requireNonNull(request, "request");
    try {
      ToolExecutionContext context = request.context();
      if (context == null) {
        throw new IllegalArgumentException(NAME + " requires a durable tool execution context");
      }
      String toState = requireToState(request.call().argumentsJson());
      IssueTransitionService.IssueTransitionResult result =
          transitionService.accept(context.threadId(), context.assistantEntryId(), toState);
      return success(request, result);
    } catch (RuntimeException error) {
      return error(request, error);
    }
  }

  private static String requireToState(String argumentsJson) {
    JsonNode arguments = JsonValues.readTree(argumentsJson);
    JsonNode value = arguments.get(FIELD_TO_STATE);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(FIELD_TO_STATE + " is required and must be a state code");
    }
    return value.textValue().strip();
  }

  private static ToolOutcome success(
      ToolExecutionRequest request, IssueTransitionService.IssueTransitionResult result) {
    String text =
        PromptRenderer.render(
                ROOT + "messages/issue-transition-accepted.md",
                Map.of(
                    "replayed",
                    result.replayed(),
                    "from",
                    result.fromState(),
                    "to",
                    result.toState()))
            .stripTrailing();
    return new ToolOutcome(result(request, text, false));
  }

  private static ToolOutcome error(ToolExecutionRequest request, RuntimeException error) {
    return new ToolOutcome(result(request, message(error), true));
  }

  /** 只透出确定性业务拒绝与参数错误的文本，模型才能据此修正目标；基础设施级异常（数据不一致、事务冲突、连接失败等）一律收敛为通用错误，绝不回显 内部细节。 */
  private static String message(RuntimeException error) {
    if (error instanceof DomainException || error instanceof IllegalArgumentException) {
      String detail = error.getMessage();
      return detail == null || detail.isBlank() ? "issue_transition was rejected" : detail;
    }
    return "issue_transition failed";
  }

  private static ToolResult result(ToolExecutionRequest request, String text, boolean error) {
    return new ToolResult(request.call().id(), List.of(new TextResultContent(text)), error, "{}");
  }
}
