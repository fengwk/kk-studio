package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;

/**
 * 一次已提交变化的实体 patch。
 *
 * <p>{@code revision} 是服务端接受位置，用于同步排序、补漏与确认接受，不是普通编辑的整图前置版本。每个实体列表都是本次前进的完整变化集： UPSERT
 * 携带完整投影，REMOVE 只携带身份。重复消息可忽略，revision 有缺口时读取快照。
 */
public record CanvasPatch(
    long revision, List<CanvasNodePatch> nodes, List<CanvasGroupPatch> groups) {

  public CanvasPatch {
    if (revision < 0L) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(groups, "groups");
    nodes = List.copyOf(nodes);
    groups = List.copyOf(groups);
  }

  /** 只携带接受位置的空 patch：用于已接受请求的精确重放回执。 */
  public static CanvasPatch receipt(long revision) {
    return new CanvasPatch(revision, List.of(), List.of());
  }

  /** 本次前进是否没有实体变化（精确重放或纯 no-op 批）。 */
  public boolean isEmpty() {
    return nodes.isEmpty() && groups.isEmpty();
  }
}
