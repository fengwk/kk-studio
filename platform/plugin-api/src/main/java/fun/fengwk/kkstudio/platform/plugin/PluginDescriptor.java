package fun.fengwk.kkstudio.platform.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Plugin 的安装身份与固定 region 候选。
 *
 * @param pluginId 全局唯一不可变安装身份，canonical 小写点划线标识符（与 {@code plugin_credential.plugin_id} 约束一致，≤64）
 * @param name 展示名
 * @param version Plugin 版本
 * @param regions 固定 deep-link region 候选；非空即表示该 Plugin 提供 {@code DEEP_LINK} 认证交互，调用方只能选择其中之一
 */
public record PluginDescriptor(String pluginId, String name, String version, List<String> regions) {

  /** 与 {@code plugin_credential.plugin_id} 列约束完全一致的安装身份语法。 */
  private static final Pattern PLUGIN_ID_SYNTAX = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private static final Pattern REGION_SYNTAX = Pattern.compile("[A-Za-z0-9_-]+");

  public static final int MAX_PLUGIN_ID_LENGTH = 64;

  public static final int MAX_NAME_CHARS = 128;

  public static final int MAX_VERSION_CHARS = 64;

  public static final int MAX_REGION_CHARS = 64;

  public static final int MAX_REGIONS = 8;

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
    Objects.requireNonNull(regions, "regions");
    if (regions.size() > MAX_REGIONS) {
      throw new IllegalArgumentException("regions must not exceed " + MAX_REGIONS + " candidates");
    }
    List<String> validated = new ArrayList<>(regions.size());
    for (String region : regions) {
      String candidate = requireBounded(region, "regions[]", MAX_REGION_CHARS);
      if (!REGION_SYNTAX.matcher(candidate).matches()) {
        throw new IllegalArgumentException("regions[] must match [A-Za-z0-9_-]+");
      }
      if (validated.contains(candidate)) {
        throw new IllegalArgumentException("regions[] must not repeat: " + candidate);
      }
      validated.add(candidate);
    }
    regions = List.copyOf(validated);
  }

  /** 是否声明了固定 region 候选，即是否提供 {@code DEEP_LINK} 认证交互。 */
  public boolean supportsAuthentication() {
    return !regions.isEmpty();
  }

  /** 给定 region 是否为 descriptor 声明的候选之一；大小写敏感，不做别名或默认回退。 */
  public boolean acceptsRegion(String region) {
    return region != null && regions.contains(region);
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
