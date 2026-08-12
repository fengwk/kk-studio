package fun.fengwk.kkstudio.studio.canvas.function;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 启动时从 Link 和 source 节点当前资源冻结出的不可变引用。
 *
 * <p>{@code resourceId} 用于 Run 生命周期 pin 与 manifest 关联，{@code blobId} 用于对象访问； 执行所需媒体事实
 * （kind/name/mediaType/sizeBytes/width/height/durationMs）作为权威 blob 事实的快照保留。
 */
public record CanvasFunctionFrozenReference(
    UUID sourceNodeId,
    int sourceIndex,
    UUID resourceId,
    UUID blobId,
    CanvasResourceKind kind,
    String name,
    String mediaType,
    long sizeBytes,
    Long width,
    Long height,
    Long durationMs) {

  public CanvasFunctionFrozenReference {
    Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    if (sourceIndex < 0) {
      throw new IllegalArgumentException("sourceIndex must be >= 0");
    }
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(kind, "kind");
    requireText(name, "name");
    requireText(mediaType, "mediaType");
    if (sizeBytes < 0L) {
      throw new IllegalArgumentException("sizeBytes must be >= 0");
    }
    if ((width == null) != (height == null)) {
      throw new IllegalArgumentException("width and height must both be set or both be null");
    }
    if (width != null && (width <= 0 || height <= 0)) {
      throw new IllegalArgumentException("width and height must be positive when set");
    }
    if (durationMs != null && durationMs <= 0) {
      throw new IllegalArgumentException("durationMs must be positive when set");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
