package fun.fengwk.kkstudio.agent.tool;

/**
 * Tool 表示一类可流式执行的工具。
 *
 * 语义说明：
 * - 工具启动后立即返回句柄。
 * - 工具通过 handler 推送 partial / end / error 三类回调。
 * - timeoutSeconds 为工具默认超时秒数，0 表示无限等待。
 *
 * @author fengwk
 */
public interface Tool {

    ToolExecutionHandle asyncExecute(ToolCallRequest request, ToolExecutionHandler handler);

    default long timeoutSeconds() {
        return 0L;
    }

}
