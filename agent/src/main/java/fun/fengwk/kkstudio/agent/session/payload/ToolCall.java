package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * assistant 工具调用的完整内容。
 *
 * @author fengwk
 */
@Data
public class ToolCall {

    /**
     * 工具调用唯一标识。
     */
    private String toolCallId;

    /**
     * 工具名称。
     */
    private String toolName;

    /**
     * 工具调用的完整参数。
     */
    private String arguments;

}
