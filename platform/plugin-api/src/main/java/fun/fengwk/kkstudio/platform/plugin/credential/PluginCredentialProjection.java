package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;

/**
 * 管理面的凭据安全投影：只有状态、非秘密 region 路由元数据与时间，没有密文、token、lease、version 或 client identity。
 *
 * @param status 状态投影；无凭据行为 {@code NOT_CONNECTED}，主密钥不可用或密文无法解密为 {@code KEY_UNAVAILABLE}
 * @param region 凭据 region；无凭据或密钥不可用时为 null
 * @param expiresAt access token 失效时刻；不可用时为 null
 * @param nextRefreshAt 下一次自动刷新时刻；不可用时为 null
 * @param lastRefreshedAt 最近一次成功刷新时刻；从未成功刷新时为 null
 * @param lastRefreshError 最近一次刷新失败的有界去敏错误；成功时为 null
 */
public record PluginCredentialProjection(
    PluginCredentialStatus status,
    String region,
    Instant expiresAt,
    Instant nextRefreshAt,
    Instant lastRefreshedAt,
    String lastRefreshError) {

  public static PluginCredentialProjection notConnected() {
    return new PluginCredentialProjection(
        PluginCredentialStatus.NOT_CONNECTED, null, null, null, null, null);
  }

  /** 主密钥不可用或密文不可解时的投影；不透出任何行内元数据。 */
  public static PluginCredentialProjection keyUnavailable() {
    return new PluginCredentialProjection(
        PluginCredentialStatus.KEY_UNAVAILABLE, null, null, null, null, null);
  }

  /** 投影是否为「已持有可用凭据」状态。 */
  public boolean connected() {
    return status == PluginCredentialStatus.CONNECTED;
  }
}
