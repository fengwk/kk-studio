package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.provider.ProviderInfo;
import fun.fengwk.kkstudio.agent.provider.ProviderManager;
import fun.fengwk.kkstudio.agent.provider.ProviderType;
import fun.fengwk.kkstudio.core.agent.runtime.provider.AgentProviderInfoResolver;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * @author fengwk
 */
public class LocalH2AcceptanceConfigurationTest {

  @Test
  public void shouldCreateLocalH2AcceptanceBeans() {
    LocalH2AcceptanceConfiguration configuration = new LocalH2AcceptanceConfiguration();
    AcceptanceStubProviderManager stubProviderManager =
        configuration.acceptanceStubProviderManager();
    ProviderManager providerManager = configuration.providerManager(stubProviderManager);
    AgentProviderInfoResolver resolver = configuration.agentProviderInfoResolver();
    Executor executor = configuration.agentRunTaskExecutor();
    AtomicBoolean executed = new AtomicBoolean();

    ProviderInfo providerInfo = resolver.resolve("stub");
    executor.execute(() -> executed.set(true));

    assertSame(stubProviderManager, providerManager);
    assertEquals(ProviderType.openai, providerInfo.getProviderType());
    assertEquals("http://stub-provider", providerInfo.getBaseUrl());
    assertEquals("stub-api-key", providerInfo.getApiKey());
    assertEquals(Duration.ofSeconds(30), providerInfo.getTimeout());
    assertEquals(Duration.ofSeconds(30), providerInfo.getStreamIdleTimeout());
    assertTrue(executed.get());
  }
}
