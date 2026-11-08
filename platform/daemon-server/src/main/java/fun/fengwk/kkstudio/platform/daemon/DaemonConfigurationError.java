package fun.fengwk.kkstudio.platform.daemon;

import java.util.regex.Pattern;

/**
 * 配置应用失败的稳定规则错误。
 *
 * <p>{@code code} 只来自既有稳定错误集合；{@code field} 描述规则所在字段，绝不包含用户输入值、命令参数或 token。
 */
public record DaemonConfigurationError(long version, String code, String field) {

  private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

  public DaemonConfigurationError {
    if (version < 0L) {
      throw new IllegalArgumentException("version must be non-negative");
    }
    if (code == null || !CODE_PATTERN.matcher(code).matches()) {
      throw new IllegalArgumentException("code must be a stable upper-snake-case identifier");
    }
    if (field != null
        && (field.isBlank()
            || field.length() > 256
            || field.codePoints().anyMatch(Character::isISOControl))) {
      throw new IllegalArgumentException("field must be a bounded single-line identifier");
    }
  }
}
