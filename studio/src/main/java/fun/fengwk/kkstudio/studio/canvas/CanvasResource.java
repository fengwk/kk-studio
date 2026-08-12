package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已完成校验并持久化的不可变资源，内容要么是 Storage blob 要么是内联文本。
 *
 * <p>可见资源由 {@code ownerNodeId + resourceIndex} 直接归属节点；Function Run 物化中的目标资源或已删除源节点留下的 pinned
 * 输入资源可以暂时无 owner，直到成功挂接或最后一个 pin 释放。
 */
public record CanvasResource(
    UUID id,
    UUID canvasId,
    UUID ownerNodeId,
    Integer resourceIndex,
    UUID blobId,
    String name,
    String textContent,
    Instant createdAt) {

  public CanvasResource {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(canvasId, "canvasId");
    if ((ownerNodeId == null) != (resourceIndex == null)) {
      throw new IllegalArgumentException(
          "ownerNodeId and resourceIndex must be both present or both absent");
    }
    if (resourceIndex != null && resourceIndex < 0) {
      throw new IllegalArgumentException("resourceIndex must be >= 0");
    }
    if ((blobId == null) == (textContent == null)) {
      throw new IllegalArgumentException("exactly one of blobId/textContent must be present");
    }
    CanvasValidation.requireNonBlank(name, "name");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** 内容是否为 Storage blob。 */
  public boolean isBlob() {
    return blobId != null;
  }

  /** 内容是否为内联文本。 */
  public boolean isText() {
    return textContent != null;
  }
}
