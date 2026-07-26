package fun.fengwk.kkstudio.studio.canvas;

/**
 * Visibility edge: source resources become visible to the target node.
 *
 * <p>Invariants enforced by the canonical constructor:
 *
 * <ul>
 *   <li>{@code id > 0}, {@code sourceNodeId > 0}, {@code targetNodeId > 0}
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
