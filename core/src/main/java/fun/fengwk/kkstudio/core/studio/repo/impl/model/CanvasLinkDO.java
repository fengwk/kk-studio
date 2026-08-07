package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/** {@code canvas_link} 行映射：同一 canvas 内两个节点间的可见性边。 */
@Data
public class CanvasLinkDO {

  /** 业务主键（bigint，由 kk_studio_id_seq 生成，> 0）。 */
  private Long id;

  /** 所属 canvas 文档 id（> 0，节点删除时经同 canvas 复合外键级联删除链接）。 */
  private Long canvasId;

  /** 源节点 id（与 canvas_id 组成复合外键引用 canvas_node，级联删除，> 0）。 */
  private Long sourceNodeId;

  /** 目标节点 id（与 canvas_id 组成复合外键引用 canvas_node，级联删除，> 0，且必须与源节点不同）。 */
  private Long targetNodeId;
}
