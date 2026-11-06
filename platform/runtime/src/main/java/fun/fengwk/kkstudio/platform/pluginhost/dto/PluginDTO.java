package fun.fengwk.kkstudio.platform.pluginhost.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.List;

/**
 * 已安装 Plugin 的安全投影。
 *
 * <p>只包含安装身份、配置投影与认证状态：密文、access token、加密密钥、client identity 与 callback URL 都不在这里，也不出现在任何响应中。
 *
 * <p>{@code pluginVersion} 是插件自身的代码版本；配置并发版本只由 {@code configuration.version} 与 {@code
 * appliedVersion} 表达。{@code configuration.version} 是权威 catalog 的当前坐标，{@code appliedVersion}
 * 是本节点实际构建运行实例时的坐标；两者不一致时 {@code applicationError} 如实说明「已保存但尚未应用」，绝不把保存当发布。
 */
@Data
public class PluginDTO {

  /** Plugin 全局唯一不可变安装身份，与 classpath 中的 {@code Plugin.pluginId} 对齐。 */
  private String pluginId;

  /** 展示名。 */
  private String name;

  /** 插件自身的代码版本，与配置并发版本无关。 */
  private String pluginVersion;

  /** 认证交互类型；Plugin 不提供认证时为 null。 */
  private @Nullable PluginAuthKindDTO authKind;

  /** 认证状态投影；Plugin 不提供认证时为 null。 */
  private @Nullable PluginCredentialStatus status;

  /** 当前凭据的 region；无凭据或静态密钥时为 null。 */
  private @Nullable String region;

  /** access token 到期时间；无凭据或静态密钥时为 null。 */
  private @Nullable Instant expiresAt;

  /** 下一次自动刷新时间；无凭据或静态密钥时为 null。 */
  private @Nullable Instant nextRefreshAt;

  /** 最近一次成功刷新时间；从未成功刷新时为 null。 */
  private @Nullable Instant lastRefreshedAt;

  /** 最近一次刷新失败的有界去敏错误；成功时为 null。 */
  private @Nullable String lastRefreshError;

  /** 权威 catalog 当前 desired 配置的普通值、schema 与秘密录入状态投影。 */
  private PluginConfigurationDTO configuration;

  /** 本节点运行实例构建时的配置版本；wire 上为 canonical 非负十进制字符串。 */
  private @Nullable Long appliedVersion;

  /** 本节点已应用实例是否声明显式只读连接检查能力。 */
  private boolean connectionCheckSupported;

  /** 本节点已应用快照中该 Plugin 真实冻结的模型可见工具名；没有贡献时为空列表。 */
  private List<String> harnessToolNames;

  /** 本节点已应用快照中该 Plugin 真实冻结的 Canvas 函数名；没有贡献时为空列表。 */
  private List<String> canvasFunctionNames;

  /** 已保存但尚未在本节点应用时的固定去敏说明；已应用时为 null。 */
  private @Nullable String applicationError;
}
