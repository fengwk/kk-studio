package fun.fengwk.kkstudio.harness.builtin.environment;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionContext;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;

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
    if (!Objects.equals(descriptor.timeout(), capability.timeout())) {
      throw new IllegalArgumentException("descriptor timeout does not match capability timeout");
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
              List.of(new TextToolContent("No environment bound in execution context")),
              true,
              "{}"));
      return CompletedToolExecutionHandle.INSTANCE;
    }
    return environment.get().execute(capability, request, listener);
  }
}
