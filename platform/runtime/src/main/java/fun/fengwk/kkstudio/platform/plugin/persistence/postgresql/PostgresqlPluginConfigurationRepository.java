package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifestJson;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCatalogRevisionDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCatalogStateDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginConfigurationRowDO;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 PostgreSQL 的 {@link PluginConfigurationRepository}：只做行映射、语句代理与提交期一致性校验。
 *
 * <p>{@link #commit(long, List, PluginCatalogManifest)} 在任何 SQL 之前先要求活跃事务，并在 Java 侧校验 changed 历史与
 * manifest 精确一致；随后同一事务内锁定并读取权威基线：epoch 不匹配直接返回 false，且既有 pluginId 一个都不能消失、未 changed 的条目必须与 基线 entry
 * 逐字段相等。全部通过后才用一条语句同时推进 epoch 指针并新建该 epoch 的不可变 manifest 历史，再插入 changed 历史、用数据库断点复核每个 manifest
 * 引用，最后发出通知。任何一步异常都交由事务整体回滚，CAS 未命中不留下 manifest 或 changed 历史。
 */
@AllArgsConstructor
@Repository
public class PostgresqlPluginConfigurationRepository implements PluginConfigurationRepository {

  private final PluginConfigurationJdbcStore pluginConfigurationJdbcStore;

  private final PostgresqlPluginCatalogChangeNotifier notifier;

  @Override
  public PluginCatalogRecord readCatalog() {
    PluginCatalogStateDO state = pluginConfigurationJdbcStore.getCatalog();
    if (state == null) {
      return null;
    }
    return toRecord(pluginConfigurationJdbcStore.getCatalogRevision(state.getEpoch()));
  }

  @Override
  public Optional<PluginCatalogRecord> readCatalog(long epoch) {
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative");
    }
    // 指定 epoch 只读不可变 manifest 历史：不读当前指针、不做任何兜底。
    return Optional.ofNullable(pluginConfigurationJdbcStore.getCatalogRevision(epoch))
        .map(PostgresqlPluginConfigurationRepository::toRecord);
  }

  @Override
  public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
    Objects.requireNonNull(pluginId, "pluginId");
    return Optional.ofNullable(pluginConfigurationJdbcStore.getRevision(pluginId, revision))
        .map(PostgresqlPluginConfigurationRepository::toRow);
  }

  @Override
  public boolean commit(
      long expectedEpoch,
      List<PluginConfigurationRow> changedConfigurations,
      PluginCatalogManifest manifest) {
    PostgresqlPluginCatalogChangeNotifier.requireTransaction();
    Objects.requireNonNull(changedConfigurations, "changedConfigurations");
    Objects.requireNonNull(manifest, "manifest");

    long newEpoch = Math.addExact(expectedEpoch, 1);
    requireConsistentCommit(newEpoch, changedConfigurations, manifest);
    // 同一事务内的权威基线：epoch 不匹配就直接返回 false，既不插入历史也不发通知。
    PluginCatalogStateDO baselineRow = pluginConfigurationJdbcStore.lockCatalog();
    if (baselineRow == null || baselineRow.getEpoch() != expectedEpoch) {
      return false;
    }
    PluginCatalogRecord baseline =
        toRecord(pluginConfigurationJdbcStore.getCatalogRevision(expectedEpoch));
    if (baseline == null) {
      throw new IllegalStateException("the authoritative catalog revision is missing");
    }
    requireBaselinePreserved(newEpoch, baseline.manifest(), manifest);

    PluginCatalogRecord next = new PluginCatalogRecord(newEpoch, manifest);
    String manifestJson = PluginCatalogManifestJson.encode(next.manifest());
    // 单条语句同时推进 state 指针并新建该 epoch 的 manifest 历史：语句末的即时外键校验保证指针与历史同时成立，
    // 而 CAS 未命中时插入 0 行，因此并发败方既不推进 epoch 也不留下任何历史。
    if (pluginConfigurationJdbcStore.advanceEpochAndInsertRevision(expectedEpoch, manifestJson)
        != 1) {
      return false;
    }
    for (PluginConfigurationRow row : changedConfigurations) {
      if (pluginConfigurationJdbcStore.insertRevision(toDO(row)) != 1) {
        throw new IllegalStateException(
            "plugin configuration history insert affected no row: pluginId=" + row.pluginId());
      }
    }
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : manifest.plugins().entrySet()) {
      PluginCatalogManifest.Entry identity = entry.getValue();
      if (pluginConfigurationJdbcStore.countMatchingReference(
              entry.getKey(),
              identity.configRevision(),
              identity.pluginCodeVersion(),
              identity.schemaFingerprint(),
              identity.capabilityFingerprint(),
              identity.authGeneration(),
              identity.contextFingerprint())
          != 1) {
        throw new IllegalStateException(
            "plugin catalog manifest references a missing or drifted history row: pluginId="
                + entry.getKey());
      }
    }
    notifier.catalogChanged(newEpoch);
    return true;
  }

  /** changed 历史必须恰好是本提交新增 revision 对应的 manifest 条目，身份逐字段一致。 */
  private static void requireConsistentCommit(
      long newEpoch,
      List<PluginConfigurationRow> changedConfigurations,
      PluginCatalogManifest manifest) {
    Map<String, PluginConfigurationRow> changedByPluginId = new LinkedHashMap<>();
    for (PluginConfigurationRow row : changedConfigurations) {
      if (row.revision() != newEpoch) {
        throw new IllegalArgumentException(
            "changed configuration revision must equal the new epoch");
      }
      if (changedByPluginId.put(row.pluginId(), row) != null) {
        throw new IllegalArgumentException("changed configurations must not repeat a pluginId");
      }
      PluginCatalogManifest.Entry entry = manifest.plugins().get(row.pluginId());
      if (entry == null || entry.configRevision() != newEpoch) {
        throw new IllegalArgumentException(
            "changed configuration must be referenced by the manifest at the new epoch");
      }
      if (!entry.pluginCodeVersion().equals(row.codeVersion())
          || !entry.schemaFingerprint().equals(row.schemaFingerprint())
          || !entry.capabilityFingerprint().equals(row.capabilityFingerprint())
          || !entry.authGeneration().equals(row.authGeneration())
          || !entry.contextFingerprint().equals(row.contextFingerprint())) {
        throw new IllegalArgumentException(
            "changed configuration identity must match the manifest entry");
      }
    }
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : manifest.plugins().entrySet()) {
      if (entry.getValue().configRevision() == newEpoch
          && !changedByPluginId.containsKey(entry.getKey())) {
        throw new IllegalArgumentException(
            "manifest references a new revision without a changed configuration");
      }
    }
  }

  /** 权威基线的冻结条目必须原样保留：既有 pluginId 一个都不能消失，本轮未 changed 的插件必须与基线 entry 逐字段相等（不能被偷退到任意旧 revision）。 */
  private static void requireBaselinePreserved(
      long newEpoch, PluginCatalogManifest baseline, PluginCatalogManifest candidate) {
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : baseline.plugins().entrySet()) {
      PluginCatalogManifest.Entry next = candidate.plugins().get(entry.getKey());
      if (next == null) {
        throw new IllegalStateException(
            "plugin catalog manifest must retain every existing plugin: pluginId="
                + entry.getKey());
      }
      if (next.configRevision() == newEpoch) {
        // 本轮 changed：其唯一性、身份与新 history row 已由 requireConsistentCommit 与插入校验覆盖。
        continue;
      }
      if (!next.equals(entry.getValue())) {
        throw new IllegalStateException(
            "an unchanged plugin catalog entry must equal the authoritative baseline: pluginId="
                + entry.getKey());
      }
    }
    for (Map.Entry<String, PluginCatalogManifest.Entry> entry : candidate.plugins().entrySet()) {
      if (!baseline.plugins().containsKey(entry.getKey())
          && entry.getValue().configRevision() != newEpoch) {
        throw new IllegalStateException(
            "a new plugin catalog entry must reference a changed configuration: pluginId="
                + entry.getKey());
      }
    }
  }

  private static PluginCatalogRecord toRecord(PluginCatalogRevisionDO row) {
    if (row == null) {
      return null;
    }
    return new PluginCatalogRecord(
        row.getEpoch(), PluginCatalogManifestJson.decode(row.getManifestJson()));
  }

  private static PluginConfigurationRow toRow(PluginConfigurationRowDO row) {
    return new PluginConfigurationRow(
        row.getPluginId(),
        row.getRevision(),
        row.getCodeVersion(),
        row.getSchemaFingerprint(),
        row.getCapabilityFingerprint(),
        row.getAuthGeneration(),
        row.getContextFingerprint(),
        row.getConfiguredSecretFields(),
        row.getValuesJson());
  }

  private static PluginConfigurationRowDO toDO(PluginConfigurationRow row) {
    PluginConfigurationRowDO data = new PluginConfigurationRowDO();
    data.setPluginId(row.pluginId());
    data.setRevision(row.revision());
    data.setCodeVersion(row.codeVersion());
    data.setSchemaFingerprint(row.schemaFingerprint());
    data.setCapabilityFingerprint(row.capabilityFingerprint());
    data.setAuthGeneration(row.authGeneration());
    data.setContextFingerprint(row.contextFingerprint());
    data.setConfiguredSecretFields(row.configuredSecretFields());
    data.setValuesJson(row.valuesJson());
    return data;
  }
}
