package fun.fengwk.kkstudio.core.agent.runtime.recovery;

/**
 * submit 遇到 busy session 后的轻量 stale 检查器。
 * <p>
 * 该组件只负责本节点内存中的延迟检查点，不承担分布式一致性；真正恢复仍由数据库 CAS 保证唯一。
 *
 * @author fengwk
 */
public interface AgentBusyRecoveryChecker {

    /** submit 已入队但 session 仍 busy 时调用，注册或刷新本节点检查点。 */
    void onSubmitQueued(String sessionId);

    /** 使用最新配置立即检查指定 session。 */
    boolean checkNow(String sessionId, String reason);

    /** 配置刷新后重排本节点已跟踪的 session。 */
    void onPropertiesRefreshed();

}
