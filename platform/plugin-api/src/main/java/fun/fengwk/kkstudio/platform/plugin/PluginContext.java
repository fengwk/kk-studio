package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link StudioPlugin#configure(PluginConfig, PluginContext)} 的宿主上下文。
 *
 * <p>只暴露本 Plugin 的 scoped {@link PluginCredentialAccess}，以及部署可选绑定的既有资源网关与 Prompt 窄端口。Plugin 拿不到
 * credential store、repository、主密钥、其它 Plugin 的凭据，也拿不到 Harness 消息、存储上传行或任务内部身份。
 *
 * <p>四个字段都不可为 null；{@code Optional.empty()} 表示该部署未绑定对应端口，涉及该能力的调用必须确定性失败。
 *
 * @param credentialAccess 绑定安装 pluginId 的凭据访问视图
 * @param resourceGateway 部署是否绑定了 Plugin 资源网关
 * @param promptTasks 部署是否绑定了 Prompt 任务端口
 * @param promptResources 部署是否绑定了 Prompt 媒体接受端口
 */
public record PluginContext(
    PluginCredentialAccess credentialAccess,
    Optional<PluginResourceGateway> resourceGateway,
    Optional<PluginPromptTasks> promptTasks,
    Optional<PluginPromptResources> promptResources) {

  public PluginContext {
    Objects.requireNonNull(credentialAccess, "credentialAccess");
    Objects.requireNonNull(resourceGateway, "resourceGateway");
    Objects.requireNonNull(promptTasks, "promptTasks");
    Objects.requireNonNull(promptResources, "promptResources");
  }
}
