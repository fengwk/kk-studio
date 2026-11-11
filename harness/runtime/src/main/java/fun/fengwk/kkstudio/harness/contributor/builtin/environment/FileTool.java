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
import fun.fengwk.kkstudio.harness.spi.VfsGateway;
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
 * <p>工具层面不固定执行位置，声明 {@link ToolRequirements#routed()}：每次调用的 {@code path} 以 {@code vfs:} 开头时走
 * Platform VFS（{@link VfsGateway}，不绑定、不等待 Environment），否则走分支绑定的 {@link BoundEnvironment} 上同名
 * capability。
 *
 * <p>无 Environment 绑定时传入非 {@code vfs:} 路径是派发前的确定性拒绝：返回「未执行」，并提示改用 {@code vfs:} 路径。错误文案统一由 builtin
 * 层补足纠正指引：环境执行失败的文案若尚未带执行事实，则声明结果不确定，绝不声称未执行。
 */
public final class FileTool implements Tool {

  /** 统一读取工具的稳定模型可见名：平台按它识别读取结果的内联上限。 */
  public static final String READ_NAME = "read";

  /** 五个文件工具在一次调用上的 VFS 操作。 */
  public enum Operation {
    READ,
    WRITE,
    EDIT,
    FIND,
    GREP
  }

  private final String name;
  private final Operation operation;
  private final VfsGateway vfsGateway;
  private final EnvironmentCapabilityDescriptor capability;
  private final ToolDescriptor descriptor;

  public FileTool(
      String name,
      Operation operation,
      String promptResource,
      EnvironmentCapabilityId capabilityId,
      ToolSideEffect sideEffect,
      VfsGateway vfsGateway) {
    this.name = Objects.requireNonNull(name, "name");
    this.operation = Objects.requireNonNull(operation, "operation");
    this.vfsGateway = Objects.requireNonNull(vfsGateway, "vfsGateway");
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

  /** 本工具对应的 VFS 操作，平台据此识别 edit 审批等按操作区分的执行契约。 */
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

    if (path != null && path.startsWith("vfs:")) {
      return new ToolOutcome(vfs(request, progress));
    }

    Optional<BoundEnvironment> environment = environment(request);
    if (environment.isEmpty()) {
      return new ToolOutcome(noEnvironment(request));
    }
    return new ToolOutcome(withGuidance(environment.get().execute(capability, request, progress)));
  }

  private ToolResult vfs(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException {
    return switch (operation) {
      case READ -> vfsGateway.read(request, progress);
      case WRITE -> vfsGateway.write(request, progress);
      case EDIT -> vfsGateway.edit(request, progress);
      case FIND -> vfsGateway.find(request, progress);
      case GREP -> vfsGateway.grep(request, progress);
    };
  }

  private static Optional<BoundEnvironment> environment(ToolExecutionRequest request) {
    ToolExecutionContext context = request.context();
    return context == null ? Optional.empty() : context.environment();
  }

  /** 无环境时非 {@code vfs:} 路径不派发：明确未执行，并给出两条可执行路径。 */
  private ToolResult noEnvironment(ToolExecutionRequest request) {
    String sessionHint = sessionHint(request);
    return new ToolResult(
        request.call().id(),
        List.of(
            new TextResultContent(
                ToolErrorGuidance.message(
                    "No Environment is bound in the execution context",
                    ExecutionFact.NOT_EXECUTED,
                    "Use a vfs:/sessions/"
                        + sessionHint
                        + "/ path, or select an Environment for the branch and call "
                        + name
                        + " again"))),
        true,
        "{}");
  }

  private static String sessionHint(ToolExecutionRequest request) {
    ToolExecutionContext context = request.context();
    return context == null ? "<sessionId>" : context.rootSessionId().toString();
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
