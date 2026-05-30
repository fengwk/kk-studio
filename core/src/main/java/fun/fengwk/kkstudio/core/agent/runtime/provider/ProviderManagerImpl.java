package fun.fengwk.kkstudio.core.agent.runtime.provider;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * @author fengwk
 */
public class ProviderManagerImpl implements ProviderManager {

    private final Map<ProviderType, Function<ProviderConfig, Provider>> providerFactoryMap;

    public ProviderManagerImpl() {
        Map<ProviderType, Function<ProviderConfig, Provider>> providerFactoryMap = new HashMap<>();
        providerFactoryMap.put(ProviderType.openai, OpenAiModelProvider::new);
        providerFactoryMap.put(ProviderType.openai_response, OpenAiResponseModelProvider::new);
        providerFactoryMap.put(ProviderType.anthropic, AnthropicModelProvider::new);
        providerFactoryMap.put(ProviderType.google, GoogleModelProvider::new);
        this.providerFactoryMap = providerFactoryMap;
    }

    @Override
    public Provider getProvider(ProviderConfig providerConfig) {
        Function<ProviderConfig, Provider> factory = providerFactoryMap.get(providerConfig.getProviderType());
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported model provider type: " + providerConfig.getProviderType());
        }

        return factory.apply(providerConfig);
    }

}
