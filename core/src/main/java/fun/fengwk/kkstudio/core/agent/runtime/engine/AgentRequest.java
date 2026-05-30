package fun.fengwk.kkstudio.core.agent.runtime.engine;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @author fengwk
 */
@Builder
@Data
public class AgentRequest {

    private final String sessionId;
    private final ProviderConfig providerConfig;
    private final ModelRequestConfig modelRequestConfig;
    private final Map<String, Object> parameters;
    private final String systemPrompt; // 系统提示词
    private final String userMessage; // 用户提示词

}
