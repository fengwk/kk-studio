package fun.fengwk.kkstudio.platform.environment.ide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;

/** IDE 视图控制的 Platform 装配：绑定独立 Origin 配置并暴露控制服务与一次性 bootstrap 票据存储。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdeOriginProperties.class)
public class EnvironmentIdeConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public IdeBootstrapTicketService ideBootstrapTicketService() {
    return new InMemoryIdeBootstrapTicketService();
  }

  @Bean
  @ConditionalOnMissingBean
  public EnvironmentIdeService environmentIdeService(
      IdeCommandTransport ideCommandTransport,
      IdeOriginProperties originProperties,
      IdeBootstrapTicketService bootstrapTickets) {
    return new EnvironmentIdeServiceImpl(ideCommandTransport, originProperties, bootstrapTickets);
  }
}
