package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;

/** 命令批的处理结果：接受回执或具体冲突，两者都不含新的编辑基线。 */
public sealed interface CanvasCommandResult
    permits CanvasCommandResult.Accepted, CanvasCommandResult.Conflicted {

  /**
   * 已接受。
   *
   * <p>首次接受的 {@link #patch()} 携带本次前进的完整变化集与结果 revision；精确重放的接受回执只携带当时记录的 revision
   * 与空变化集，表示请求已经生效，客户端按 revision 自行补齐。
   */
  record Accepted(CanvasPatch patch) implements CanvasCommandResult {

    public Accepted {
      Objects.requireNonNull(patch, "patch");
    }
  }

  /** 被拒绝：全部冲突及服务端权威值，本次批没有任何写入。 */
  record Conflicted(List<CanvasConflict> conflicts) implements CanvasCommandResult {

    public Conflicted {
      Objects.requireNonNull(conflicts, "conflicts");
      conflicts = List.copyOf(conflicts);
      if (conflicts.isEmpty()) {
        throw new IllegalArgumentException("conflicts must not be empty");
      }
    }
  }
}
