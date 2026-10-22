package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 已安装 Plugin 的安全投影。
 *
 * <p>只包含安装身份、配置投影与认证状态：密文、access token、加密密钥、client identity 与 callback URL 都不在这里，也不出现在任何响应中。 秘密只以
 * {@link PluginConfigurationDTO#getSecrets()} 的说明与布尔出现。
 *
 * <p>配置身份与运行状态分开：{@code desired*} 是权威 catalog 的当前坐标，{@code applied*} 是本节点实际构建运行实例时的坐标；两者不一致时 {@code
 * applicationError} 如实说明「已保存但尚未应用」，绝不把保存当发布。
 */
@Data
public class PluginDTO {

  /** Plugin 全局唯一不可变安装身份（canonical 小写点划线标识符），与 classpath 中的 StudioPlugin.pluginId 对齐。 */
  private String pluginId;

  /** 展示名。 */
  private String name;

  /** Plugin 版本。 */
  private String version;

  /** 认证交互类型；Plugin 不提供认证时为 null。 */
  private @Nullable PluginAuthKindDTO authKind;

  /**
   * 认证状态投影，取 {@code NOT_CONNECTED / CONNECTED / REFRESH_FAILED / REFRESH_UNCERTAIN /
   * REAUTH_REQUIRED}：{@code NOT_CONNECTED} 表示尚无可用认证。
   */
  private @Nullable String status;

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

  /** 权威 catalog 当前 desired 配置的普通值、schema 与秘密录入状态投影；本节点实际应用的坐标另见 {@code applied*}。 */
  private PluginConfigurationDTO configuration;

  /** 权威 catalog 中该插件的当前配置修订（canonical 非负十进制字符串）。 */
  private String desiredRevision;

  /** 本节点运行实例构建时的配置修订（canonical 非负十进制字符串）。 */
  private @Nullable String appliedRevision;

  /** 本节点已应用实例是否声明显式只读连接检查能力；按已应用实例的能力如实投影，绝不按 pluginId 硬编码。 */
  private boolean connectionCheckSupported;

  /** 本节点已应用快照中该 Plugin 真实冻结的模型可见工具名（按冻结目录顺序）；没有贡献时为空列表。 */
  private List<String> harnessToolNames;

  /** 本节点已应用快照中该 Plugin 真实冻结的 Canvas 函数名（按冻结目录顺序）；没有贡献时为空列表。 */
  private List<String> canvasFunctionNames;

  /** 已保存但尚未在本节点应用时的固定去敏说明；已应用时为 null。 */
  private @Nullable String applicationError;
}
