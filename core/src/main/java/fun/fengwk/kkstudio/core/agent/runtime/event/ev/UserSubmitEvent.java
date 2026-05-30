package fun.fengwk.kkstudio.core.agent.runtime.event.ev;

import fun.fengwk.kkstudio.core.agent.runtime.provider.ModelRequestConfig;
import fun.fengwk.kkstudio.core.agent.runtime.provider.ProviderType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author fengwk
 */
@Builder
@Data
public class UserSubmitEvent extends Event {

    private final ProviderType providerType;
    private final ModelRequestConfig modelRequestConfig;
    private final Map<String, Object> parameters;
    private final String systemPrompt;
    private final List<String> userMessageList;

    @Override
    public EventType getEventType() {
        return EventType.user_submit;
    }

}
