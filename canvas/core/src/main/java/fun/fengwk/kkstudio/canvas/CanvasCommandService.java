package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.UUID;

/** Canvas typed commands 的写端口。 */
public interface CanvasCommandService {

  CanvasDocument createCanvas(String title);

  /**
   * 应用命令批并返回完整 graph patch。
   *
   * <p>事务内锁定 document 并校验 {@code expectedVersion}（CAS），按 {@code (canvasId, idempotencyKey)} 幂等： 相同
   * idempotencyKey + request hash 精确回放，相同 idempotencyKey 不同 hash 冲突。
   */
  CanvasPatch applyCommands(
      UUID canvasId, long expectedVersion, UUID idempotencyKey, List<CanvasCommand> commands);

  /** 深删除画布：释放全部资源 blob 引用、删除全部 graph 行并深删除绑定 Thread。 */
  void deleteCanvas(UUID canvasId);
}
