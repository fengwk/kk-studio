package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * assistant_error 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AssistantErrorPayload implements Payload {

  /** 本次 assistant 错误的描述文本。 */
  private String message;
}
