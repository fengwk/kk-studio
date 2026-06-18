package fun.fengwk.kkstudio.agent.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author fengwk
 */
public class ProviderManagerImplTest {

    private final ProviderManagerImpl providerManager = new ProviderManagerImpl();

    /**
     * 校验所有已注册 ProviderType 都能解析到正确实现。
     */
    @Test
    public void testReturnsProviderForAllRegisteredTypes() {
        assertInstanceOf(OpenAiModelProvider.class, providerManager.getProvider(providerInfo(ProviderType.openai)));
        assertInstanceOf(OpenAiResponseModelProvider.class, providerManager.getProvider(providerInfo(ProviderType.openai_response)));
        assertInstanceOf(AnthropicModelProvider.class, providerManager.getProvider(providerInfo(ProviderType.anthropic)));
        assertInstanceOf(GoogleModelProvider.class, providerManager.getProvider(providerInfo(ProviderType.google)));
    }

    /**
     * 校验缺失 ProviderInfo 时不会退化为 NPE。
     */
    @Test
    public void testRejectsNullProviderInfo() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> providerManager.getProvider(null));
        assertEquals("providerInfo must not be null", exception.getMessage());
    }

    /**
     * 校验未知 ProviderType 会抛出明确异常。
     */
    @Test
    public void testRejectsUnknownProviderType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> providerManager.getProvider(providerInfo(null)));
        assertEquals("Unsupported model provider type: null", exception.getMessage());
    }

    /**
     * 构造最小 ProviderInfo 测试输入。
     */
    private ProviderInfo providerInfo(ProviderType providerType) {
        return ProviderInfo.builder()
            .providerType(providerType)
            .build();
    }

}
