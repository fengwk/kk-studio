package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;

/**
 * {@code plugin_configuration_revision} 的一行不可变配置历史投影。
 *
 * <p>revision 是该 plugin 最后一次写入的全局 epoch，必须为正；valuesJson 是严格 JSON object 普通值，受 64 KiB UTF-8 限额（由
 * {@link PluginConfig} 复用校验）；code/schema/capability 是该 revision 撰写时的身份指纹，启动恢复据此拒绝 JAR 漂移。
 */
public record PluginConfigurationRow(
    String pluginId,
    long revision,
    String codeVersion,
    String schemaFingerprint,
    String capabilityFingerprint,
    String valuesJson) {

  public PluginConfigurationRow {
    PluginCatalogManifest.requirePluginId(pluginId);
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive");
    }
    if (codeVersion == null
        || codeVersion.isBlank()
        || codeVersion.length() > PluginCatalogManifest.Entry.MAX_CODE_VERSION_LENGTH
        || !codeVersion.equals(codeVersion.strip())) {
      throw new IllegalArgumentException("codeVersion must be a bounded non-blank text");
    }
    if (!PluginCatalogFingerprint.isFingerprint(schemaFingerprint)) {
      throw new IllegalArgumentException("schemaFingerprint must be a 64-char lowercase SHA-256");
    }
    if (!PluginCatalogFingerprint.isFingerprint(capabilityFingerprint)) {
      throw new IllegalArgumentException(
          "capabilityFingerprint must be a 64-char lowercase SHA-256");
    }
    // 复用 PluginConfig 的严格 JSON object 与 64 KiB 门禁；records 只 insert，绝不携带秘密。
    new PluginConfig(revision, valuesJson);
  }
}
