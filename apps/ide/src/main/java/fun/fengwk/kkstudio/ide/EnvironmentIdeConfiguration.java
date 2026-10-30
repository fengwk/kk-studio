package fun.fengwk.kkstudio.ide;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** IDE 视图控制的 Platform 装配：控制服务、绑定缓存、租约协调与一次性 bootstrap 票据存储。 */
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

  @Bean
  @ConditionalOnMissingBean
  public IdeViewBindingRegistry ideViewBindingRegistry() {
    return new IdeViewBindingRegistry();
  }

  @Bean(name = "localEnvironmentIdeService")
  @ConditionalOnMissingBean(name = "localEnvironmentIdeService")
  public EnvironmentIdeServiceImpl localEnvironmentIdeService(
      IdeCommandTransport ideCommandTransport,
      IdeBootstrapTicketService bootstrapTickets,
      IdeBrowserUseGateway browserUse,
      IdeViewBindingRegistry bindings,
      ObjectProvider<IdeViewAuthorizationListener> viewAuthorization) {
    return new EnvironmentIdeServiceImpl(
        ideCommandTransport,
        bootstrapTickets,
        browserUse,
        bindings,
        EnvironmentIdeServiceImpl.DEFAULT_COMMAND_TIMEOUT,
        viewAuthorization.getIfAvailable());
  }

  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean(name = "ideLeaseExecutor")
  public ExecutorService ideLeaseExecutor(ContextSnapshotFactory contextSnapshotFactory) {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(), contextSnapshotFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public IdeLeaseCoordinator ideLeaseCoordinator(
      @Qualifier("localEnvironmentIdeService") EnvironmentIdeService localEnvironmentIdeService,
      IdeCommandTransport ideCommandTransport,
      IdeBrowserUseGateway browserUse,
      @Qualifier("ideLeaseExecutor") ExecutorService ideLeaseExecutor) {
    return new IdeLeaseCoordinator(
        localEnvironmentIdeService, ideCommandTransport, browserUse, ideLeaseExecutor);
  }
}
