package fun.fengwk.kkstudio.platform.plugin.service;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

/**
 * 权威配置历史的严格读取：manifest entry 与它引用的 revision 行必须逐字段一致，缺失或不一致一律 fail closed。
 *
 * <p>历史行是「某次提交写下的不可变事实」，manifest entry 是同一次提交写下的坐标。二者不一致意味着目录被篡改或库被手工改动，此时既不能悄悄退回空值，也不能 用旧 revision
 * 的普通值冒充当前配置，只能拒绝服务。
 */
final class PluginConfigurationHistory {

  private PluginConfigurationHistory() {}

  /**
   * 读取并核对 entry 所引用的历史行。
   *
   * @throws IllegalStateException 历史行缺失，或修订/代码版本/schema 指纹/能力指纹/认证代际/上下文指纹与 entry 不一致
   */
  static PluginConfigurationRow require(
      PluginConfigurationRepository repository,
      String pluginId,
      PluginCatalogManifest.Entry entry) {
    PluginConfigurationRow row =
        repository
            .readConfiguration(pluginId, entry.configRevision())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "plugin configuration history is missing: pluginId="
                            + pluginId
                            + " revision="
                            + entry.configRevision()));
    if (row.revision() != entry.configRevision()
        || !row.codeVersion().equals(entry.pluginCodeVersion())
        || !row.schemaFingerprint().equals(entry.schemaFingerprint())
        || !row.capabilityFingerprint().equals(entry.capabilityFingerprint())
        || !row.authGeneration().equals(entry.authGeneration())
        || !row.contextFingerprint().equals(entry.contextFingerprint())) {
      throw new IllegalStateException(
          "plugin configuration history drifted from the catalog entry: pluginId=" + pluginId);
    }
    return row;
  }

  /** entry 引用历史行的普通值原文。 */
  static String requireValues(
      PluginConfigurationRepository repository,
      String pluginId,
      PluginCatalogManifest.Entry entry) {
    return require(repository, pluginId, entry).valuesJson();
  }
}
