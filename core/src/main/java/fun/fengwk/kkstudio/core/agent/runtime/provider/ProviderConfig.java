package fun.fengwk.kkstudio.core.agent.runtime.provider;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

/**
 * @author fengwk
 */
@Builder
@Data
public class ProviderConfig {

    private final ProviderType providerType;
    private final String baseUrl;
    /**
     * Provider 凭据。
     * <p>
     * 当前骨架直接携带 apiKey 以便快速验证 provider 调用；正式持久化实现必须加密存储或替换为凭据引用。
     */
    @ToString.Exclude
    private final String apiKey;

}
