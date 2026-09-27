package fun.fengwk.kkstudio.canvas.function;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.Objects;
import java.util.UUID;

/**
 * Run 冻结输出计划中的一个槽位：预分配 Resource ID、节点内位置、类型与资源名。
 *
 * <p>adapter 只能物化计划内的槽位，且只能用与该槽位类型匹配的物化入口（文本内联 / 媒体 Blob）。
 */
public record CanvasFunctionFrozenOutput(
    UUID resourceId, int index, CanvasResourceKind kind, String name) {

  public CanvasFunctionFrozenOutput {
    Objects.requireNonNull(resourceId, "resourceId");
    if (index < 0) {
      throw new IllegalArgumentException("output index must be nonnegative");
    }
    Objects.requireNonNull(kind, "kind");
    if (name == null
        || name.isBlank()
        || !name.equals(name.strip())
        || name.length() > CanvasFunctionOutputSpec.MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("output name must be non-blank canonical text");
    }
  }

  /** 该槽位是否以内联文本发布（没有 Blob）。 */
  public boolean inlineText() {
    return kind == CanvasResourceKind.TEXT;
  }
}
