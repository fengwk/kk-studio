package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * abort 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AbortPayload implements Payload {

  /** 本次中断的原因说明。 */
  private String reason;
}
