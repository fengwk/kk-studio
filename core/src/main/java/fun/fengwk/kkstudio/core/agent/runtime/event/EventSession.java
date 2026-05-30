package fun.fengwk.kkstudio.core.agent.runtime.event;

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
public class EventSession {

    private String sessionId;
    private String headEventId;
//    private String eventTreeId;
    private EventSessionStatus status;

    private ProviderConfig providerConfig;
    private ModelRequestConfig modelRequestConfig;
    private Map<String, Object> parameters;
    private String systemPrompt;

}
