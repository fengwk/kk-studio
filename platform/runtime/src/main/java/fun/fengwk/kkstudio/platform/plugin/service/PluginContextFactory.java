package fun.fengwk.kkstudio.platform.plugin.service;

import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.ScopedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 组合根唯一的 scoped {@link PluginContext} 工厂：把权威凭据 store 收窄成某个 pluginId 在其冻结引用上的访问视图，并附加部署是否绑定三个宿主端口。
 *
 * <p>启动构建与配置提交的候选构建都通过它装配上下文，因此两处永远给出同一形状的 PluginContext。
 *
 * <p>三个宿主端口都以 {@link Supplier} 惰性获取：装配期不做任何解析，只有真正创建上下文时才向容器索取，避免把端口的依赖图拉进组合根的装配顺序。
 */
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

  /** 按安装 id 与冻结引用装配 scoped 上下文。 */
  public PluginContext create(String pluginId, PluginCredentialReference reference) {
    PluginCredentialAccess access =
        new ScopedPluginCredentialAccess(pluginId, reference, credentialStore);
    return new PluginContext(
        access,
        Optional.ofNullable(resourceGateway.get()),
        Optional.ofNullable(promptTasks.get()),
        Optional.ofNullable(promptResources.get()));
  }
}
