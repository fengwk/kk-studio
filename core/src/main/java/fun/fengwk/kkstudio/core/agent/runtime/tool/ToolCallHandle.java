package fun.fengwk.kkstudio.core.agent.runtime.tool;

/**
 * 工具调用执行句柄，用于在 abort 或 CAS 抢占失败时尽快中断正在执行的工具。
 *
 * @author fengwk
 */
public interface ToolCallHandle {

    ToolCallHandle NOOP = new ToolCallHandle() {

        @Override
        public void cancel() {
            // noop
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /** 尽力取消工具执行。实现需要保证幂等。 */
    void cancel();

    /** 是否已请求取消。 */
    boolean isCancelled();

}
