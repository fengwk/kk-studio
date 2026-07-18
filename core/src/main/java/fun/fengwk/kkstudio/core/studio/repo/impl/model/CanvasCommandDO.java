package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code canvas_command} 行映射：画布幂等命令日志。 */
@Data
public class CanvasCommandDO {
  /** 业务主键。 */
  private Long id;

  /** 客户端幂等键。 */
  private String commandId;

  /** 所属工作区。 */
  private Long workspaceId;

  /** 所属画布。 */
  private Long canvasId;

  /** 应用前基线 revision。 */
  private Long baseRevision;

  /** 应用后结果 revision。 */
  private Long resultRevision;

  /** 请求内容哈希（冲突/幂等校验）。 */
  private String requestHash;

  /** 命令载荷 JSON。 */
  private String payloadJson;

  /** 结果快照摘要 JSON。 */
  private String resultJson;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
