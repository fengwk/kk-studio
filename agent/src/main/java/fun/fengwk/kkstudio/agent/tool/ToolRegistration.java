package fun.fengwk.kkstudio.agent.tool;

import lombok.Data;

import lombok.Data;

/**
 * ToolRegistration 聚合工具描述与工具实现。
 *
 * @author fengwk
 */
@Data
public class ToolRegistration {

  /** 注册名称。 */
  private final String name;

  /** 工具描述。 */
  private final ToolInfo toolInfo;

  /** 工具实现。 */
  private final Tool tool;
}
