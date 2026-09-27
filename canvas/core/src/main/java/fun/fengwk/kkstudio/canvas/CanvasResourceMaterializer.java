package fun.fengwk.kkstudio.canvas;

import java.io.InputStream;
import java.util.UUID;

/**
 * 将 adapters 声明的输出槽位物化为不可变 Canvas Resource。
 *
 * <p>调用方使用 Run 启动时预分配并冻结的 {@code resourceId}；相同 id 的重复调用返回既有
 * Resource，因此崩溃恢复后的重复物化是幂等的。实现必须在同一事务内插入无 owner 的 Resource 行与 {@code OUTPUT} pin：pin 依赖真实 Resource
 * 外键，因此不允许先写占位 pin；同时必须校验 {@code (nodeId, requestId)} 仍是当前 Run，旧 Run 或已取消的 Run 不得再物化输出。媒体内容由全局
 * Storage 持有，文本内容内联在该行；Function success 事务再按冻结顺序把全部输出资源原子挂接到节点。
 */
public interface CanvasResourceMaterializer {

  /** 物化媒体槽位（IMAGE/VIDEO/AUDIO）：内容经全局 Storage 暂存为 Blob。 */
  CanvasResource materializeBlob(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      UUID resourceId,
      String name,
      InputStream content);

  /** 物化 TEXT 槽位：内容内联在 Resource 行，不产生 Blob。 */
  CanvasResource materializeText(
      UUID canvasId, UUID nodeId, UUID requestId, UUID resourceId, String name, String text);
}
