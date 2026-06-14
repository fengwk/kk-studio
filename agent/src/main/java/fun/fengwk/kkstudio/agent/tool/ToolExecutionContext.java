package fun.fengwk.kkstudio.agent.tool;

import lombok.Data;

/**
 * ToolExecutionContext 表示一次工具执行的上下文。
 *
 * @author fengwk
 */
@Data
public class ToolExecutionContext {

    /**
     * 工具调用唯一标识。
     */
    private final String toolCallId;

    /**
     * 当前工具名称。
     */
    private final String toolName;

    /**
     * 当前工具调用参数。
     */
    private final String arguments;

    /**
     * 当前工具执行句柄。
     */
    private final ToolExecutionHandle handle;

}
