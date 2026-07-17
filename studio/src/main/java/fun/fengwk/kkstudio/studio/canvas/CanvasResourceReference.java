package fun.fengwk.kkstudio.studio.canvas;

import fun.fengwk.kkstudio.studio.model.ResourceSelector;

import java.util.Objects;

/** Actual dependency edge used by propagation and Function inputs. */
public record CanvasResourceReference(
    long id,
    long canvasId,
    long targetNodeId,
    String targetPath,
    long visibilityLinkId,
    long dependencySourceNodeId,
    ResourceSelector selector,
    long revision) {

  public CanvasResourceReference {
    Objects.requireNonNull(targetPath, "targetPath");
    Objects.requireNonNull(selector, "selector");
  }
}
