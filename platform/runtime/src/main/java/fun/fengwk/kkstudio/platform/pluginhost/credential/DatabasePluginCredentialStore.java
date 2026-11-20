package fun.fengwk.kkstudio.platform.pluginhost.credential;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
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
import java.util.Set;

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
    return snapshot(
        repository.find(pluginId).orElseThrow(DatabasePluginCredentialStore::notConnected));
  }

  @Override
  public PluginCredentialSnapshot resolve(Plugin plugin, PluginConfig config) {
    String pluginId = plugin.descriptor().pluginId();
    Set<String> fields = plugin.authenticationSpec().contextFields();
    var current =
        repository
            .findConfigured(pluginId)
            .orElseThrow(DatabasePluginCredentialStore::notConnected);
    JsonNode expected = JsonValues.readTree(config.valuesJson());
    JsonNode actual = JsonValues.readTree(current.valuesJson());
    for (String field : fields) {
      if (!Objects.equals(expected.get(field), actual.get(field))) {
        throw unavailable(
            PluginCredentialUnavailableReason.NOT_CONNECTED,
            "plugin credential is not available for this authentication context");
      }
    }
    return snapshot(current.credential());
  }

  private PluginCredentialSnapshot snapshot(PluginCredentialRow row) {
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
        row.generation(),
        row.pluginId(),
        row.region(),
        row.expiresAt(),
        row.version(),
        row.payloadJson());
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    Objects.requireNonNull(snapshot, "snapshot");
    return repository.rejectUsed(
        snapshot.pluginId(),
        snapshot.generation(),
        snapshot.version(),
        PluginCredentialRefreshService.boundedError(error, AUTH_REJECTED_ERROR));
  }

  private static PluginCredentialUnavailableException notConnected() {
    return unavailable(PluginCredentialUnavailableReason.NOT_CONNECTED, "plugin is not connected");
  }

  private static PluginCredentialUnavailableException unavailable(
      PluginCredentialUnavailableReason reason, String message) {
    return new PluginCredentialUnavailableException(reason, message);
  }
}
