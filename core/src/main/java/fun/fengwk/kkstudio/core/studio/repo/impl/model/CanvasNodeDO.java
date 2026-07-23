package fun.fengwk.kkstudio.core.studio.repo.impl.model;

import lombok.Data;

import java.time.OffsetDateTime;

/** {@code canvas_node} 行映射：画布节点。 */
@Data
public class CanvasNodeDO {
  /** 业务主键。 */
  private Long id;

  /** 所属画布。 */
  private Long canvasId;

  /** 领域种类：RESOURCE / FUNCTION / GROUP。 */
  private String kind;

  /** 节点类型名。 */
  private String nodeType;

  /** 节点类型版本。 */
  private Integer nodeTypeVersion;

  /** 显示名称。 */
  private String name;

  /** 父 Group 节点 id；顶层为空。 */
  private Long parentGroupId;

  /** 画布坐标 x。 */
  private Double x;

  /** 画布坐标 y。 */
  private Double y;

  /** 宽度。 */
  private Double width;

  /** 高度。 */
  private Double height;

  /** 旋转角。 */
  private Double rotation;

  /** 层叠顺序。 */
  private Long zIndex;

  /** 是否锁定编辑。 */
  private Boolean locked;

  /** 是否隐藏。 */
  private Boolean hidden;

  /** 有效性状态。 */
  private String validity;

  /** 子类型数据 JSON。 */
  private String dataJson;

  /** 节点业务 revision。 */
  private Long revision;

  /** 软删除时间（映射 {@code deleted_at} timestamptz）；空表示未删除。 */
  private OffsetDateTime deletedTime;

  /** 乐观锁行版本。 */
  private Long version;

  /** 创建时间（映射 {@code created_at} timestamptz）。 */
  private OffsetDateTime createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz）。 */
  private OffsetDateTime updateTime;
}
