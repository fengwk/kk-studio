package fun.fengwk.kkstudio.core.agent.runtime.recovery;

/**
 * stale busy session 的恢复回调。
 *
 * @author fengwk
 */
public interface AgentBusyRecoveryHandler {

    /**
     * 显式闭合 stale busy turn，并尝试启动已排队的用户提交。
     *
     * @return true 表示恢复成功并已尝试接力，false 表示 CAS 冲突或 session 状态已变化。
     */
    boolean recoverAndContinue(String sessionId, String reason);

}
