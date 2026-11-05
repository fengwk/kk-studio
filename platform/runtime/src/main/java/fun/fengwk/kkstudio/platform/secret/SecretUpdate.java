package fun.fengwk.kkstudio.platform.secret;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 秘密字段更新操作：{@code KEEP} / {@code SET} / {@code CLEAR} 三态。
 *
 * <p>KEEP 与 CLEAR 禁止携带值；SET 必须给出非空、无首尾空白、有界且非纯遮罩占位符的秘密值。 任何 {@link #toString()} 均不得回显实际秘密值。
 */
public record SecretUpdate(Operation operation, @Nullable String value) {

  /** 单个秘密值的 UTF-8 字符及字节上限；超出即拒绝，避免无界密文。 */
  public static final int MAX_SECRET_CHARS = 4096;

  /** 常见遮罩字符：只由这些字符组成的「值」是占位符而不是有效秘密。 */
  private static final Pattern MASK_ONLY = Pattern.compile("[*•·●◦⁃\\-\\s]+");

  public enum Operation {
    KEEP,
    SET,
    CLEAR
  }

  public SecretUpdate {
    operation = Objects.requireNonNull(operation, "operation");
    switch (operation) {
      case KEEP, CLEAR -> {
        if (value != null) {
          throw new IllegalArgumentException(operation + " must not carry a value");
        }
      }
      case SET -> {
        if (value == null || value.isBlank()) {
          throw new IllegalArgumentException("SET requires a non-blank value");
        }
        if (!value.equals(value.strip())) {
          throw new IllegalArgumentException("SET value must not have surrounding whitespace");
        }
        if (value.length() > MAX_SECRET_CHARS
            || value.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_CHARS) {
          throw new IllegalArgumentException(
              "SET value must not exceed " + MAX_SECRET_CHARS + " UTF-8 bytes");
        }
        if (MASK_ONLY.matcher(value).matches()) {
          throw new IllegalArgumentException("SET value must not be a mask placeholder");
        }
      }
    }
  }

  /** 保留现有秘密，不进行修改。 */
  public static SecretUpdate keep() {
    return new SecretUpdate(Operation.KEEP, null);
  }

  /** 更新为新的有效秘密。 */
  public static SecretUpdate set(String value) {
    return new SecretUpdate(Operation.SET, value);
  }

  /** 清除现有秘密。 */
  public static SecretUpdate clear() {
    return new SecretUpdate(Operation.CLEAR, null);
  }

  @Override
  public String toString() {
    return "SecretUpdate[operation=" + operation + ", value=<redacted>]";
  }
}
