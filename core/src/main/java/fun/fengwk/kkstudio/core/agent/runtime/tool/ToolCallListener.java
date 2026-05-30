package fun.fengwk.kkstudio.core.agent.runtime.tool;

/**
 * @author fengwk
 */
public interface ToolCallListener {

    void onPartialResult(String id, String partialResult);

    void onCompleteResult(String id, String result);

    void onCompleteAll();

}
