package fun.fengwk.kkstudio.platform.plugin.testing;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试专用的内存 catalog 与配置历史仓储，忠实模拟 PostgreSQL 版的关键语义：
 *
 * <ul>
 *   <li>{@link #commit(long, List, PluginCatalogManifest)} 在 epoch 不匹配时返回 false 且不写任何历史，命中时全局 epoch
 *       前进到 {@code expectedEpoch + 1}、插入 changed 历史并整体替换 manifest；
 *   <li>changed 的 revision 必须等于 {@code expectedEpoch + 1}，且不能删除已存在的 pluginId 条目（与 PG 侧一致性校验同构）；
 *   <li>历史 append-only：同一 {@code (pluginId, revision)} 不会被覆盖成不同内容。
 * </ul>
 */
public class InMemoryPluginConfigurationRepository implements PluginConfigurationRepository {

  private PluginCatalogRecord catalog =
      new PluginCatalogRecord(0L, PluginCatalogManifest.uninitialized());
  private final Map<String, PluginConfigurationRow> history = new LinkedHashMap<>();
  private RuntimeException readFailure;
  private RuntimeException commitFailure;

  /** 初始化权威单行（epoch 与 manifest）。 */
  public void initialize(PluginCatalogRecord record) {
    this.catalog = record;
  }

  /** 直接植入一条历史行（模拟自其它节点已提交的 revision）。 */
  public void putHistory(PluginConfigurationRow row) {
    history.put(row.pluginId() + "#" + row.revision(), row);
  }

  /** 直接删除一条历史行（模拟库被手工改动/历史缺失）。 */
  public void removeHistory(String pluginId, long revision) {
    history.remove(pluginId + "#" + revision);
  }

  public void failReads(RuntimeException failure) {
    this.readFailure = failure;
  }

  public void failCommits(RuntimeException failure) {
    this.commitFailure = failure;
  }

  public int historySize() {
    return history.size();
  }

  @Override
  public PluginCatalogRecord readCatalog() {
    if (readFailure != null) {
      throw readFailure;
    }
    return catalog;
  }

  @Override
  public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
    if (readFailure != null) {
      throw readFailure;
    }
    return Optional.ofNullable(history.get(pluginId + "#" + revision));
  }

  @Override
  public boolean commit(
      long expectedEpoch,
      List<PluginConfigurationRow> changedConfigurations,
      PluginCatalogManifest manifest) {
    if (commitFailure != null) {
      throw commitFailure;
    }
    if (catalog == null || catalog.epoch() != expectedEpoch) {
      return false;
    }
    for (PluginConfigurationRow row : changedConfigurations) {
      if (row.revision() != expectedEpoch + 1) {
        throw new IllegalArgumentException("changed revision must equal expectedEpoch + 1");
      }
    }
    for (String pluginId : catalog.manifest().plugins().keySet()) {
      if (!manifest.plugins().containsKey(pluginId)) {
        throw new IllegalArgumentException("an existing plugin entry must not disappear");
      }
    }
    for (PluginConfigurationRow row : changedConfigurations) {
      history.put(row.pluginId() + "#" + row.revision(), row);
    }
    catalog = new PluginCatalogRecord(expectedEpoch + 1, manifest);
    return true;
  }
}
