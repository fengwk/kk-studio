package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

/** Canvas document 及其 graph 的读模型。 */
public record CanvasSnapshot(
    CanvasDocument document, List<CanvasNode> nodes, List<CanvasLink> links) {

  public CanvasSnapshot {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(nodes, "nodes");
    Objects.requireNonNull(links, "links");
    nodes = List.copyOf(nodes);
    links = List.copyOf(links);
  }
}
