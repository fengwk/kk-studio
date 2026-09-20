package fun.fengwk.kkstudio.harness.builtin.environment;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 委托至 BoundEnvironment 执行的具体环境能力 Tool 实现。 */
public final class EnvironmentCapabilityTool implements Tool {

  private final ToolDescriptor descriptor;
  private final EnvironmentCapabilityDescriptor capability;

  public EnvironmentCapabilityTool(
      ToolDescriptor descriptor, EnvironmentCapabilityDescriptor capability) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.capability = Objects.requireNonNull(capability, "capability");
    if (!Objects.equals(descriptor.inputSchema(), capability.inputSchema())) {
      throw new IllegalArgumentException(
          "descriptor inputSchema does not match capability inputSchema");
    }
    if (!Objects.equals(descriptor.defaultTimeout(), capability.defaultTimeout())) {
      throw new IllegalArgumentException(
          "descriptor defaultTimeout does not match capability defaultTimeout");
    }
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public EnvironmentCapabilityDescriptor capability() {
    return capability;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.environment();
  }

  /**
   * 只有本工具声明了 arguments 级超时契约：归一化 arguments 携带显式 {@code timeout_seconds} 时严格使用该值，缺省时使用 capability
   * 的默认超时。显式值可以比默认值更短或更长，两者不取最小值，也不存在产品上限。
   *
   * <p>{@code timeout_seconds} 按 schema 声明必须是正数：非正数既不是合法覆盖，也不表示「无 deadline」，而是 fail closed 的非法请求，由
   * Gateway 收敛为确定性的 {@code INVALID_REQUEST} 失败。
   */
  @Override
  public Duration resolveTimeout(ToolCall call) {
    return EnvironmentCapabilityTimeouts.resolve(capability, call);
  }

  /**
   * Environment 能力的历史动作保留目标、{@code workdir}、Environment 名与改变解释的匹配标志，省略 timeout、limit、分页与列偏移等执行预算或
   * 结果窗口参数。
   */
  @Override
  public Optional<ToolHistoryRenderer> historyRenderer() {
    return Optional.of(EnvironmentCapabilityRenderer.of(capability.id()));
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    ToolExecutionContext context = request.context();
    Optional<BoundEnvironment> environment =
        context == null ? Optional.empty() : context.environment();
    if (environment.isEmpty()) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextResultContent("No environment bound in execution context")),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    return environment.get().execute(capability, request, listener);
  }
}
