package fun.fengwk.kkstudio.core.agent.runtime.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.configuration.AgentRuntimeProperties;

import java.time.Duration;

/**
 * @author fengwk
 */
public class ConfiguredAgentProviderInfoResolverTest {

  @Test
  public void shouldResolveProviderInfoFromRuntimeProperties() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    AgentRuntimeProperties.ProviderProperties providerProperties =
        new AgentRuntimeProperties.ProviderProperties();
    providerProperties.setProviderType(ProviderType.openai);
    providerProperties.setBaseUrl("https://api.example.test");
    providerProperties.setApiKey("test-api-key");
    providerProperties.setTimeout(Duration.ofSeconds(10));
    providerProperties.setStreamIdleTimeout(Duration.ofSeconds(20));
    properties.getProviders().put("openai-main", providerProperties);

    ConfiguredAgentProviderInfoResolver resolver =
        new ConfiguredAgentProviderInfoResolver(properties);
    ProviderInfo providerInfo = resolver.resolve("openai-main");

    assertEquals(ProviderType.openai, providerInfo.getProviderType());
    assertEquals("https://api.example.test", providerInfo.getBaseUrl());
    assertEquals("test-api-key", providerInfo.getApiKey());
    assertEquals(Duration.ofSeconds(10), providerInfo.getTimeout());
    assertEquals(Duration.ofSeconds(20), providerInfo.getStreamIdleTimeout());
  }

  @Test
  public void shouldReturnNullForBlankMissingOrIncompleteProvider() {
    AgentRuntimeProperties properties = new AgentRuntimeProperties();
    properties.getProviders().put("incomplete", new AgentRuntimeProperties.ProviderProperties());

    ConfiguredAgentProviderInfoResolver resolver =
        new ConfiguredAgentProviderInfoResolver(properties);

    assertNull(resolver.resolve(null));
    assertNull(resolver.resolve(" "));
    assertNull(resolver.resolve("missing"));
    assertNull(resolver.resolve("incomplete"));
  }
}
