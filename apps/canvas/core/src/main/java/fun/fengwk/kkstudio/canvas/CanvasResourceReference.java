package fun.fengwk.kkstudio.canvas;

import java.util.Objects;
import java.util.UUID;

/**
 * Function args 中的核心保留资源引用值：{@code {"type":"resource","nodeId":"...","index":0}}。
 *
 * <p>引用只存一份（在消费节点的 args 里），连线是读取投影。身份始终是同画布内源节点的 UUID 与从零开始的输出位置；名称不参与绑定。
 */
public record CanvasResourceReference(UUID nodeId, int index) {

  public CanvasResourceReference {
    Objects.requireNonNull(nodeId, "nodeId");
    if (index < 0) {
      throw new IllegalArgumentException("index must be >= 0");
    }
  }
}
