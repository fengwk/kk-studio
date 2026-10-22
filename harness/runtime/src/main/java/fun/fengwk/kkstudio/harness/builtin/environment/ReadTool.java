package fun.fengwk.kkstudio.harness.builtin.environment;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 统一文件与资源读取工具。
 *
 * <p>复用 {@code fs.read} capability 的 schema 与默认超时，声明 {@link
 * ToolRequirements#optionalEnvironment()}。 执行全量委托至注入的 {@link ReadToolExecutor}，由实现方承担 {@code
 * kkstudio:} 稳定地址与可选环境本地路径的路由。
 *
 * <p>read 的模型可见错误统一在 builtin 层补足纠正指引：本地路径且绑定了 Environment 时才可能进入 capability 执行，此时不得声称未执行，
 * 其余（无绑定、空/非文本 path、{@code kkstudio:} URI）都是派发前或纯平台本地读取，声明未执行。
 */
public final class ReadTool implements Tool {

  public static final String NAME = "read";

  private static final String READ_NEXT_ACTION =
      "Use read with an absolute local path or a supported kkstudio: URI";

  private final ReadToolExecutor executor;
  private final EnvironmentCapabilityDescriptor capability;
  private final ToolDescriptor descriptor;

  public ReadTool(ReadToolExecutor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
    this.capability = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ);
    this.descriptor =
        new ToolDescriptor(
            NAME,
            EnvironmentPrompts.load("read.md"),
            NAME,
            capability.inputSchema(),
            ToolSideEffect.READ_ONLY,
            capability.defaultTimeout());
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.optionalEnvironment();
  }

  @Override
  public Duration resolveTimeout(ToolCall call) {
    return EnvironmentCapabilityTimeouts.resolve(capability, call);
  }

  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(EnvironmentCapabilityRenderer.of(EnvironmentCapabilityIds.FS_READ));
  }

  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progress, "progress");
    ExecutionFact executionFact = executionFact(request);
    return ToolOutcome.withoutEffects(
        withGuidance(executor.read(request, progress), executionFact));
  }

  /** read 是只读工具：只有本地路径且绑定了 Environment 时才可能真正进入执行。 */
  private static ExecutionFact executionFact(ToolExecutionRequest request) {
    boolean environmentBound =
        request.context() != null && request.context().environment().isPresent();
    JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
    String path = ToolArguments.text(arguments, "path");
    if (!environmentBound || path == null || path.startsWith("kkstudio:")) {
      return ExecutionFact.NOT_EXECUTED;
    }
    return ExecutionFact.UNCERTAIN;
  }

  /** 只包装终止错误结果：保留原 toolCallId/details，替换为三段式纠正文案。 */
  private static ToolResult withGuidance(ToolResult result, ExecutionFact executionFact) {
    if (!result.error()) {
      return result;
    }
    String whatFailed = firstText(result);
    if (ToolErrorGuidance.isGuided(whatFailed)) {
      // 已带执行事实（例如 daemon capability 已包装）：不重复包装。
      return result;
    }
    return new ToolResult(
        result.toolCallId(),
        List.of(
            new TextResultContent(
                ToolErrorGuidance.message(whatFailed, executionFact, READ_NEXT_ACTION))),
        true,
        result.detailsJson());
  }

  private static String firstText(ToolResult result) {
    for (var content : result.contents()) {
      if (content instanceof TextResultContent text && !text.text().isBlank()) {
        return text.text();
      }
    }
    return "read failed";
  }
}
