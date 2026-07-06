package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * 工具调用增量内容。
 *
 * @author fengwk
 */
@Data
public class ToolCallDelta {

  /** 工具调用唯一标识。 */
  private String toolCallId;

  /** 工具名称。 */
  private String toolName;

  /** 工具参数增量。 */
  private String argumentsDelta;
}
