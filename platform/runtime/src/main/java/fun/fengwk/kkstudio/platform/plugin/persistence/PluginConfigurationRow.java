package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code plugin_configuration_revision} 的一行不可变配置历史投影。
 *
 * <p>revision 是该 plugin 最后一次写入的全局 epoch，必须为正；valuesJson 是严格 JSON object 普通值，受 64 KiB UTF-8 限额（由
 * {@link PluginConfig} 复用校验）；code/schema/capability 是该 revision 撰写时的身份指纹，启动恢复据此拒绝 JAR 漂移。
 *
 * <p>每个 revision 还冻结写入时的凭据身份：{@code authGeneration} 与 {@code contextFingerprint} 必须与 manifest entry
 * 逐字段相等； {@code configuredSecretFields} 只记录「哪些声明的秘密字段已录入」的字段名，绝不携带任何明文值，因此即使主密钥不可用也不会谎称未录入。
 */
public record PluginConfigurationRow(
    String pluginId,
    long revision,
    String codeVersion,
    String schemaFingerprint,
    String capabilityFingerprint,
    UUID authGeneration,
    String contextFingerprint,
    List<String> configuredSecretFields,
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
    authGeneration = Objects.requireNonNull(authGeneration, "authGeneration");
    if (!PluginCatalogFingerprint.isFingerprint(contextFingerprint)) {
      throw new IllegalArgumentException("contextFingerprint must be a 64-char lowercase SHA-256");
    }
    configuredSecretFields = copyConfiguredSecretFields(configuredSecretFields);
    // 复用 PluginConfig 的严格 JSON object 与 64 KiB 门禁；records 只 insert，绝不携带秘密。
    new PluginConfig(revision, valuesJson);
  }

  /** 声明字段名去重、排序并冻结：configured 投影只依赖这组非秘密字段名。 */
  private static List<String> copyConfiguredSecretFields(List<String> fields) {
    Objects.requireNonNull(fields, "configuredSecretFields");
    Set<String> unique = new LinkedHashSet<>();
    for (String field : fields) {
      if (field == null || !PluginAuthSpec.CONFIG_FIELD_SYNTAX.matcher(field).matches()) {
        throw new IllegalArgumentException("configuredSecretFields must be schema field names");
      }
      unique.add(field);
    }
    List<String> sorted = new ArrayList<>(unique);
    Collections.sort(sorted);
    return List.copyOf(sorted);
  }
}
