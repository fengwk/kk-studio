package fun.fengwk.kkstudio.agent.tool.execution;

/**
 * ToolTimeoutException 表示一次工具调用超时。
 *
 * @author fengwk
 */
public class ToolTimeoutException extends RuntimeException {

    private final String toolCallId;
    private final String toolName;
    private final long timeoutSeconds;

    public ToolTimeoutException(String toolCallId, String toolName, long timeoutSeconds) {
        super("tool timeout after " + timeoutSeconds + " seconds: " + toolName);
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

}
