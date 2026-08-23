package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;

/**
 * 幂等 graph patch：command 响应、changes 回放与 SSE 恢复统一使用它。
 *
 * <p>{@code baseVersion -> version} 表示一次连续前进；version 必须大于 baseVersion，两者都是非负 graph 版本。
 * 每个实体列表都是该次前进的完整变化集：UPSERT 携带完整实体投影，REMOVE 只携带身份。
 */
public record CanvasPatch(
    long baseVersion,
    long version,
    List<CanvasGroupPatch> groups,
    List<CanvasNodePatch> nodes,
    List<CanvasLinkPatch> links) {

  public CanvasPatch {
    if (baseVersion < 0L) {
      throw new IllegalArgumentException("baseVersion must be >= 0");
    }
    if (version < 0L) {
      throw new IllegalArgumentException("version must be >= 0");
    }
    if (version < baseVersion) {
      throw new IllegalArgumentException("version must not be before baseVersion");
    }
    Objects.requireNonNull(groups, "groups");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(links, "links");
    groups = List.copyOf(groups);
    nodes = List.copyOf(nodes);
    links = List.copyOf(links);
  }

  /** 不变的空 patch：version 未前进时用于精确回放幂等响应。 */
  public boolean isEmpty() {
    return groups.isEmpty() && nodes.isEmpty() && links.isEmpty();
  }
}
