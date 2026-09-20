package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.mapper.PluginCredentialMapper;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCredentialDO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 基于 PostgreSQL 的 {@code plugin_credential} 仓库；只做行映射与语句代理，不持有主密钥也不做加解密。 */
@AllArgsConstructor
@Repository
public class PostgresqlPluginCredentialRepository implements PluginCredentialRepository {

  private final PluginCredentialMapper mapper;

  @Override
  public Optional<PluginCredentialRow> find(String pluginId) {
    return Optional.ofNullable(mapper.getByPluginId(pluginId)).map(this::toRow);
  }

  @Override
  public boolean upsert(PluginCredentialRow row) {
    return mapper.upsert(toDO(row)) == 1;
  }

  @Override
  public boolean delete(String pluginId) {
    return mapper.deleteByPluginId(pluginId) == 1;
  }

  @Override
  public int markExpiredLeasesUncertain(List<String> pluginIds, Instant now, String error) {
    if (pluginIds.isEmpty()) {
      return 0;
    }
    return mapper.markExpiredLeasesUncertain(pluginIds, now, error);
  }

  @Override
  public List<PluginCredentialRow> claimDue(
      List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
    if (pluginIds.isEmpty()) {
      return List.of();
    }
    return mapper.claimDue(pluginIds, now, leaseUntil, leaseToken, limit).stream()
        .map(this::toRow)
        .toList();
  }

  @Override
  public boolean finalizeSuccess(
      String pluginId,
      String leaseToken,
      long version,
      byte[] encryptedPayload,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt,
      Instant refreshedAt,
      Instant now) {
    return mapper.finalizeSuccess(
            pluginId,
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
      String leaseToken,
      long version,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error,
      Instant now) {
    return mapper.finalizeFailure(
            pluginId, leaseToken, version, status.name(), nextRefreshAt, error, now)
        == 1;
  }

  @Override
  public boolean releaseLease(String pluginId, String leaseToken, long version, Instant now) {
    return mapper.releaseLease(pluginId, leaseToken, version, now) == 1;
  }

  private PluginCredentialRow toRow(PluginCredentialDO row) {
    return new PluginCredentialRow(
        row.getPluginId(),
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
