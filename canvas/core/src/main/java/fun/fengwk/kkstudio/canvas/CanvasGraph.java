package fun.fengwk.kkstudio.canvas;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 命令规划使用的画布权威事实快照。
 *
 * <p>{@code pinnedResourceIds} 是被任一活跃 Run pin 的资源：它们即使不再被节点持有也必须保留行与内容，因此规划只能解除挂接而不能删除。
 */
public record CanvasGraph(
    UUID canvasId,
    List<CanvasResourceNode> nodes,
    List<CanvasGroup> groups,
    Set<UUID> pinnedResourceIds) {

  public CanvasGraph {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(groups, "groups");
    Objects.requireNonNull(pinnedResourceIds, "pinnedResourceIds");
    nodes = List.copyOf(nodes);
    groups = List.copyOf(groups);
    pinnedResourceIds = new HashSet<>(pinnedResourceIds);
  }
}
