package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.work.WorkClaim;

import java.util.Objects;
import java.util.UUID;

/**
 * 一次 Canvas Function claim：已进入 RUNNING 的 Run 快照与其 CANVAS_RUN Work 租约；lease token 是后续所有写入的 ownership
 * fence。
 */
public record ClaimedRun(CanvasFunctionRun run, WorkClaim work) {

  public ClaimedRun {
    Objects.requireNonNull(run, "run");
    Objects.requireNonNull(work, "work");
    if (run.status() != CanvasFunctionRunStatus.RUNNING || !run.nodeId().equals(work.targetId())) {
      throw new IllegalArgumentException("claimed run must be RUNNING under its own work lease");
    }
  }

  public UUID nodeId() {
    return run.nodeId();
  }

  public UUID requestId() {
    return run.requestId();
  }

  public String leaseToken() {
    return work.leaseToken();
  }
}
