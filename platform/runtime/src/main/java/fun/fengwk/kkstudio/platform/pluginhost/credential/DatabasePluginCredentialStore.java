package fun.fengwk.kkstudio.platform.pluginhost.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRow;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** {@link PluginCredentialStore} 的数据库实现：读取当前凭据行，按状态、刷新租约与本地时限判定是否可用。 */
public final class DatabasePluginCredentialStore implements PluginCredentialStore {

  static final String AUTH_REJECTED_ERROR =
      "plugin credential was rejected by the provider and must be re-authenticated";

  private final PluginCredentialRepository repository;
  private final Clock clock;

  public DatabasePluginCredentialStore(PluginCredentialRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public PluginCredentialProjection projection(String pluginId) {
    return repository
        .find(pluginId)
        .map(
            row ->
                new PluginCredentialProjection(
                    row.status(),
                    row.region(),
                    row.expiresAt(),
                    row.nextRefreshAt(),
                    row.lastRefreshedAt(),
                    row.lastRefreshError()))
        .orElseGet(PluginCredentialProjection::notConnected);
  }

  @Override
  public PluginCredentialSnapshot resolve(String pluginId) {
    PluginCredentialRow row =
        repository
            .find(pluginId)
            .orElseThrow(
                () ->
                    unavailable(
                        PluginCredentialUnavailableReason.NOT_CONNECTED,
                        "plugin is not connected"));
    Instant now = clock.instant();
    if (row.refreshing(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.AUTH_REFRESHING,
          "plugin credential refresh is in flight");
    }
    switch (row.status()) {
      case REAUTH_REQUIRED -> throw unavailable(
          PluginCredentialUnavailableReason.REAUTH_REQUIRED,
          "plugin credential was rejected and must be re-authenticated");
      case REFRESH_UNCERTAIN -> throw unavailable(
          PluginCredentialUnavailableReason.REFRESH_UNCERTAIN,
          "plugin credential refresh outcome is unknown; re-authenticate the plugin");
      default -> {
        // CONNECTED 与 REFRESH_FAILED（旧凭据仍在本地时限内）都可用。
      }
    }
    if (row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.EXPIRED,
          "plugin credential is expired; re-authenticate the plugin");
    }
    return new PluginCredentialSnapshot(
        row.pluginId(), row.region(), row.expiresAt(), row.version(), row.payloadJson());
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    Objects.requireNonNull(snapshot, "snapshot");
    return repository.rejectUsed(
        snapshot.pluginId(),
        snapshot.version(),
        PluginCredentialRefreshService.boundedError(error, AUTH_REJECTED_ERROR));
  }

  private static PluginCredentialUnavailableException unavailable(
      PluginCredentialUnavailableReason reason, String message) {
    return new PluginCredentialUnavailableException(reason, message);
  }
}
