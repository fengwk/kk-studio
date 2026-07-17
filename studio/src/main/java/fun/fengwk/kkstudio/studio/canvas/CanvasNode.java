package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

public record CanvasNode(
    long id,
    long canvasId,
    CanvasNodeKind kind,
    String nodeType,
    int nodeTypeVersion,
    String name,
    Long parentGroupId,
    NodeTransform transform,
    long zIndex,
    boolean locked,
    boolean hidden,
    NodeValidity validity,
    long revision,
    String dataJson) {

  public CanvasNode {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(nodeType, "nodeType");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(transform, "transform");
    Objects.requireNonNull(validity, "validity");
    Objects.requireNonNull(dataJson, "dataJson");
  }
}
