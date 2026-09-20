package fun.fengwk.kkstudio.harness.builtin.environment;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一文件与资源读取工具。
 *
 * <p>复用 {@code fs.read} capability 的 schema 与默认超时，声明 {@link
 * ToolRequirements#optionalEnvironment()}。 执行全量委托至注入的 {@link ReadToolExecutor}，由实现方承担 {@code
 * kkstudio:} 稳定地址与可选环境本地路径的路由。
 */
public final class ReadTool implements Tool {

  public static final String NAME = "read";

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
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    return executor.read(request, listener);
  }
}
