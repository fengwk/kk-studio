package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.Objects;
import java.util.UUID;

/** 单个 journal 文件无法安全读取的原因；原文件名与异常只供内部定位，不进入日志。 */
public record ExecutionExitJournalFailure(String fileName, Reason reason, Throwable cause) {

  public enum Reason {
    INVALID_FILE_TYPE,
    UNREADABLE,
    CORRUPT,
    IDENTITY_MISMATCH
  }

  public ExecutionExitJournalFailure {
    fileName = Objects.requireNonNull(fileName, "fileName");
    reason = Objects.requireNonNull(reason, "reason");
  }

  /** 仅当文件名是严格的 canonical {@code <uuid>.json} 时返回该 UUID，供日志定位。 */
  public UUID canonicalExecutionId() {
    String suffix = ".json";
    if (!fileName.endsWith(suffix)) {
      return null;
    }
    String text = fileName.substring(0, fileName.length() - suffix.length());
    try {
      UUID executionId = UUID.fromString(text);
      return executionId.toString().equals(text) ? executionId : null;
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  /** 日志只能使用异常简单类名，不能输出 message 或堆栈。 */
  public String exceptionClass() {
    return cause == null ? "-" : cause.getClass().getSimpleName();
  }
}
