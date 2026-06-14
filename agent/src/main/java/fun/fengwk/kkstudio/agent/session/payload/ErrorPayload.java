package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * error 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class ErrorPayload implements Payload {

    /**
     * 若本次错误闭合的是 tool，则记录对应 toolCallId。
     */
    private String toolCallId;

    /**
     * 本次错误的描述文本。
     */
    private String message;

}
