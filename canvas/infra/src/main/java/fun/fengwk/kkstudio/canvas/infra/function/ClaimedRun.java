package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;

import java.util.Objects;
import java.util.UUID;

/** 一次 Canvas Function claim 的完整快照；lease token 是后续所有写入的 ownership fence。 */
public record ClaimedRun(CanvasFunctionRun run) {

  public ClaimedRun {
    Objects.requireNonNull(run, "run");
    if (run.status() != CanvasFunctionRunStatus.RUNNING || run.leaseToken() == null) {
      throw new IllegalArgumentException("claimed run must be RUNNING with a lease");
    }
  }

  public UUID nodeId() {
    return run.nodeId();
  }

  public UUID requestId() {
    return run.requestId();
  }

  public String leaseToken() {
    return run.leaseToken();
  }
}
