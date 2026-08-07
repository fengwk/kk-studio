package fun.fengwk.kkstudio.harness.tool.remote;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.util.Objects;

/**
 * 传输无关的远程 Tool 代理；与本地 {@link Tool} 共享同一异步执行 API。
 *
 * <p>本类只委托 {@link RemoteToolTransport} 发送 INVOKE/CANCEL 并把远程回调映射为 listener 事件，不依赖
 * runtime/core/daemon/Spring/DB。路由只使用 canonical {@link EnvironmentName}。
 */
public final class RemoteTool implements Tool {

  private final ToolDescriptor descriptor;
  private final EnvironmentName environmentName;
  private final RemoteToolTransport transport;

  public RemoteTool(
      ToolDescriptor descriptor, EnvironmentName environmentName, RemoteToolTransport transport) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.environmentName = Objects.requireNonNull(environmentName, "environmentName");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public EnvironmentName environmentName() {
    return environmentName;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException(
          "request descriptor does not match remote tool descriptor");
    }
    return transport.invoke(environmentName, request, listener);
  }
}
