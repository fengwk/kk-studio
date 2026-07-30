package fun.fengwk.kkstudio.web;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;

/** Synchronizes externally supplied E2E provider credentials after the E2E seed is available. */
@Configuration(proxyBeanMethods = false)
@Profile("e2e")
@ConditionalOnProperty(
    prefix = "kk-studio.e2e-provider-sync",
    name = "enabled",
    havingValue = "true")
public class E2eProviderCredentialEnvironmentConfiguration {

  @Bean
  ApplicationRunner e2eProviderCredentialEnvironmentSyncRunner(
      AgentProviderService agentProviderService, Environment environment) {
    return arguments ->
        new E2eProviderCredentialEnvironmentSynchronizer(
                agentProviderService, environment::getProperty)
            .synchronize();
  }
}
