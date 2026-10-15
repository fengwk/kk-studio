package fun.fengwk.kkstudio.platform.plugin.testing;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 测试专用的内存 pending attempt 仓库，忠实模拟 PostgreSQL 版语义：每个 pluginId 至多一行，写入整体替换旧 attempt，
 * 消费必须是「摘要命中、未过期、epoch 未前进且当前 manifest 坐标仍与 attempt 相等才删除并返回」的原子单次操作。
 *
 * <p>坐标条件与生产 SQL 一致地取自权威 catalog 替身，因此「先检查后按 nonce 单独删除」的漂移窗口在这里同样不存在。
 */
public class InMemoryPluginAuthAttemptRepository implements PluginAuthAttemptRepository {

  private final PluginConfigurationRepository configurationRepository;
  private final Map<String, PluginAuthAttemptRow> storage = new LinkedHashMap<>();

  public InMemoryPluginAuthAttemptRepository(
      PluginConfigurationRepository configurationRepository) {
    this.configurationRepository =
        Objects.requireNonNull(configurationRepository, "configurationRepository");
  }

  @Override
  public synchronized void replace(PluginAuthAttemptRow attempt) {
    Objects.requireNonNull(attempt, "attempt");
    storage.put(attempt.pluginId(), attempt);
  }

  @Override
  public synchronized Optional<PluginAuthAttemptRow> find(String pluginId) {
    return Optional.ofNullable(storage.get(pluginId));
  }

  @Override
  public synchronized Optional<PluginAuthAttemptRow> consume(
      String pluginId, String nonceHash, Instant now, long expectedEpoch) {
    PluginAuthAttemptRow attempt = storage.get(pluginId);
    if (attempt == null
        || !attempt.nonceHash().equals(nonceHash)
        || !attempt.expiresAt().isAfter(now)
        || !coordinatesStillCurrent(pluginId, attempt, expectedEpoch)) {
      return Optional.empty();
    }
    storage.remove(pluginId);
    return Optional.of(attempt);
  }

  /** 与生产 SQL 同一组条件：全局 epoch 未前进，且当前 manifest 的代际/上下文/修订与 attempt 逐字段相等。 */
  private boolean coordinatesStillCurrent(
      String pluginId, PluginAuthAttemptRow attempt, long expectedEpoch) {
    PluginCatalogRecord catalog = configurationRepository.readCatalog();
    if (catalog == null || catalog.epoch() != expectedEpoch) {
      return false;
    }
    PluginCatalogManifest.Entry entry = catalog.manifest().plugins().get(pluginId);
    return entry != null
        && entry.configRevision() == attempt.configRevision()
        && entry.authGeneration().equals(attempt.authGeneration())
        && entry.contextFingerprint().equals(attempt.contextFingerprint());
  }

  public synchronized int size() {
    return storage.size();
  }
}
