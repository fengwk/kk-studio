package fun.fengwk.kkstudio.platform.plugin.catalog;

import fun.fengwk.kkstudio.harness.builtin.environment.ReadToolExecutor;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentRunner;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskAcceptance;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskRequest;
import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptContent;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 离线装配用的非成功执行端口：read/subagent、凭据、资源网关与两个 Prompt 端口的所有操作都直接以固定安全异常终止——端口本身既不发出请求也不改变状态。
 *
 * <p>六个端口必须存在，而不是 {@code Optional.empty}：线上启用中的能力集合不能因为离线而消失，否则两域指纹会与真实目录不同。
 * 本类无状态，由离线装配方显式注册，不参与常驻运行时的自动装配。
 *
 * <p>这不是 sandbox：Plugin 自己仍可创建客户端，本组件只保证宿主端口不被执行。
 */
public final class OfflinePluginHost
    implements ReadToolExecutor,
        SubagentRunner,
        PluginCredentialStore,
        PluginResourceGateway,
        PluginPromptTasks,
        PluginPromptResources {

  @Override
  public ToolExecutionHandle read(ToolExecutionRequest request, ToolExecutionListener listener) {
    throw unavailable();
  }

  @Override
  public SubagentTaskAcceptance accept(SubagentTaskRequest request) {
    throw unavailable();
  }

  @Override
  public PluginCredentialProjection projection(String pluginId) {
    throw unavailable();
  }

  @Override
  public PluginCredentialSnapshot resolve(PluginCredentialReference reference) {
    throw unavailable();
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    throw unavailable();
  }

  @Override
  public URI resolveSessionResource(UUID threadId, String resourceUri) {
    throw unavailable();
  }

  @Override
  public ResourceRef stageRemoteMedia(
      URI remoteUri, PluginMediaFamily family, String name, Runnable beforeRequest) {
    throw unavailable();
  }

  @Override
  public UUID submit(String agentName, String systemText, List<PluginPromptContent> contents) {
    throw unavailable();
  }

  @Override
  public String await(UUID taskId, Duration timeout, BooleanSupplier continueWaiting) {
    throw unavailable();
  }

  @Override
  public void stop(UUID taskId) {
    throw unavailable();
  }

  @Override
  public PluginPromptResources.StagedResource stage(
      String filename, String mediaType, InputStream input, long expectedSize) {
    throw unavailable();
  }

  @Override
  public void discard(PluginPromptResources.StagedResource resource) {
    throw unavailable();
  }

  private static HostOperationException unavailable() {
    return new HostOperationException();
  }

  /** 离线装配期间的宿主操作固定安全异常：绝不携带底层 cause、输入或任何凭据/媒体片段。 */
  public static final class HostOperationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    HostOperationException() {
      super("offline plugin catalog assembly cannot execute host operations");
    }
  }
}
