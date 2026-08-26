package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Agent tool 的稳定 canonical 公共身份。
 *
 * <p>该身份只由小写字母、数字以及单个 {@code '.'} 或 {@code '-'} 分隔符组成，适合作为 settings、日志和 wire key。
 */
public record AgentToolId(String value) {

  /** Agent tool identity 的最大字符数。 */
  public static final int MAX_LENGTH = 128;

  private static final Pattern CANONICAL = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  public AgentToolId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("value must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("value must be at most " + MAX_LENGTH + " characters");
    }
    if (!CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException("value must use canonical agent tool id syntax");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
