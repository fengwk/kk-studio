package fun.fengwk.kkstudio.share.studio;

import lombok.Data;

@Data
public class CanvasNodeDTO {
  /** 节点主键：正十进制字符串（底层 bigint，由数据库序列分配）。 */
  private String id;

  /** 节点种类，取 {@code CanvasNodeKind} 枚举名：RESOURCE（资源节点）或 FUNCTION（函数节点）。 */
  private String kind;

  /** 节点类型标识：如 {@code text}、{@code system.generate-text}（形状随 kind 变化）。 */
  private String nodeType;

  /** 节点显示名。 */
  private String name;

  /** 世界空间 X 坐标（有限数，逻辑像素）。 */
  private double x;

  /** 世界空间 Y 坐标（有限数，逻辑像素）。 */
  private double y;

  /** 节点宽度（有限正数，逻辑像素）。 */
  private double width;

  /** 节点高度（有限正数，逻辑像素）。 */
  private double height;

  /**
   * 节点数据 JSON：形状随 nodeType（如 text 节点 {@code {"text":...}}；generate-text 节点含
   * functionId/version/prompt/configRevision）。
   */
  private String dataJson;
}
