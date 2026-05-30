package fun.fengwk.kkstudio.core.agent.runtime.repo;

import fun.fengwk.kkstudio.core.agent.runtime.event.EventSession;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;

import java.util.Map;

/**
 * @author fengwk
 */
public interface EventSessionRepository {

    EventSession newSession(String headEventId);

    EventSession get(String sessionId);

    // cas + busy 标志
    boolean casHeadEventIdWithBusy(String sessionId, String oldHeadEventId, String newHeadEventId);

    void updateSessionConfig(String sessionId, ProviderConfig providerConfig, ModelRequestConfig modelRequestConfig,
                             Map<String, Object> parameters, String systemPrompt);

}