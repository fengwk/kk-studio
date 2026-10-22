package fun.fengwk.kkstudio.ide;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;

/** IDE 视图控制的 Platform 装配：暴露控制服务与一次性 bootstrap 票据存储。 */
@Configuration(proxyBeanMethods = false)
public class EnvironmentIdeConfiguration {

  @Bean
  public IdeCommandTransport ideCommandTransport(EnvironmentDaemonServer server) {
    return new DaemonIdeCommandTransport(server);
  }

  @Bean
  @ConditionalOnMissingBean
  public IdeBootstrapTicketService ideBootstrapTicketService() {
    return new InMemoryIdeBootstrapTicketService();
  }

  @Bean(name = "localEnvironmentIdeService")
  @ConditionalOnMissingBean(name = "localEnvironmentIdeService")
  public EnvironmentIdeServiceImpl localEnvironmentIdeService(
      IdeCommandTransport ideCommandTransport,
      IdeBootstrapTicketService bootstrapTickets,
      ObjectProvider<IdeViewAuthorizationListener> viewAuthorization) {
    return new EnvironmentIdeServiceImpl(
        ideCommandTransport,
        bootstrapTickets,
        EnvironmentIdeServiceImpl.DEFAULT_COMMAND_TIMEOUT,
        viewAuthorization.getIfAvailable());
  }
}
