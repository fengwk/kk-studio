package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code plugin_credential} 的领域行。
 *
 * <p>只有持久化层、凭据 store 与刷新服务持有本类型；{@link #toString()} 有意省略密文，任何日志或错误消息都不得回显它。
 * 明文秘密载荷已经不存在于本类型，它只在加解密瞬间作为局部变量存在。
 *
 * <p>与材料一致，只存在两种真实形态：交互凭据的 {@code region/expiresAt/nextRefreshAt} 全部存在；静态密钥三者全部为 null，既没有 region
 * 路由，也没有过期与自动刷新。
 *
 * <p>{@code authGeneration} 与 {@code contextFingerprint} 是每次人工完整替换凭据都重新生成的认证身份：它们与 CAS {@code
 * version} 分开，使断连后重建的同 {@code rowVersion} 行、以及同一代际下配置目标变化后的新上下文，都无法被旧快照、迟到刷新或旧密文命中。它们不是
 * 秘密，但也只在本类型、Snapshot 与持久化之间流动。
 */
public record PluginCredentialRow(
    String pluginId,
    UUID authGeneration,
    String contextFingerprint,
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
    authGeneration = Objects.requireNonNull(authGeneration, "authGeneration");
    contextFingerprint = Objects.requireNonNull(contextFingerprint, "contextFingerprint");
    encryptedPayload = Objects.requireNonNull(encryptedPayload, "encryptedPayload").clone();
    boolean anyRouteMetadata = region != null || expiresAt != null || nextRefreshAt != null;
    boolean allRouteMetadata = region != null && expiresAt != null && nextRefreshAt != null;
    if (anyRouteMetadata != allRouteMetadata) {
      throw new IllegalArgumentException(
          "a credential row must carry region, expiresAt and nextRefreshAt together or none");
    }
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

  /** 该行是否为交互凭据（携带 region 与过期/刷新时刻）。 */
  public boolean interactive() {
    return region != null;
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
        && authGeneration.equals(that.authGeneration)
        && contextFingerprint.equals(that.contextFingerprint)
        && Arrays.equals(encryptedPayload, that.encryptedPayload)
        && Objects.equals(region, that.region)
        && Objects.equals(expiresAt, that.expiresAt)
        && Objects.equals(nextRefreshAt, that.nextRefreshAt)
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
        authGeneration,
        contextFingerprint,
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
        + ", authGeneration="
        + authGeneration
        + ", contextFingerprint="
        + contextFingerprint
        + ", region="
        + region
        + ", expiresAt="
        + expiresAt
        + ", nextRefreshAt="
        + nextRefreshAt
        + ", status="
        + status
        + ", version="
        + version
        + ", encryptedPayload=<redacted>]";
  }
}
