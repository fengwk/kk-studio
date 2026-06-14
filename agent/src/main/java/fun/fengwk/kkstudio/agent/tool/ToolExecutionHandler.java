package fun.fengwk.kkstudio.agent.tool;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;

import java.util.List;

/**
 * ToolExecutionHandler 表示工具执行过程中的流式回调。
 *
 * 语义说明：
 * - onPartial 推送结果增量。
 * - onComplete 推送最终完整结果。
 * - onError 推送异常结束。
 *
 * @author fengwk
 */
public interface ToolExecutionHandler {

    void onPartial(List<IndexedToolContentDelta> partial, ToolExecutionContext context);

    void onComplete(List<ToolContent> result, ToolExecutionContext context);

    void onError(Throwable error, ToolExecutionContext context);

}
