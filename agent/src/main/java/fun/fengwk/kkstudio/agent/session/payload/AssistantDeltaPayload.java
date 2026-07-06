package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import java.util.List;

/**
 * assistant_delta 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AssistantDeltaPayload implements Payload {

  /** assistant 正文增量。 */
  private String textDelta;

  /** assistant thinking 增量。 */
  private String thinkingDelta;

  /** assistant 工具调用增量列表。 */
  private List<IndexedToolCallDelta> toolCallsDelta;
}
