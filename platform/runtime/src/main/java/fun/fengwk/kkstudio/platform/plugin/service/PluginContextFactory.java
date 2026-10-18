package fun.fengwk.kkstudio.platform.plugin.service;

import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.ScopedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 按 pluginId 装配 scoped {@link PluginContext}：凭据访问只看得到本插件，其余宿主端口按需提供。 */
public final class PluginContextFactory {

  private final PluginCredentialStore credentialStore;
  private final Supplier<PluginResourceGateway> resourceGateway;
  private final Supplier<PluginPromptTasks> promptTasks;
  private final Supplier<PluginPromptResources> promptResources;

  public PluginContextFactory(
      PluginCredentialStore credentialStore,
      Supplier<PluginResourceGateway> resourceGateway,
      Supplier<PluginPromptTasks> promptTasks,
      Supplier<PluginPromptResources> promptResources) {
    this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
    this.resourceGateway = Objects.requireNonNull(resourceGateway, "resourceGateway");
    this.promptTasks = Objects.requireNonNull(promptTasks, "promptTasks");
    this.promptResources = Objects.requireNonNull(promptResources, "promptResources");
  }

  public PluginContext create(String pluginId) {
    return create(new ScopedPluginCredentialAccess(pluginId, credentialStore));
  }

  /** 使用指定凭据访问装配上下文（保存前校验配置时传入候选凭据）。 */
  public PluginContext create(PluginCredentialAccess credentialAccess) {
    return new PluginContext(
        credentialAccess,
        Optional.ofNullable(resourceGateway.get()),
        Optional.ofNullable(promptTasks.get()),
        Optional.ofNullable(promptResources.get()));
  }
}
