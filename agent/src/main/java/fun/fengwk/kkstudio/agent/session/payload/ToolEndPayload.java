package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * tool_end 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class ToolEndPayload implements Payload {

  /** 正常结束的工具调用唯一标识。 */
  private String toolCallId;
}
