package fun.fengwk.kkstudio.agent.tool.execution;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;

import java.util.List;

/**
 * ToolExecutionListener 接收受控工具执行产生的回流信号。
 *
 * @author fengwk
 */
public interface ToolExecutionListener {

    void onPartial(List<IndexedToolContentDelta> partial);

    void onComplete(List<ToolContent> result);

    void onError(Throwable error);

}
