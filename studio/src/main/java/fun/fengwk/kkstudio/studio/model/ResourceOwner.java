package fun.fengwk.kkstudio.studio.model;

/** Owner of a logical Resource address. */
public sealed interface ResourceOwner {

  record CanvasNode(long canvasId, long nodeId) implements ResourceOwner {}

  record FunctionRun(long runId) implements ResourceOwner {}
}
