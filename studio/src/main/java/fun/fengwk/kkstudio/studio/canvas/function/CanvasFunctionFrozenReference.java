package fun.fengwk.kkstudio.studio.canvas.function;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.util.Objects;

/** Run 启动时从 Link 和 source 节点当前资源冻结出的不可变引用。 */
public record CanvasFunctionFrozenReference(
    long sourceNodeId,
    int sourceIndex,
    long resourceId,
    CanvasResourceKind kind,
    String name,
    String mediaType,
    long size,
    String metadataJson) {

  public CanvasFunctionFrozenReference {
    if (sourceNodeId <= 0L || resourceId <= 0L) {
      throw new IllegalArgumentException("sourceNodeId/resourceId must be > 0");
    }
    if (sourceIndex < 0 || size < 0L) {
      throw new IllegalArgumentException("sourceIndex/size must be >= 0");
    }
    Objects.requireNonNull(kind, "kind");
    requireText(name, "name");
    requireText(mediaType, "mediaType");
    requireText(metadataJson, "metadataJson");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
