package fun.fengwk.kkstudio.core.agent.runtime.engine;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderConfig;
import lombok.Data;

import java.util.Map;

/**
 * @author fengwk
 */
@Data
public class AgentRequestTask {

    private String taskId;

    private String sessionId;
    private ProviderConfig providerConfig;
    private ModelRequestConfig modelRequestConfig;
    private Map<String, Object> parameters;
    private String systemPrompt;
    private String userMessage;

    private boolean consume; // 是否消费

}
