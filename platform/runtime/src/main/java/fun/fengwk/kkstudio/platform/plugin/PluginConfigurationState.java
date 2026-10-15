package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;

import java.util.Objects;

/**
 * 构建目录所需的配置身份：该 Plugin 的普通配置加它在本次 epoch 的冻结凭据引用。
 *
 * <p>它不是额外的注册器，只是把「普通值」与「秘密属于哪一代、哪个认证上下文」放在一起进入快照构建：{@link
 * fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot} 与 {@link
 * fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap} 用它装配 scoped 上下文与
 * manifest/history。
 *
 * @param config 不可变普通配置
 * @param credentialReference 该配置 epoch 的唯一凭据身份引用（即使当前无凭据也存在）
 */
public record PluginConfigurationState(
    PluginConfig config, PluginCredentialReference credentialReference) {

  public PluginConfigurationState {
    config = Objects.requireNonNull(config, "config");
    credentialReference = Objects.requireNonNull(credentialReference, "credentialReference");
  }
}
