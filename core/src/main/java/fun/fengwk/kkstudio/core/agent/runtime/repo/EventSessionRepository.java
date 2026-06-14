package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.event.EventSessionStatus;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;

import java.util.Map;

/**
 * @author fengwk
 */
public interface EventSessionRepository {

    EventSession newSession(String headEventId);

    EventSession get(String sessionId);

    /**
     * CAS 推进 session head，并同时更新最小运行状态。
     * <p>
     * 多节点无状态部署依赖该方法保证同一 session 同一时间只有一条执行链能推进 head。
     * 失败/中止等业务结果由事件类型表达，不扩展 session status。
     * oldHeadEventId 允许为 null，用于新 session 首次启动。
     * 成功时必须同步维护 status、runningTurnId、lastEventAt；从 idle 进入 busy 时还应维护 runningSince。
     * 调用方会把该方法与 event append/task consume 放在同一事务内执行，实现必须参与当前事务。
     */
    boolean casHeadEventId(String sessionId, String oldHeadEventId, String newHeadEventId,
                           EventSessionStatus newStatus, String runningTurnId);

    /**
     * 更新 session 当前默认配置。
     * <p>
     * 如果 providerConfig 包含 apiKey 等敏感信息，实现必须加密存储或转换为凭据引用，禁止明文落库和日志外泄。
     */
    void updateSessionConfig(String sessionId, ProviderConfig providerConfig, ModelRequestConfig modelRequestConfig,
                             Map<String, Object> parameters, String systemPrompt);

}
