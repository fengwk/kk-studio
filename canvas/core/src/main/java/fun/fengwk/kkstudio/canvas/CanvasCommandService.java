package fun.fengwk.kkstudio.canvas;

import java.util.List;
import java.util.UUID;

/** Canvas typed commands 的写端口：原子编辑、显式生产资源与画布生命周期。 */
public interface CanvasCommandService {

  /** 创建画布：revision 从 0 开始，标题为去空白后的显示标题。 */
  CanvasDocument createCanvas(String title);

  /**
   * 应用一个 typed command 批。
   *
   * <p>实现必须锁住 document 短事务串行提交同一画布的写入：先按 {@code (canvasId, idempotencyKey)} 判定幂等，再校验每条命令的语义组前置条件，
   * 全部通过才写入并推进 revision；任何冲突都不写入。相同幂等键与相同请求指纹返回当时的接受位置，不同指纹拒绝。
   */
  CanvasCommandResult applyCommands(
      UUID canvasId, UUID idempotencyKey, List<CanvasCommand> commands);

  /** 深删除画布：先确认没有未终结执行，再按 pin、Run、Resource、Node、Group、dedup、Document 顺序清理。 */
  void deleteCanvas(UUID canvasId);
}
