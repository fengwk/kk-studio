package fun.fengwk.kkstudio.core.agent.runtime.tool;

import java.util.List;

/**
 * @author fengwk
 */
public interface ToolCallEngine {

    // 按照传入顺序返回
    void asyncExecute(List<ToolCallRequest> toolCallRequests, ToolCallListener toolCallListener);

}
