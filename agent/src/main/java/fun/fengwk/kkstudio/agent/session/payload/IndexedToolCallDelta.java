package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * 带槽位索引的工具调用增量。
 *
 * @author fengwk
 */
@Data
public class IndexedToolCallDelta {

  /** 工具调用在 assistant 工具调用列表中的槽位。 */
  private Integer index;

  /** 该槽位上的工具调用增量。 */
  private ToolCallDelta toolCallDelta;
}
