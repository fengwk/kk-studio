package fun.fengwk.kkstudio.share.canvas;

import lombok.Data;

/** 函数输出计划中的一个槽位：资源类型与可选显式名称。 */
@Data
public class CanvasFunctionOutputDTO {
  private String kind;

  /** 显式资源名；为 null 表示由节点名与类型推导。 */
  private String name;
}
