package fun.fengwk.kkstudio.platform.plugin.catalog;

import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 完整冻结两域目录的 catalog manifest：按 pluginId 排序的冻结 plugins 映射加全局 catalogFingerprint。
 *
 * <p>pluginId 只在 map key 出现，entry 只携带该 plugin 的 configRevision 与定义/能力身份指纹。只有未初始化形态允许
 * catalogFingerprint 为 null 与空 plugins；任何带 plugins 的 manifest 都必须声明 64 位小写 SHA-256。
 */
public record PluginCatalogManifest(Map<String, Entry> plugins, String catalogFingerprint) {

  /** 与 {@link PluginDescriptor#pluginId()} 同一来源的 canonical 标识符长度上限，不引入第二套身份限制。 */
  public static final int MAX_PLUGIN_ID_LENGTH = PluginDescriptor.MAX_PLUGIN_ID_LENGTH;

  /** 与 pluginId 列一致的 canonical 小写点划线标识符。 */
  public static final Pattern PLUGIN_ID_SYNTAX = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  public PluginCatalogManifest {
    Objects.requireNonNull(plugins, "plugins");
    TreeMap<String, Entry> ordered = new TreeMap<>();
    for (Map.Entry<String, Entry> entry : plugins.entrySet()) {
      String pluginId = Objects.requireNonNull(entry.getKey(), "plugins key");
      requirePluginId(pluginId);
      ordered.put(pluginId, Objects.requireNonNull(entry.getValue(), "plugins value"));
    }
    plugins = Collections.unmodifiableMap(ordered);
    if (catalogFingerprint != null && !PluginCatalogFingerprint.isFingerprint(catalogFingerprint)) {
      throw new IllegalArgumentException(
          "catalogFingerprint must be a 64-char lowercase SHA-256 when present");
    }
    if (catalogFingerprint == null && !plugins.isEmpty()) {
      throw new IllegalArgumentException(
          "a manifest with plugins must declare a catalogFingerprint");
    }
  }

  /** 唯一未初始化形态：空 plugins 且无指纹。 */
  public static PluginCatalogManifest uninitialized() {
    return new PluginCatalogManifest(Map.of(), null);
  }

  /** canonical pluginId 的门禁：小写点划线、非空、长度有界。 */
  public static void requirePluginId(String pluginId) {
    Objects.requireNonNull(pluginId, "pluginId");
    if (pluginId.length() > MAX_PLUGIN_ID_LENGTH || !PLUGIN_ID_SYNTAX.matcher(pluginId).matches()) {
      throw new IllegalArgumentException("pluginId must be a canonical lowercase identifier");
    }
  }

  /** 单个 plugin 的冻结目录条目：配置修订号与撰写时的定义/能力身份指纹。 */
  public record Entry(
      long configRevision,
      String pluginCodeVersion,
      String schemaFingerprint,
      String capabilityFingerprint) {

    /** 与 {@link PluginDescriptor#version()} 同一来源的版本长度上限，不引入更窄的第二套限制。 */
    public static final int MAX_CODE_VERSION_LENGTH = PluginDescriptor.MAX_VERSION_CHARS;

    public Entry {
      if (configRevision <= 0) {
        throw new IllegalArgumentException("configRevision must be positive");
      }
      if (pluginCodeVersion == null
          || pluginCodeVersion.isBlank()
          || pluginCodeVersion.length() > MAX_CODE_VERSION_LENGTH
          || !pluginCodeVersion.equals(pluginCodeVersion.strip())) {
        throw new IllegalArgumentException("pluginCodeVersion must be a bounded non-blank text");
      }
      if (!PluginCatalogFingerprint.isFingerprint(schemaFingerprint)) {
        throw new IllegalArgumentException("schemaFingerprint must be a 64-char lowercase SHA-256");
      }
      if (!PluginCatalogFingerprint.isFingerprint(capabilityFingerprint)) {
        throw new IllegalArgumentException(
            "capabilityFingerprint must be a 64-char lowercase SHA-256");
      }
    }
  }
}
