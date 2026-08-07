package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasLinkDTO {
  /** 链接主键：正十进制字符串（底层 bigint，由数据库序列分配）。 */
  private String id;

  /** 源节点主键：正十进制字符串；节点必须存在于同一画布。 */
  private String sourceNodeId;

  /** 目标节点主键：正十进制字符串；与源节点不同且必须存在于同一画布。 */
  private String targetNodeId;
}
