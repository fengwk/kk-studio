package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * assistant_end 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AssistantEndPayload implements Payload {

  /** assistant 结束元信息。 */
  private AssistantMetadata metadata;
}
