package fun.fengwk.kkstudio.studio.canvas;

/** Visibility edge: source resources become visible to the target node. */
public record CanvasLink(
    long id, long canvasId, long sourceNodeId, long targetNodeId, long revision) {}
