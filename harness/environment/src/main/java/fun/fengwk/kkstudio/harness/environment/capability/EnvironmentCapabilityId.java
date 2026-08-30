package fun.fengwk.kkstudio.harness.environment.capability;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Environment Capability 的稳定 canonical 身份。
 *
 * <p>该身份使用严格的 canonical 语法和长度上限，属于独立 namespace 和类型，不与模型 Tool 身份互相转换。
 */
public record EnvironmentCapabilityId(String value) {

  /** Environment Capability identity 的最大字符数。 */
  public static final int MAX_LENGTH = 128;

  private static final Pattern CANONICAL = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  public EnvironmentCapabilityId {
    Objects.requireNonNull(value, "value");
    if (value.isEmpty()) {
      throw new IllegalArgumentException("value must not be empty");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("value must be at most " + MAX_LENGTH + " characters");
    }
    if (!CANONICAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "value must use canonical environment capability id syntax");
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
