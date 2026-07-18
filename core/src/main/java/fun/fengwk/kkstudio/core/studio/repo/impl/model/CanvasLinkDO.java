package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.LocalDateTime;

/** {@code canvas_link} 行映射：画布可见性连线（非 ResourceReference）。 */
@Data
public class CanvasLinkDO {
  /** 业务主键。 */
  private Long id;

  /** 所属画布。 */
  private Long canvasId;

  /** 源节点 id。 */
  private Long sourceNodeId;

  /** 目标节点 id。 */
  private Long targetNodeId;

  /** 连线业务 revision。 */
  private Long revision;

  /** 创建时间（映射 {@code gmt_create}）。 */
  private LocalDateTime createTime;
}
