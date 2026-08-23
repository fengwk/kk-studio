package fun.fengwk.kkstudio.platform.systemsettings;

import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPathPattern;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.net.URI;

/**
 * {@link SystemSettings} 各 section 共享的字段级校验工具。
 *
 * <p>独立于 {@code SystemSettings} 顶层类，避免「嵌套 record 的 {@code DEFAULT} 常量在构造时调用外层静态方法」导致 {@code
 * SystemSettings} 与嵌套 record 之间循环类初始化：Jackson 或其他代码先触发嵌套 record 类初始化时，外层静态方法 的调用会重新进入 {@code
 * SystemSettings.<clinit>}，从而产生空引用。本类只持有无状态静态方法，无初始化依赖。
 */
final class SystemSettingsValidation {

  private SystemSettingsValidation() {}

  /**
   * 校验单条 permission path pattern：必须能被 runtime 的 {@link PermissionPathPattern}（JGit gitignore
   * 语义）编译，且不得是 negation（{@code !} 前缀）、comment-only、空白或无效形态；转义后的 literal（{@code \!} / {@code
   * \#}）放行，语义交给匹配器。 platform 不直接依赖 JGit，这里只委托公共 API。
   */
  static void requireValidPattern(String pattern) {
    if (pattern == null) {
      throw new IllegalArgumentException("tool.permission pattern must not be null");
    }
    if (!pattern.equals(pattern.strip())) {
      throw new IllegalArgumentException(
          "tool.permission pattern must not contain surrounding whitespace");
    }
    PermissionPathPattern.validate(pattern);
  }

  static String requirePresentableUrl(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    return value.strip();
  }

  /** 返回规范化 {@code http(s)://host[:port][/]} 形式的 base URL；null 表示未配置。 */
  static String requireHttpOrigin(String value, String field) {
    String trimmed = requirePresentableUrl(value, field);
    if (trimmed == null) {
      return null;
    }
    URI uri;
    try {
      uri = URI.create(trimmed);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be an HTTP(S) origin", error);
    }
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          field + " must be an HTTP(S) origin without path, userinfo, query or fragment");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !"/".equals(path)) {
      throw new IllegalArgumentException(field + " must be an HTTP(S) origin without path");
    }
    return trimmed;
  }

  static String requireOptionalBoundedText(String value, int maxLength, String field) {
    if (value == null) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    String stripped = value.strip();
    if (stripped.isEmpty() || stripped.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " must be absent or 1.." + maxLength + " characters");
    }
    return stripped;
  }

  static String requireOptionalAgentName(String value, String field) {
    if (value == null) {
      return null;
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    String stripped = value.strip();
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (stripped.indexOf('/') >= 0) {
      throw new IllegalArgumentException(field + " must not contain '/'");
    }
    if (stripped.length() > 64) {
      throw new IllegalArgumentException(field + " must be at most 64 characters");
    }
    return stripped;
  }

  static String requireOptionalEnvironmentName(String value, String field) {
    if (value == null) {
      return null;
    }
    try {
      return new EnvironmentName(value).value();
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " " + error.getMessage(), error);
    }
  }

  static void requirePositiveMillis(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  static void requireNonNegativeMillis(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
  }

  static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  static void requireNonNegativeInt(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
  }

  static void requireAtLeast(int value, int minimum, String field) {
    if (value < minimum) {
      throw new IllegalArgumentException(field + " must be at least " + minimum);
    }
  }

  static void requirePositiveBounded(long value, long maximum, String field) {
    if (value <= 0 || value > maximum) {
      throw new IllegalArgumentException(field + " must be positive and at most " + maximum);
    }
  }

  static void requireBounded(long value, long minimum, long maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }

  static void requireBounded(int value, int minimum, int maximum, String field) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum);
    }
  }
}
