package fun.fengwk.kkstudio.canvas;

import java.io.InputStream;
import java.util.UUID;

/**
 * 将 adapters 声明的输出槽位物化为不可变 Canvas Resource。
 *
 * <p>调用方使用 Run 启动时预分配并冻结的 {@code resourceId}；相同 id 的重复调用返回既有
 * Resource，因此崩溃恢复后的重复物化是幂等的。实现必须在同一事务内插入无 owner 的 Resource 行与 {@code OUTPUT} pin：pin 依赖真实 Resource
 * 外键，因此不允许先写占位 pin；写入或返回既有资源前必须在 document → run 行锁下校验 canvas/node/request、RUNNING、 本次 leaseToken
 * 及数据库当前时间下未过期的租约。旧 owner 即便同 request 也不得发布或消费输出，新 owner 可幂等消费既有资源。媒体内容由全局 Storage
 * 持有，文本内容内联在该行；Function success 事务再按冻结顺序把全部输出资源原子挂接到节点。
 */
public interface CanvasResourceMaterializer {

  /** 物化媒体槽位（IMAGE/VIDEO/AUDIO）：内容经全局 Storage 暂存为 Blob。 */
  CanvasResource materializeBlob(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      InputStream content);

  /** 物化 TEXT 槽位：内容内联在 Resource 行，不产生 Blob。 */
  CanvasResource materializeText(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      String text);
}
