package fun.fengwk.kkstudio.web.runtime;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Web 组合根测试的内存替身：权威 catalog 单行 CAS、按 epoch 的完整 manifest 历史与最小事务边界。
 *
 * <p>只服务不接触数据库的组合根测试；真实持久化行为由 PostgreSQL 集成测试覆盖。
 */
final class InMemoryPluginCatalogFakes {

  private InMemoryPluginCatalogFakes() {}

  /** 从 V1 未初始化形态开始的内存权威仓储。 */
  static PluginConfigurationRepository repository() {
    return new InMemoryPluginConfigurationRepository();
  }

  /** 只让 {@code TransactionTemplate} 边界生效的最小事务管理器。 */
  static PlatformTransactionManager transactionManager() {
    return new InMemoryTransactionManager();
  }

  private static final class InMemoryPluginConfigurationRepository
      implements PluginConfigurationRepository {

    private PluginCatalogRecord catalog =
        new PluginCatalogRecord(0, PluginCatalogManifest.uninitialized());
    private final Map<Long, PluginCatalogRecord> catalogRevisions =
        new LinkedHashMap<>(Map.of(0L, catalog));
    private final Map<String, PluginConfigurationRow> history = new LinkedHashMap<>();

    @Override
    public PluginCatalogRecord readCatalog() {
      return catalog;
    }

    @Override
    public Optional<PluginCatalogRecord> readCatalog(long epoch) {
      return Optional.ofNullable(catalogRevisions.get(epoch));
    }

    @Override
    public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
      return Optional.ofNullable(history.get(pluginId + "#" + revision));
    }

    @Override
    public boolean commit(
        long expectedEpoch,
        List<PluginConfigurationRow> changedConfigurations,
        PluginCatalogManifest manifest) {
      if (catalog.epoch() != expectedEpoch) {
        return false;
      }
      for (PluginConfigurationRow row : changedConfigurations) {
        history.put(row.pluginId() + "#" + row.revision(), row);
      }
      catalog = new PluginCatalogRecord(expectedEpoch + 1, manifest);
      catalogRevisions.put(catalog.epoch(), catalog);
      return true;
    }
  }

  private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
