package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.common.error.DomainException;

/**
 * 人工核查与 UNKNOWN 收敛的文本入口：非空白、去控制字符、按列宽截断。
 *
 * <p>两者共用同一条清洗规则但错误类型不同：{@code reason} 是 Worker 侧的运行时说明（{@link IllegalArgumentException}）， {@code
 * verification} 是客户端提交的核查事实（领域校验错误）。
 */
final class CanvasFunctionRunTexts {

  private static final int MAX_UNKNOWN_REASON_LENGTH = 1024;
  private static final int MAX_VERIFICATION_LENGTH = 1024;

  private CanvasFunctionRunTexts() {}

  static String validateUnknownReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("unknown reason must not be blank");
    }
    String stripped = stripControlCharacters(reason.strip());
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException("unknown reason must not be blank");
    }
    return stripped.length() > MAX_UNKNOWN_REASON_LENGTH
        ? stripped.substring(0, MAX_UNKNOWN_REASON_LENGTH)
        : stripped;
  }

  static String validateVerification(String verification) {
    if (verification == null || verification.isBlank()) {
      throw DomainException.validation("verification", "verification must not be blank");
    }
    String stripped = stripControlCharacters(verification.strip());
    if (stripped.isEmpty()) {
      throw DomainException.validation("verification", "verification must not be blank");
    }
    return stripped.length() > MAX_VERIFICATION_LENGTH
        ? stripped.substring(0, MAX_VERIFICATION_LENGTH)
        : stripped;
  }

  private static String stripControlCharacters(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      builder.append(Character.isISOControl(character) ? ' ' : character);
    }
    return builder.toString().strip();
  }
}
