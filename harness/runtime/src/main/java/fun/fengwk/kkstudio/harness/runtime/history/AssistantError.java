package fun.fengwk.kkstudio.harness.runtime.history;

import java.util.Objects;

/**
 * Assistant-side 失败审计 Entry 的最小快照：stable error code 与人类可读 message。
 *
 * <p>code 必须匹配 {@code [A-Z][A-Z0-9_]*} 且不超过 64 字符；message 必须非 blank，长度与首尾空白不受限制，原样保留上游完整错误正文
 * （含换行与首尾空白）。这与 {@code ModelInvocationError} 的非 blank 契约保持一致，使 terminal Model 错误可以落成
 * ASSISTANT_ERROR Entry。UI / audit only；不投影到 Provider Context。retry 生命周期属于对应的 ModelInvocation。
 */
public record AssistantError(String code, String message) {

  /** Stop 写入取消屏障时使用的稳定 error code。 */
  public static final String CANCELLED_CODE = "CANCELLED";

  private static final int CODE_MAX_LENGTH = 64;

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
    return value;
  }
}
