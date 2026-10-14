package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCredentialDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginCredentialJdbcStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 基于 PostgreSQL 的 {@code plugin_credential} 仓库；只做行映射与语句代理，不持有主密钥也不做加解密。 */
@AllArgsConstructor
@Repository
public class PostgresqlPluginCredentialRepository implements PluginCredentialRepository {

  private final PluginCredentialJdbcStore pluginCredentialJdbcStore;

  @Override
  public Optional<PluginCredentialRow> find(String pluginId) {
    return Optional.ofNullable(pluginCredentialJdbcStore.getByPluginId(pluginId)).map(this::toRow);
  }

  @Override
  public boolean insert(PluginCredentialRow row) {
    return pluginCredentialJdbcStore.insert(toDO(row)) == 1;
  }

  @Override
  public boolean replace(
      PluginCredentialRow row,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion) {
    return pluginCredentialJdbcStore.replace(
            toDO(row), expectedAuthGeneration, expectedContextFingerprint, expectedVersion)
        == 1;
  }

  @Override
  public boolean delete(
      String pluginId,
      UUID expectedAuthGeneration,
      String expectedContextFingerprint,
      long expectedVersion) {
    return pluginCredentialJdbcStore.deleteByReference(
            pluginId, expectedAuthGeneration, expectedContextFingerprint, expectedVersion)
        == 1;
  }

  @Override
  public int markExpiredLeasesUncertain(List<String> pluginIds, Instant now, String error) {
    if (pluginIds.isEmpty()) {
      return 0;
    }
    return pluginCredentialJdbcStore.markExpiredLeasesUncertain(pluginIds, now, error);
  }

  @Override
  public List<PluginCredentialRow> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
    if (pluginIds.isEmpty()) {
      return List.of();
    }
    return pluginCredentialJdbcStore
        .claimDue(pluginIds, now, leaseUntil, leaseToken, limit)
        .stream()
        .map(this::toRow)
        .toList();
  }

  @Override
  public boolean finalizeSuccess(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now) {
    return pluginCredentialJdbcStore.finalizeSuccess(
            pluginId,
            authGeneration,
            contextFingerprint,
            leaseToken,
            version,
            encryptedPayload,
            region,
            expiresAt,
            nextRefreshAt,
            refreshedAt,
            now)
        == 1;
  }

  @Override
  public boolean finalizeFailure(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error,
      Instant now) {
    return pluginCredentialJdbcStore.finalizeFailure(
            pluginId,
            authGeneration,
            contextFingerprint,
            leaseToken,
            version,
            status.name(),
            nextRefreshAt,
            error,
            now)
        == 1;
  }

  @Override
  public boolean releaseLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now) {
    return pluginCredentialJdbcStore.releaseLease(
            pluginId, authGeneration, contextFingerprint, leaseToken, version, now)
        == 1;
  }

  @Override
  public boolean ownsUnexpiredLease(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      String leaseToken,
      long version,
      Instant now) {
    return pluginCredentialJdbcStore.countOwnedUnexpiredLease(
            pluginId, authGeneration, contextFingerprint, leaseToken, version, now)
        == 1;
  }

  @Override
  public Optional<Instant> earliestRefreshAt(List<String> pluginIds, Instant now) {
    if (pluginIds.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(pluginCredentialJdbcStore.earliestRefreshAt(pluginIds, now));
  }

  @Override
  public boolean rejectUsedCredential(
      String pluginId,
      UUID authGeneration,
      String contextFingerprint,
      long version,
      byte[] encryptedPayload,
      String error,
      Instant now) {
    return pluginCredentialJdbcStore.rejectUsedCredential(
            pluginId, authGeneration, contextFingerprint, version, encryptedPayload, error, now)
        == 1;
  }

  private PluginCredentialRow toRow(PluginCredentialDO row) {
    return new PluginCredentialRow(
        row.getPluginId(),
        row.getAuthGeneration(),
        row.getContextFingerprint(),
        row.getEncryptedPayload(),
        row.getRegion(),
        row.getExpiresAt(),
        row.getNextRefreshAt(),
        PluginCredentialStatus.valueOf(row.getStatus()),
        row.getLastRefreshedAt(),
        row.getLastRefreshError(),
        row.getRefreshLeaseToken(),
        row.getRefreshLeaseUntil(),
        row.getVersion(),
        row.getCreateTime(),
        row.getUpdateTime());
  }

  private PluginCredentialDO toDO(PluginCredentialRow row) {
    PluginCredentialDO data = new PluginCredentialDO();
    data.setPluginId(row.pluginId());
    data.setAuthGeneration(row.authGeneration());
    data.setContextFingerprint(row.contextFingerprint());
    data.setEncryptedPayload(row.encryptedPayload());
    data.setRegion(row.region());
    data.setExpiresAt(row.expiresAt());
    data.setNextRefreshAt(row.nextRefreshAt());
    data.setStatus(row.status().name());
    data.setLastRefreshedAt(row.lastRefreshedAt());
    data.setLastRefreshError(row.lastRefreshError());
    data.setRefreshLeaseToken(row.refreshLeaseToken());
    data.setRefreshLeaseUntil(row.refreshLeaseUntil());
    data.setVersion(row.version());
    data.setCreateTime(row.createTime());
    data.setUpdateTime(row.updateTime());
    return data;
  }
}
