package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifestJson;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
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
 * 逐字段相等。全部通过后才 CAS epoch、插入 changed 历史、用数据库断点复核每个 manifest 引用，最后发出通知。任何一步异常都交由事务整体回滚。
 */
@AllArgsConstructor
@Repository
public class PostgresqlPluginConfigurationRepository implements PluginConfigurationRepository {

  private final PluginConfigurationJdbcStore pluginConfigurationJdbcStore;

  private final PostgresqlPluginCatalogChangeNotifier notifier;

  @Override
  public PluginCatalogRecord readCatalog() {
    PluginCatalogStateDO row = pluginConfigurationJdbcStore.getCatalog();
    if (row == null) {
      return null;
    }
    return new PluginCatalogRecord(
        row.getEpoch(), PluginCatalogManifestJson.decode(row.getManifestJson()));
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
        new PluginCatalogRecord(
            baselineRow.getEpoch(),
            PluginCatalogManifestJson.decode(baselineRow.getManifestJson()));
    requireBaselinePreserved(newEpoch, baseline.manifest(), manifest);
    new PluginCatalogRecord(newEpoch, manifest);

    String manifestJson = PluginCatalogManifestJson.encode(manifest);
    // SQL CAS 仍是并发的最终围栏：基线读取之后任何并发提交都会让这里失败。
    if (pluginConfigurationJdbcStore.compareAndSetEpoch(expectedEpoch, manifestJson) != 1) {
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
