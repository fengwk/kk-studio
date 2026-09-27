package fun.fengwk.kkstudio.canvas;

import java.io.InputStream;
import java.util.UUID;

/**
 * 将 provider/adapters 产出的媒体流物化为不可变 Canvas Resource。
 *
 * <p>调用方预先分配 {@code resourceId}；相同 id 的重复调用返回既有 Resource。实现必须在同一事务内插入无 owner 的 Resource 行与 {@code
 * OUTPUT} pin：pin 依赖真实 Resource 外键，因此不允许先写占位 pin；同时必须校验 {@code (nodeId, requestId)} 仍是当前 Run，旧 Run
 * 或已取消的 Run 不得再物化输出。内容由全局 Storage 持有，Function success 事务再把目标资源原子挂接到节点。
 */
public interface CanvasResourceMaterializer {

  CanvasResource materialize(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      UUID resourceId,
      String name,
      InputStream content);
}
