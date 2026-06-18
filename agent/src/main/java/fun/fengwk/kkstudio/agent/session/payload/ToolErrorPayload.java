package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * tool_error 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class ToolErrorPayload implements Payload {

    /**
     * 需要异常闭合的工具调用唯一标识。
     */
    private String toolCallId;

    /**
     * 本次工具错误的描述文本。
     */
    private String message;

}
