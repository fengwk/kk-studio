package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link StudioPlugin#configure(PluginConfig, PluginContext)} 的宿主上下文。
 *
 * <p>只暴露两件事：本 Plugin 的 scoped {@link PluginCredentialAccess}，以及可选的既有任务导向 {@link
 * PluginResourceGateway}。Plugin 拿不到 credential store、repository、主密钥或其它 Plugin 的凭据。
 *
 * @param credentialAccess 绑定安装 pluginId 的凭据访问视图
 * @param resourceGateway 部署是否绑定了 Plugin 资源网关；缺失表示涉及资源的调用必须确定性失败
 */
public record PluginContext(
    PluginCredentialAccess credentialAccess, Optional<PluginResourceGateway> resourceGateway) {

  public PluginContext {
    Objects.requireNonNull(credentialAccess, "credentialAccess");
    Objects.requireNonNull(resourceGateway, "resourceGateway");
  }
}
