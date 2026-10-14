package fun.fengwk.kkstudio.platform.plugin;

import java.util.regex.Pattern;

/**
 * Plugin 的安装身份。
 *
 * <p>descriptor 只声明身份：{@code pluginId} 全局唯一且不可变，认证候选与配置契约分别在 {@link
 * StudioPlugin#authenticationSpec()} 与 {@link StudioPlugin#configurationSchema()} 中声明。
 *
 * @param pluginId 全局唯一不可变安装身份，canonical 小写点划线标识符（与 {@code plugin_credential.plugin_id} 约束一致，≤64）
 * @param name 展示名
 * @param version Plugin 版本
 */
public record PluginDescriptor(String pluginId, String name, String version) {

  /** 与 {@code plugin_credential.plugin_id} 列约束完全一致的安装身份语法。 */
  private static final Pattern PLUGIN_ID_SYNTAX = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  public static final int MAX_PLUGIN_ID_LENGTH = 64;

  public static final int MAX_NAME_CHARS = 128;

  public static final int MAX_VERSION_CHARS = 64;

  public PluginDescriptor {
    if (pluginId == null
        || pluginId.length() > MAX_PLUGIN_ID_LENGTH
        || !PLUGIN_ID_SYNTAX.matcher(pluginId).matches()) {
      throw new IllegalArgumentException(
          "pluginId must be a canonical lowercase dotted/dashed identifier of at most "
              + MAX_PLUGIN_ID_LENGTH
              + " characters");
    }
    name = requireBounded(name, "name", MAX_NAME_CHARS);
    version = requireBounded(version, "version", MAX_VERSION_CHARS);
  }

  private static String requireBounded(String value, String field, int maxChars) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not have surrounding whitespace");
    }
    if (value.length() > maxChars) {
      throw new IllegalArgumentException(field + " must not exceed " + maxChars + " characters");
    }
    return value;
  }
}
