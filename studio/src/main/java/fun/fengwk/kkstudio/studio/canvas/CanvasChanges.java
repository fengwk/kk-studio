package fun.fengwk.kkstudio.studio.canvas;

import java.util.List;
import java.util.Objects;

/**
 * {@code GET /canvases/{id}/changes?afterVersion=N} 的恢复载荷。
 *
 * <p>要么是从 afterVersion 起连续的 patches（客户端逐个应用），要么是必须整体替换当前状态的 snapshot（gap / 压缩 / 服务端无法增量）；snapshot
 * 非空时 patches 必须为空。
 */
public record CanvasChanges(List<CanvasPatch> patches, CanvasSnapshot snapshot) {

  public CanvasChanges {
    Objects.requireNonNull(patches, "patches");
    patches = List.copyOf(patches);
    if (snapshot != null && !patches.isEmpty()) {
      throw new IllegalArgumentException("snapshot and patches are mutually exclusive");
    }
  }
}
