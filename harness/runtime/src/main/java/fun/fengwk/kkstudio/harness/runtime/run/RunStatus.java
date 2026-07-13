package fun.fengwk.kkstudio.harness.runtime.run;

import java.util.EnumSet;
import java.util.Set;

/** Durable Run 的持久状态；只有这里声明的边允许业务迁移。 */
public enum RunStatus {
  QUEUED,
  RUNNING,
  WAITING_TOOLS,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  private static final Set<RunStatus> TERMINAL = EnumSet.of(SUCCEEDED, FAILED, CANCELLED);

  public boolean canTransitionTo(RunStatus target) {
    return switch (this) {
      case QUEUED -> target == RUNNING;
      case RUNNING -> target == SUCCEEDED
          || target == FAILED
          || target == CANCELLED
          || target == WAITING_TOOLS
          || target == QUEUED;
      case WAITING_TOOLS -> target == QUEUED || target == CANCELLED || target == FAILED;
      case SUCCEEDED, FAILED, CANCELLED -> false;
    };
  }

  public void requireTransitionTo(RunStatus target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException("illegal run transition: " + this + " -> " + target);
    }
  }

  public boolean terminal() {
    return TERMINAL.contains(this);
  }
}
