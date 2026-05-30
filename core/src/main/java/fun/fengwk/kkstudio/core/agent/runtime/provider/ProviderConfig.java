package fun.fengwk.kkstudio.core.agent.runtime.provider;

import lombok.Builder;
import lombok.Data;

/**
 * @author fengwk
 */
@Builder
@Data
public class ProviderConfig {

    private final ProviderType providerType;
    private final String baseUrl;
    private final String apiKey;

}
