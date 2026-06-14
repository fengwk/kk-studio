package fun.fengwk.kkstudio.core.agent.runtime.tool;

import java.util.List;

/**
 * @author fengwk
 */
public interface ToolCallEngine {

    /**
     * 异步执行一组工具调用。
     * <p>
     * 实现约定：
     * <ul>
     *   <li>方法应尽快返回；耗时工具执行必须发生在异步线程中。</li>
     *   <li>任何外部副作用发生前必须先调用 {@link ToolCallListener#onStart(ToolCallHandle)} 传入取消句柄。</li>
     *   <li>同一个 tool call 的 partial/result 必须按产生顺序回调。</li>
     *   <li>多个 tool call 的 {@link ToolCallListener#onCompleteResult(String, String, ToolCallHandle)} 必须按入参列表顺序回调，
     *   runtime 会按事件顺序重建 ToolExecutionResultMessage。</li>
     *   <li>{@link ToolCallListener#onCompleteAll(ToolCallHandle)} 必须在所有成功 tool call 的
     *   {@link ToolCallListener#onCompleteResult(String, String, ToolCallHandle)} 之后回调。</li>
     *   <li>任一工具执行失败时应调用 {@link ToolCallListener#onError(Throwable, ToolCallHandle)}，不要再调用 onCompleteAll。</li>
     * </ul>
     */
    void asyncExecute(List<ToolCallRequest> toolCallRequests, ToolCallListener toolCallListener);

}
