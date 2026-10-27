package fun.fengwk.kkstudio.harness.runtime.execution;

import java.util.Objects;

/** journal 中已经存在同一 execution 的其它身份或目标证据；调用方不得覆盖或把它当作可无限重试的瞬态失败。 */
public final class ExecutionExitJournalConflictException extends ExecutionExitJournalException {

  public enum Conflict {
    IDENTITY,
    TARGET
  }

  private final Conflict conflict;

  public ExecutionExitJournalConflictException(Conflict conflict, String message) {
    super(message);
    this.conflict = Objects.requireNonNull(conflict, "conflict");
  }

  public ExecutionExitJournalConflictException(Conflict conflict, String message, Throwable cause) {
    super(message, cause);
    this.conflict = Objects.requireNonNull(conflict, "conflict");
  }

  public Conflict conflict() {
    return conflict;
  }
}
