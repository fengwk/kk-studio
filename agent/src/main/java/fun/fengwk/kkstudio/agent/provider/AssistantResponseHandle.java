package fun.fengwk.kkstudio.agent.provider;

/**
 * AssistantResponseHandle 表示一次 assistant 流的取消句柄。
 *
 * @author fengwk
 */
public interface AssistantResponseHandle {

    void cancel();

    boolean isCancelled();

}
