package fun.fengwk.kkstudio.harness.kernel.execution;

import java.util.Objects;

/**
 * 协调器返回的不可变失败描述。
 *
 * <p>code 用于稳定分类，message 用于诊断。不携带堆栈、cause 或重试策略。
 */
public record Failure(String code, String message) {

  public Failure {
    Objects.requireNonNull(code, "code");
    if (code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    Objects.requireNonNull(message, "message");
  }
}
