package fun.fengwk.kkstudio.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NoopToolExecutionHandle 表示没有下游资源需要取消的工具执行句柄。
 *
 * @author fengwk
 */
public class NoopToolExecutionHandle implements ToolExecutionHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

}
