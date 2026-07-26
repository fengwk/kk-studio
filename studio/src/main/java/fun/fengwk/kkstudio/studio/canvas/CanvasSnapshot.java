package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

/** Read model for a Canvas document and its graph. */
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
