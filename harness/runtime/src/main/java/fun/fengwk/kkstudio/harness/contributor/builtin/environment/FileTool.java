package fun.fengwk.kkstudio.harness.contributor.builtin.environment;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.spi.ResourceReadGateway;
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
 * 逐调用路由的文件工具：{@code read}、{@code write}、{@code edit}、{@code grep}、{@code find}。
 *
 * <p>工具层面不固定执行位置，声明 {@link ToolRequirements#routed()}：{@code read} 的 {@code resource://} 路径由
 * Platform 读取，其他路径交给绑定的 {@link BoundEnvironment}。
 *
 * <p>无 Environment 时本地路径返回明确的 needs an Environment 错误。错误文案统一由 builtin 层补足纠正指引。
 */
public final class FileTool implements Tool {

  /** 统一读取工具的稳定模型可见名：平台按它识别读取结果的内联上限。 */
  public static final String READ_NAME = "read";

  /** 五个文件工具各自调用的 Environment capability 操作。 */
  public enum Operation {
    READ,
    WRITE,
    EDIT,
    FIND,
    GREP
  }

  private final String name;
  private final Operation operation;
  private final ResourceReadGateway resourceReadGateway;
  private final EnvironmentCapabilityDescriptor capability;
  private final ToolDescriptor descriptor;

  public FileTool(
      String name,
      Operation operation,
      String promptResource,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect,
      ResourceReadGateway resourceReadGateway) {
    this.name = Objects.requireNonNull(name, "name");
    this.operation = Objects.requireNonNull(operation, "operation");
    this.resourceReadGateway = Objects.requireNonNull(resourceReadGateway, "resourceReadGateway");
    this.capability = EnvironmentCapabilityCatalog.require(capabilityId);
    this.descriptor =
        new ToolDescriptor(
            name,
            EnvironmentPrompts.load(promptResource),
            name,
            capability.inputSchema(),
            sideEffect,
            capability.defaultTimeout());
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  /** 本工具对应的 Environment capability 操作。 */
  public Operation operation() {
    return operation;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.routed();
  }

  @Override
  public Duration resolveTimeout(ToolCall call) {
    return EnvironmentCapabilityTimeouts.resolve(capability, call);
  }

  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(EnvironmentCapabilityRenderer.of(capability.id()));
  }

  @Override
  public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progress, "progress");
    JsonNode arguments = ToolArguments.parse(request.call().argumentsJson());
    String path = ToolArguments.text(arguments, "path");

    if (path != null && path.startsWith("resource://")) {
      if (operation != Operation.READ) {
        return new ToolOutcome(
            ToolResult.error(request.call().id(), "resource URIs are read-only"));
      }
      return new ToolOutcome(resourceReadGateway.read(request, progress));
    }

    Optional<BoundEnvironment> environment = environment(request);
    if (environment.isEmpty()) {
      return new ToolOutcome(noEnvironment(request));
    }
    return new ToolOutcome(withGuidance(environment.get().execute(capability, request, progress)));
  }

  private static Optional<BoundEnvironment> environment(ToolExecutionRequest request) {
    ToolExecutionContext context = request.context();
    return context == null ? Optional.empty() : context.environment();
  }

  /** 无环境时本地路径不派发，返回可理解的明确错误。 */
  private ToolResult noEnvironment(ToolExecutionRequest request) {
    return new ToolResult(
        request.call().id(),
        List.of(
            new TextResultContent(
                ToolErrorGuidance.message(
                    "This operation needs an Environment",
                    ExecutionFact.NOT_EXECUTED,
                    "Select an Environment for the branch and call " + name + " again"))),
        true,
        "{}");
  }

  /** 只包装终止错误结果：保留原 toolCallId/details，替换为三段式纠正文案。 */
  private ToolResult withGuidance(ToolResult result) {
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
                ToolErrorGuidance.message(whatFailed, ExecutionFact.UNCERTAIN, nextAction()))),
        true,
        result.detailsJson());
  }

  private String nextAction() {
    return "Check the path under the selected Environment, then call " + name + " again";
  }

  private String firstText(ToolResult result) {
    for (var content : result.contents()) {
      if (content instanceof TextResultContent text && !text.text().isBlank()) {
        return text.text();
      }
    }
    return name + " failed";
  }
}
