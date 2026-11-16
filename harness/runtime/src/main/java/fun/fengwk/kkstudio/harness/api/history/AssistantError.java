package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.model.ModelFailureMetadata;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Assistant-side 失败审计 Entry 的最小快照：stable error code、人类可读 message 与可选 Provider 诊断。
 *
 * <p>message 原样保留上游完整错误正文（含换行与首尾空白）。这使 terminal generation 错误可以落成 ASSISTANT_ERROR Entry。UI / audit
 * only；不投影到上下文。重试生命周期属于对应的 Driver 私有执行。
 */
public record AssistantError(
    String code, String message, String providerErrorCode, ModelFailureMetadata metadata) {

  /** Stop 写入取消屏障时使用的稳定 error code。 */
  public static final String CANCELLED_CODE = "CANCELLED";

  private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]*");

  private static final Pattern PROVIDER_CODE_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");

  private static final int CODE_MAX_LENGTH = 64;

  public AssistantError {
    code = requireErrorCode(code);
    message = requireMessage(message);
    if (providerErrorCode != null
        && (providerErrorCode.length() > 128
            || !PROVIDER_CODE_PATTERN.matcher(providerErrorCode).matches())) {
      throw new IllegalArgumentException("providerErrorCode is invalid");
    }
  }

  public AssistantError(String code, String message) {
    this(code, message, null, null);
  }

  private static String requireErrorCode(String value) {
    Objects.requireNonNull(value, "code");
    if (!CODE_PATTERN.matcher(value).matches()) {
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
