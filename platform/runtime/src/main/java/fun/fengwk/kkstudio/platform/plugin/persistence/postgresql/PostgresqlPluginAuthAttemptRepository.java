package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginAuthAttemptDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginAuthAttemptJdbcStore;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 PostgreSQL 的 {@link PluginAuthAttemptRepository}：只做行映射与语句代理，原子性完全由条件 SQL 承担。
 *
 * <p>写入是单条 {@code insert ... on conflict do update}，消费是单条 {@code delete ... returning}（并用 {@code
 * using plugin_catalog_state} 同时条件当前 epoch 与 manifest 坐标），因此「每个 Plugin 至多一个 pending
 * attempt」「单次消费」与「坐标漂移后不可能签发回调许可」 都由数据库保证，不依赖任何应用侧锁、读后写或先检查后单独删除。
 */
@AllArgsConstructor
@Repository
public class PostgresqlPluginAuthAttemptRepository implements PluginAuthAttemptRepository {

  private final PluginAuthAttemptJdbcStore pluginAuthAttemptJdbcStore;

  @Override
  public void replace(PluginAuthAttemptRow attempt) {
    Objects.requireNonNull(attempt, "attempt");
    PluginAuthAttemptDO row = new PluginAuthAttemptDO();
    row.setPluginId(attempt.pluginId());
    row.setNonceHash(attempt.nonceHash());
    row.setAuthGeneration(attempt.authGeneration());
    row.setContextFingerprint(attempt.contextFingerprint());
    row.setConfigRevision(attempt.configRevision());
    row.setRegion(attempt.region());
    row.setExpiresAt(attempt.expiresAt());
    pluginAuthAttemptJdbcStore.replace(row);
  }

  @Override
  public Optional<PluginAuthAttemptRow> find(String pluginId) {
    Objects.requireNonNull(pluginId, "pluginId");
    return Optional.ofNullable(pluginAuthAttemptJdbcStore.getByPluginId(pluginId))
        .map(PostgresqlPluginAuthAttemptRepository::toRow);
  }

  @Override
  public Optional<PluginAuthAttemptRow> consume(
      String pluginId, String nonceHash, Instant now, long expectedEpoch) {
    Objects.requireNonNull(pluginId, "pluginId");
    Objects.requireNonNull(nonceHash, "nonceHash");
    Objects.requireNonNull(now, "now");
    if (expectedEpoch < 0) {
      throw new IllegalArgumentException("expectedEpoch must not be negative");
    }
    return Optional.ofNullable(
            pluginAuthAttemptJdbcStore.consume(pluginId, nonceHash, now, expectedEpoch))
        .map(PostgresqlPluginAuthAttemptRepository::toRow);
  }

  private static PluginAuthAttemptRow toRow(PluginAuthAttemptDO row) {
    return new PluginAuthAttemptRow(
        row.getPluginId(),
        row.getNonceHash(),
        row.getAuthGeneration(),
        row.getContextFingerprint(),
        row.getConfigRevision(),
        row.getRegion(),
        row.getExpiresAt());
  }
}
