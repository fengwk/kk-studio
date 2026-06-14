package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * abort 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class AbortPayload implements Payload {

    /**
     * 若本次中断闭合的是 tool，则记录对应 toolCallId。
     */
    private String toolCallId;

    /**
     * 本次中断的原因说明。
     */
    private String reason;

}
