package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

/** Canvas document 及其 graph 的完整读模型。 */
public record CanvasSnapshot(
    CanvasDocument document,
    List<CanvasResourceNode> nodes,
    List<CanvasGroup> groups,
    List<CanvasLink> links) {

  public CanvasSnapshot {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(groups, "groups");
    Objects.requireNonNull(links, "links");
    nodes = List.copyOf(nodes);
    groups = List.copyOf(groups);
    links = List.copyOf(links);
  }
}
