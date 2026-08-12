package fun.fengwk.kkstudio.studio.canvas.function;

import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 启动时从 Link 和 source 节点当前资源冻结出的不可变引用。
 *
 * <p>{@code resourceId} 用于 Run 生命周期 pin 与 manifest 关联，{@code blobId} 用于对象访问；
 * 执行所需媒体事实（kind/name/mediaType/size/metadata）作为快照数据保留。
 */
public record CanvasFunctionFrozenReference(
    UUID sourceNodeId,
    int sourceIndex,
    UUID resourceId,
    UUID blobId,
    CanvasResourceKind kind,
    String name,
    String mediaType,
    long size,
    String metadataJson) {

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
    if (size < 0L) {
      throw new IllegalArgumentException("size must be >= 0");
    }
    requireText(metadataJson, "metadataJson");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
