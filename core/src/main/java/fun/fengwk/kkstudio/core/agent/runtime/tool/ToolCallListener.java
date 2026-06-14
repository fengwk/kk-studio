package fun.fengwk.kkstudio.core.agent.runtime.tool;

/**
 * @author fengwk
 */
public interface ToolCallListener {

    /**
     * 工具执行即将开始。实现必须在任何外部副作用发生前回调该方法。
     */
    void onStart(ToolCallHandle toolCallHandle);

    /** 工具执行增量结果。 */
    void onPartialResult(String id, String partialResult, ToolCallHandle toolCallHandle);

    /** 单个工具执行完成。 */
    void onCompleteResult(String id, String result, ToolCallHandle toolCallHandle);

    /** 本批工具全部成功完成。 */
    void onCompleteAll(ToolCallHandle toolCallHandle);

    /**
     * 工具执行失败。默认抛出异常以暴露未处理错误；runtime 实现会覆盖该方法并转成 ErrorEvent。
     */
    default void onError(Throwable error, ToolCallHandle toolCallHandle) {
        throw new RuntimeException(error);
    }

}
