package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fun.fengwk.convention4j.api.page.Page;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class E2eProviderCredentialEnvironmentSynchronizerTest {

  @Test
  void synchronizesEveryCompleteConfiguredProvider() {
    AgentProviderService service = service(providers());
    Map<String, String> environment = completeEnvironment();

    new E2eProviderCredentialEnvironmentSynchronizer(service, environment::get).synchronize();

    ArgumentCaptor<AgentProviderUpdateDTO> updates =
        ArgumentCaptor.forClass(AgentProviderUpdateDTO.class);
    verify(service, times(7)).updateProvider(anyLong(), updates.capture());
    Map<String, AgentProviderUpdateDTO> byName =
        updates.getAllValues().stream()
            .collect(Collectors.toMap(AgentProviderUpdateDTO::getName, update -> update));
    assertEquals("https://minimax.example/v1", byName.get("minimax").getBaseUrl());
    assertEquals("minimax-secret", byName.get("minimax").getCredential());
    assertEquals("https://google.example/", byName.get("google").getBaseUrl());
    assertEquals("google-secret", byName.get("google").getCredential());
    assertEquals("https://zai.example/v1", byName.get("zai").getBaseUrl());
    assertEquals("zai-secret", byName.get("zai").getCredential());
    assertEquals("0", byName.get("minimax").getExpectedVersion());
  }

  @Test
  void skipsAbsentAndIncompleteEnvironmentConfigurations() {
    AgentProviderService service = mock(AgentProviderService.class);
    Map<String, String> environment = new HashMap<>();
    environment.put("TEST_MINIMAX_BASE_URL", "https://minimax.example");

    new E2eProviderCredentialEnvironmentSynchronizer(service, environment::get).synchronize();

    verifyNoInteractions(service);
  }

  @Test
  void rejectsACompleteExternalConfigurationWhenItsE2eProviderIsMissing() {
    AgentProviderService service = service(List.of());
    Map<String, String> environment =
        Map.of(
            "TEST_MINIMAX_BASE_URL", "https://minimax.example",
            "TEST_MINIMAX_API_KEY", "minimax-secret");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new E2eProviderCredentialEnvironmentSynchronizer(service, environment::get)
                    .synchronize());

    assertEquals("E2E provider seed is missing configured provider: minimax", error.getMessage());
    verify(service, never()).updateProvider(anyLong(), any(AgentProviderUpdateDTO.class));
  }

  @Test
  void rejectsAnInvalidSeedProviderIdBeforeUpdatingIt() {
    AgentProviderService service =
        service(List.of(provider("invalid", "minimax", "openai_response")));
    Map<String, String> environment =
        Map.of(
            "TEST_MINIMAX_BASE_URL", "https://minimax.example",
            "TEST_MINIMAX_API_KEY", "minimax-secret");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                new E2eProviderCredentialEnvironmentSynchronizer(service, environment::get)
                    .synchronize());

    assertEquals("E2E provider has an invalid id: minimax", error.getMessage());
    verify(service, never()).updateProvider(anyLong(), any(AgentProviderUpdateDTO.class));
  }

  private static AgentProviderService service(List<AgentProviderDTO> providers) {
    AgentProviderService service = mock(AgentProviderService.class);
    @SuppressWarnings("unchecked")
    Page<AgentProviderDTO> page = mock(Page.class);
    when(page.getResults()).thenReturn(providers);
    when(service.pageProviders(any())).thenReturn(page);
    return service;
  }

  private static List<AgentProviderDTO> providers() {
    return List.of(
        provider("1", "minimax", "openai_response"),
        provider("2", "openai", "openai_response"),
        provider("3", "xai", "openai_response"),
        provider("4", "deepseek", "openai"),
        provider("5", "google", "google"),
        provider("6", "anthropic", "anthropic"),
        provider("7", "zai", "openai"));
  }

  private static AgentProviderDTO provider(String id, String name, String providerType) {
    AgentProviderDTO provider = new AgentProviderDTO();
    provider.setId(id);
    provider.setName(name);
    provider.setDescription(name + " provider");
    provider.setProviderType(providerType);
    provider.setModelCallTimeoutMillis(1_800_000L);
    provider.setModelCallIdleTimeoutMillis(120_000L);
    provider.setVersion("0");
    return provider;
  }

  private static Map<String, String> completeEnvironment() {
    Map<String, String> environment = new HashMap<>();
    environment.put("TEST_MINIMAX_BASE_URL", "https://minimax.example/");
    environment.put("TEST_MINIMAX_API_KEY", "minimax-secret");
    environment.put("TEST_OPENAI_BASE_URL", "https://openai.example");
    environment.put("TEST_OPENAI_API_KEY", "openai-secret");
    environment.put("TEST_XAI_BASE_URL", "https://xai.example/v1");
    environment.put("TEST_XAI_API_KEY", "xai-secret");
    environment.put("TEST_DEEPSEEK_BASE_URL", "https://deepseek.example/");
    environment.put("TEST_DEEPSEEK_API_KEY", "deepseek-secret");
    environment.put("TEST_GOOGLE_BASE_URL", "https://google.example/");
    environment.put("TEST_GOOGLE_API_KEY", "google-secret");
    environment.put("TEST_ANTHROPIC_BASE_URL", "https://anthropic.example/");
    environment.put("TEST_ANTHROPIC_API_KEY", "anthropic-secret");
    environment.put("TEST_ZAI_BASE_URL", "https://zai.example");
    environment.put("TEST_ZAI_API_KEY", "zai-secret");
    return environment;
  }
}
