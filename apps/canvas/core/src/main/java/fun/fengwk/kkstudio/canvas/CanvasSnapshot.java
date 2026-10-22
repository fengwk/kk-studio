package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.Objects;

/** Canvas document 及其节点、分组与引用连线的完整读模型。 */
public record CanvasSnapshot(
    CanvasDocument document,
    List<CanvasResourceNode> nodes,
    List<CanvasGroup> groups,
    List<CanvasReference> references) {

  public CanvasSnapshot {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(groups, "groups");
    Objects.requireNonNull(references, "references");
    nodes = List.copyOf(nodes);
    groups = List.copyOf(groups);
    references = List.copyOf(references);
  }
}
