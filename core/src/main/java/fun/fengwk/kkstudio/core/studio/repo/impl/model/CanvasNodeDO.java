package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

/** {@code canvas_node} 行映射：canvas 节点。 */
@Data
public class CanvasNodeDO {

  /** 业务主键（bigint，由 kk_studio_id_seq 生成，> 0）。 */
  private Long id;

  /** 所属 canvas 文档 id（外键引用 canvas_document.id，级联删除，> 0）。 */
  private Long canvasId;

  /** 节点领域类型（varchar(32)，必填）：RESOURCE / FUNCTION。 */
  private String kind;

  /** 节点类型名（varchar(128)，必填：非空白）。 */
  private String nodeType;

  /** 显示名（varchar(256)，必填：非空白）。 */
  private String name;

  /** 画布 x 坐标（double precision，必填）。 */
  private Double x;

  /** 画布 y 坐标（double precision，必填）。 */
  private Double y;

  /** 节点宽度（double precision，必填：> 0）。 */
  private Double width;

  /** 节点高度（double precision，必填：> 0）。 */
  private Double height;

  /** 子类型负载 JSON（jsonb，必填）。 */
  private String dataJson;
}
