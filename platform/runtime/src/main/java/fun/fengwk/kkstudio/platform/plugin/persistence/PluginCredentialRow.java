package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.time.Instant;
import java.util.Objects;

/** {@code plugin_credential} 的一行；{@code payloadJson} 是秘密，任何投影都不得返回。 */
public record PluginCredentialRow(
    String pluginId,
    String payloadJson,
    String region,
    Instant expiresAt,
    Instant nextRefreshAt,
    PluginCredentialStatus status,
    Instant lastRefreshedAt,
    String lastRefreshError,
    String refreshLeaseToken,
    Instant refreshLeaseUntil,
    long version) {

  public PluginCredentialRow {
    Objects.requireNonNull(pluginId, "pluginId");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(status, "status");
    if (!status.isPersisted()) {
      throw new IllegalArgumentException("status must be a persisted credential status");
    }
  }

  /** 刷新租约是否仍被持有且未过期。 */
  public boolean refreshing(Instant now) {
    return refreshLeaseUntil != null && refreshLeaseUntil.isAfter(now);
  }

  @Override
  public String toString() {
    return "PluginCredentialRow[pluginId="
        + pluginId
        + ", status="
        + status
        + ", version="
        + version
        + "]";
  }
}
