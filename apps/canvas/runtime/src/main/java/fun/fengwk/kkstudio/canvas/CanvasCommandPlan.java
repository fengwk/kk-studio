package fun.fengwk.kkstudio.canvas;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 命令批的规划结果：可提交的持久化步骤、接受后的实体 patch 与冲突。
 *
 * <p>存在冲突时步骤与 patch 都不生效：批要么全部提交，要么全部回滚，不会留下部分写入。
 */
public record CanvasCommandPlan(
    List<CanvasMutation> mutations,
    List<CanvasNodePatch> nodes,
    List<CanvasGroupPatch> groups,
    List<CanvasConflict> conflicts) {

  public CanvasCommandPlan {
    Objects.requireNonNull(mutations, "mutations");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(groups, "groups");
    Objects.requireNonNull(conflicts, "conflicts");
    Set<CanvasConflict> distinct = new LinkedHashSet<>(conflicts);
    mutations = List.copyOf(mutations);
    nodes = List.copyOf(nodes);
    groups = List.copyOf(groups);
    conflicts = List.copyOf(distinct);
  }

  /** 批是否被前置条件或节点状态拒绝。 */
  public boolean isRejected() {
    return !conflicts.isEmpty();
  }

  /** 批是否产生了需要提交的行级变化；纯 no-op 批不会推进 revision。 */
  public boolean hasChanges() {
    return !mutations.isEmpty();
  }

  /** 构造接受结果；无变化时返回只携带接受位置的空 patch。 */
  public CanvasPatch toPatch(long revision) {
    if (!hasChanges()) {
      return CanvasPatch.receipt(revision);
    }
    return new CanvasPatch(revision, nodes, groups);
  }
}
