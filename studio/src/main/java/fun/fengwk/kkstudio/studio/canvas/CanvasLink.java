package fun.fengwk.kkstudio.studio.canvas;

/**
 * 可见性边：source 资源变为对 target node 可见。
 *
 * <p>由规范构造函数强制的不变量：
 *
 * <ul>
 *   <li>{@code id > 0}、{@code sourceNodeId > 0}、{@code targetNodeId > 0}
 *   <li>{@code sourceNodeId != targetNodeId}
 * </ul>
 */
public record CanvasLink(long id, long sourceNodeId, long targetNodeId) {

  public CanvasLink {
    if (id <= 0L) {
      throw new IllegalArgumentException("id must be > 0");
    }
    if (sourceNodeId <= 0L) {
      throw new IllegalArgumentException("sourceNodeId must be > 0");
    }
    if (targetNodeId <= 0L) {
      throw new IllegalArgumentException("targetNodeId must be > 0");
    }
    if (sourceNodeId == targetNodeId) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
