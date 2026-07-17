package fun.fengwk.kkstudio.core.environment.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Wires the durable Environment gateway polling lifecycle without coupling it to WebSocket APIs.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnvironmentGatewayProperties.class)
public class EnvironmentDaemonGatewayConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public EnvironmentDaemonGatewayLifecycle environmentDaemonGatewayLifecycle(
      EnvironmentDaemonGateway gateway,
      HarnessRuntimeProperties runtimeProperties,
      @Qualifier("harnessWorkerScheduler") ScheduledExecutorService scheduler) {
    return new EnvironmentDaemonGatewayLifecycle(gateway, runtimeProperties, scheduler);
  }
}
