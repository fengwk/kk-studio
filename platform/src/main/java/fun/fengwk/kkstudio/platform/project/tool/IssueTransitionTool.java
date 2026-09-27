package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.error.AiDomainException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code issue_transition}：Issue Agent turn 唯一的业务写工具，用于请求把当前阶段交接到 workflow 声明的下一阶段。
 *
 * <p>工具是 INTERNAL 贡献：Agent 不能把它声明进自己的工具面，只有 Issue Agent Thread 的 Turn 由平台注入。执行身份必须来自 durable {@link
 * fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext}，全部授权与合法边校验都在 {@link
 * IssueTransitionService} 的事务内按活动 Run 重新核验，工具本身不做任何状态判断。
 *
 * <p>参数 schema 只能声明状态编码的形状，合法目标集合取决于当前阶段的实时 workflow，因此在执行期校验而不是在静态 schema 里枚举。
 */
public final class IssueTransitionTool implements Tool {

  /** 模型可见工具名（工具名只允许字母/数字/下划线/连字符）。 */
  public static final String NAME = "issue_transition";

  /** Contributor 内的稳定贡献名。 */
  public static final String LOCAL_NAME = "issue.transition";

  private static final String FIELD_TO_STATE = "to_state";

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          """
          Request a controlled handoff of the current Issue stage.

          Use it only when the work of the current stage is done. The target must be one of the
          stage's declared next stages; the request is validated against the current stage of the
          live Issue and the currently running run. The platform stores the accepted target on the
          current run and applies it only when the run is safely closed out, so the Issue stage does
          not change while the run is still working. After a handoff has been accepted, do not start
          new business writes; finish the run with a final report of what was done and how it was
          verified.
          """,
          NAME,
          new InputSchema(
              "Handoff request for the current Issue stage.",
              Map.of(
                  FIELD_TO_STATE,
                  new StringSchema(
                      "Target workflow state code of the current stage (for example REVIEW).")),
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
    // 交接只读写 Project 事实，与 Agent 执行环境无关，也不访问 branch custom state。
    return ToolRequirements.none();
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    ToolOutcome outcome;
    try {
      if (request.context() == null || request.context().threadId() == null) {
        throw new IllegalArgumentException(
            NAME + " requires a durable tool execution context with a thread identity");
      }
      String toState = requireToState(request.call().argumentsJson());
      IssueTransitionService.IssueTransitionResult result =
          transitionService.accept(request.context().threadId(), toState);
      outcome = success(request, result);
    } catch (RuntimeException error) {
      outcome = error(request, error);
    }
    listener.onComplete(outcome);
    return CompletedToolExecutionHandle.INSTANCE;
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
    String prefix =
        result.replayed()
            ? "Handoff was already accepted for this run: "
            : "Handoff accepted: this run will close out and the Issue stage will then move ";
    String text =
        prefix
            + result.fromState()
            + " -> "
            + result.toState()
            + ". Do not start new business writes; finish the run with a final report of what was"
            + " done and how it was verified.";
    return ToolOutcome.withoutEffects(result(request, text, false));
  }

  private static ToolOutcome error(ToolExecutionRequest request, RuntimeException error) {
    return ToolOutcome.withoutEffects(result(request, message(error), true));
  }

  /** 只透出确定性业务拒绝与参数错误的文本，模型才能据此修正目标；基础设施级异常（数据不一致、事务冲突、连接失败等）一律收敛为通用错误，绝不回显 内部细节。 */
  private static String message(RuntimeException error) {
    if (error instanceof AiDomainException || error instanceof IllegalArgumentException) {
      String detail = error.getMessage();
      return detail == null || detail.isBlank() ? "issue_transition was rejected" : detail;
    }
    return "issue_transition failed";
  }

  private static ToolResult result(ToolExecutionRequest request, String text, boolean error) {
    return new ToolResult(request.call().id(), List.of(new TextResultContent(text)), error, "{}");
  }
}
