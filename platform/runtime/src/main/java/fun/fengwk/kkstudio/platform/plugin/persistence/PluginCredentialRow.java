package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * {@code plugin_credential} 的领域行。
 *
 * <p>只有持久化层、凭据 store 与刷新服务持有本类型；{@link #toString()} 有意省略密文，任何日志或错误消息都不得回显它。
 * 明文秘密载荷已经不存在于本类型，它只在加解密瞬间作为局部变量存在。
 */
public record PluginCredentialRow(
    String pluginId,
    byte[] encryptedPayload,
    String region,
    Instant expiresAt,
    Instant nextRefreshAt,
    PluginCredentialStatus status,
    Instant lastRefreshedAt,
    String lastRefreshError,
    String refreshLeaseToken,
    Instant refreshLeaseUntil,
    long version,
    Instant createTime,
    Instant updateTime) {

  public PluginCredentialRow {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    encryptedPayload = Objects.requireNonNull(encryptedPayload, "encryptedPayload").clone();
    region = Objects.requireNonNull(region, "region");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    nextRefreshAt = Objects.requireNonNull(nextRefreshAt, "nextRefreshAt");
    status = Objects.requireNonNull(status, "status");
    if (!status.isPersisted()) {
      throw new IllegalArgumentException("status must be a persisted credential status");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  @Override
  public byte[] encryptedPayload() {
    return encryptedPayload.clone();
  }

  /** 该行当前是否持有 refresh lease。 */
  public boolean leased() {
    return refreshLeaseToken != null;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PluginCredentialRow that)) {
      return false;
    }
    return version == that.version
        && pluginId.equals(that.pluginId)
        && Arrays.equals(encryptedPayload, that.encryptedPayload)
        && region.equals(that.region)
        && expiresAt.equals(that.expiresAt)
        && nextRefreshAt.equals(that.nextRefreshAt)
        && status == that.status
        && Objects.equals(lastRefreshedAt, that.lastRefreshedAt)
        && Objects.equals(lastRefreshError, that.lastRefreshError)
        && Objects.equals(refreshLeaseToken, that.refreshLeaseToken)
        && Objects.equals(refreshLeaseUntil, that.refreshLeaseUntil)
        && Objects.equals(createTime, that.createTime)
        && Objects.equals(updateTime, that.updateTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pluginId,
        Arrays.hashCode(encryptedPayload),
        region,
        expiresAt,
        nextRefreshAt,
        status,
        lastRefreshedAt,
        lastRefreshError,
        refreshLeaseToken,
        refreshLeaseUntil,
        version,
        createTime,
        updateTime);
  }

  @Override
  public String toString() {
    return "PluginCredentialRow[pluginId="
        + pluginId
        + ", region="
        + region
        + ", status="
        + status
        + ", expiresAt="
        + expiresAt
        + ", nextRefreshAt="
        + nextRefreshAt
        + ", version="
        + version
        + ", leased="
        + leased()
        + ", encryptedPayload=<redacted>]";
  }
}
