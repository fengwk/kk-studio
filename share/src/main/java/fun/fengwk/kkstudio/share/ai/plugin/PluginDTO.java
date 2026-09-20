package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;

import java.time.Instant;

/**
 * 已安装 Plugin 的安全投影。
 *
 * <p>只包含安装身份与认证状态：密文、access token、加密密钥、client identity 与 callback URL 都不在这里，也不出现在任何响应中。
 */
@Data
public class PluginDTO {

  /** Plugin 全局唯一不可变安装身份（canonical 小写点划线标识符），与 classpath 中的 StudioPlugin.pluginId 对齐。 */
  private String pluginId;

  /** 展示名。 */
  private String name;

  /** Plugin 版本。 */
  private String version;

  /** 认证交互类型；Plugin 不提供交互认证时为 null。 */
  private PluginAuthKindDTO authKind;

  /**
   * 认证状态投影，取 {@code NOT_CONNECTED / KEY_UNAVAILABLE / CONNECTED / REFRESH_FAILED /
   * REFRESH_UNCERTAIN / REAUTH_REQUIRED}：{@code NOT_CONNECTED} 表示尚无凭据行，{@code KEY_UNAVAILABLE}
   * 表示部署主密钥不可用（读写 fail closed）。
   */
  private String status;

  /** 当前凭据的 region；无凭据时为 null。 */
  private String region;

  /** access token 到期时间；无凭据时为 null。 */
  private Instant expiresAt;

  /** 下一次自动刷新时间；无凭据时为 null。 */
  private Instant nextRefreshAt;

  /** 最近一次成功刷新时间；从未成功刷新时为 null。 */
  private Instant lastRefreshedAt;

  /** 最近一次刷新失败的有界去敏错误；成功时为 null。 */
  private String lastRefreshError;
}
