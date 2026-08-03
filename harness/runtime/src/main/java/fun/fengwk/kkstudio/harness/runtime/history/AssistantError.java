package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * Assistant-side 失败审计 Entry 的最小快照：stable error code 与人类可读 message。
 *
 * <p>code 必须匹配 {@code [A-Z][A-Z0-9_]*} 且不超过 64 字符；message 必须非空、无首尾空白且不超过 2048 字符。 UI / audit
 * only；不投影到 Provider Context。retry 生命周期属于对应的 ModelInvocation。
 */
public record AssistantError(String code, String message) {

  private static final int CODE_MAX_LENGTH = 64;
  private static final int MESSAGE_MAX_LENGTH = 2048;

  public AssistantError {
    code = requireErrorCode(code);
    message = requireMessage(message);
  }

  private static String requireErrorCode(String value) {
    Objects.requireNonNull(value, "code");
    if (!value.matches("[A-Z][A-Z0-9_]*")) {
      throw new IllegalArgumentException("code must match [A-Z][A-Z0-9_]*");
    }
    if (value.length() > CODE_MAX_LENGTH) {
      throw new IllegalArgumentException("code must be <= " + CODE_MAX_LENGTH + " characters");
    }
    return value;
  }

  private static String requireMessage(String value) {
    Objects.requireNonNull(value, "message");
    if (value.isBlank()) {
      throw new IllegalArgumentException("message must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("message must not contain surrounding whitespace");
    }
    if (value.length() > MESSAGE_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "message must be <= " + MESSAGE_MAX_LENGTH + " characters");
    }
    return value;
  }
}
