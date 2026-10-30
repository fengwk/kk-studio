package fun.fengwk.kkstudio.platform.pluginhost.service;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 单个秘密字段的补丁操作：{@code KEEP} / {@code SET} / {@code CLEAR} 三态。
 *
 * <p>省略即 KEEP；KEEP 与 CLEAR 禁止携带值；SET 必须给出非空、无 padding、有界的秘密值，且拒绝纯遮罩占位符（例如只由 {@code *} 或 {@code •}
 * 组成）。任何 {@link #toString()}、异常或日志都不得回显值。
 */
public record PluginSecretPatch(Operation operation, String value) {

  /** 单个秘密值的 UTF-8 字符上限；超出即拒绝，避免无界密文。 */
  public static final int MAX_SECRET_CHARS = 4096;

  /** 常见遮罩字符：只由这些字符组成的「值」是占位符而不是秘密。 */
  private static final Pattern MASK_ONLY = Pattern.compile("[*•·●◦⁃\\-\\s]+");

  public enum Operation {
    KEEP,
    SET,
    CLEAR
  }

  public PluginSecretPatch {
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

  /** 省略字段的默认补丁。 */
  public static PluginSecretPatch keep() {
    return new PluginSecretPatch(Operation.KEEP, null);
  }

  public static PluginSecretPatch set(String value) {
    return new PluginSecretPatch(Operation.SET, value);
  }

  public static PluginSecretPatch clear() {
    return new PluginSecretPatch(Operation.CLEAR, null);
  }

  @Override
  public String toString() {
    return "PluginSecretPatch[operation=" + operation + ", value=<redacted>]";
  }
}
