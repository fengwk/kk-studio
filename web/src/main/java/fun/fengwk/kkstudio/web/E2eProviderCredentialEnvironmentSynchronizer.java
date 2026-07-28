package fun.fengwk.kkstudio.web;

import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies complete externally supplied E2E provider configurations without logging secrets. */
@Slf4j
final class E2eProviderCredentialEnvironmentSynchronizer {

  private static final List<ProviderEnvironment> PROVIDERS =
      List.of(
          new ProviderEnvironment("minimax", "TEST_MINIMAX_BASE_URL", "TEST_MINIMAX_API_KEY"),
          new ProviderEnvironment("openai", "TEST_OPENAI_BASE_URL", "TEST_OPENAI_API_KEY"),
          new ProviderEnvironment("xai", "TEST_XAI_BASE_URL", "TEST_XAI_API_KEY"),
          new ProviderEnvironment("deepseek", "TEST_DEEPSEEK_BASE_URL", "TEST_DEEPSEEK_API_KEY"),
          new ProviderEnvironment("google", "TEST_GOOGLE_BASE_URL", "TEST_GOOGLE_API_KEY"),
          new ProviderEnvironment("anthropic", "TEST_ANTHROPIC_BASE_URL", "TEST_ANTHROPIC_API_KEY"),
          new ProviderEnvironment("zai", "TEST_ZAI_BASE_URL", "TEST_ZAI_API_KEY"));

  private final AgentProviderService agentProviderService;
  private final Function<String, String> environmentValue;

  E2eProviderCredentialEnvironmentSynchronizer(
      AgentProviderService agentProviderService, Function<String, String> environmentValue) {
    this.agentProviderService = agentProviderService;
    this.environmentValue = environmentValue;
  }

  void synchronize() {
    List<ExternalProviderConfiguration> configurations = completeConfigurations();
    if (configurations.isEmpty()) {
      return;
    }
    Map<String, AgentProviderDTO> providersByName =
        agentProviderService.pageProviders(new PageQuery(1, 100)).getResults().stream()
            .collect(Collectors.toMap(AgentProviderDTO::getName, Function.identity()));
    for (ExternalProviderConfiguration configuration : configurations) {
      AgentProviderDTO provider = providersByName.get(configuration.name());
      if (provider == null) {
        throw new IllegalStateException(
            "E2E provider seed is missing configured provider: " + configuration.name());
      }
      AgentProviderUpdateDTO update = update(provider, configuration);
      agentProviderService.updateProvider(providerId(provider), update);
      log.info(
          "Synchronized externally supplied E2E configuration for provider {}", provider.getName());
    }
  }

  private List<ExternalProviderConfiguration> completeConfigurations() {
    List<ExternalProviderConfiguration> configurations = new ArrayList<>();
    for (ProviderEnvironment provider : PROVIDERS) {
      String baseUrl = trimToNull(environmentValue.apply(provider.baseUrlVariable()));
      String credential = trimToNull(environmentValue.apply(provider.credentialVariable()));
      if (baseUrl == null && credential == null) {
        continue;
      }
      if (baseUrl == null || credential == null) {
        log.warn(
            "Ignoring incomplete external E2E configuration for provider {}; both {} and {} are required",
            provider.name(),
            provider.baseUrlVariable(),
            provider.credentialVariable());
        continue;
      }
      configurations.add(new ExternalProviderConfiguration(provider.name(), baseUrl, credential));
    }
    return configurations;
  }

  private static AgentProviderUpdateDTO update(
      AgentProviderDTO provider, ExternalProviderConfiguration configuration) {
    AgentProviderUpdateDTO update = new AgentProviderUpdateDTO();
    update.setName(provider.getName());
    update.setDescription(provider.getDescription());
    update.setProviderType(provider.getProviderType());
    update.setBaseUrl(normalizeBaseUrl(configuration.baseUrl(), provider.getProviderType()));
    update.setCredential(configuration.credential());
    update.setModelCallTimeoutMillis(provider.getModelCallTimeoutMillis());
    update.setModelCallIdleTimeoutMillis(provider.getModelCallIdleTimeoutMillis());
    return update;
  }

  private static long providerId(AgentProviderDTO provider) {
    try {
      return Long.parseLong(provider.getId());
    } catch (NumberFormatException error) {
      throw new IllegalStateException(
          "E2E provider has an invalid id: " + provider.getName(), error);
    }
  }

  private static String normalizeBaseUrl(String baseUrl, String providerType) {
    if (!"openai".equals(providerType) && !"openai_response".equals(providerType)) {
      return baseUrl;
    }
    String cleaned = baseUrl.replaceFirst("/+$", "");
    return cleaned.endsWith("/v1") ? cleaned : cleaned + "/v1";
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record ProviderEnvironment(
      String name, String baseUrlVariable, String credentialVariable) {}

  private record ExternalProviderConfiguration(String name, String baseUrl, String credential) {}
}
