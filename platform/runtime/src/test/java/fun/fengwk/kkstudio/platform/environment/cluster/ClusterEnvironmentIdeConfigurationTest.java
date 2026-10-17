package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.server.IdeCommandTransport;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeConfiguration;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.environment.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.platform.environment.ide.InMemoryIdeBootstrapTicketService;

import java.time.Clock;
import java.util.UUID;

/**
 * 意图：同时装配既有 IDE 配置与本包配置必须无循环/重复——本地 delegate 以 {@code localEnvironmentIdeService} 暴露，组合服务以
 * {@code @Primary EnvironmentIdeService} 暴露，且上下文一次即可启动。
 */
class ClusterEnvironmentIdeConfigurationTest {

  @Test
  void contextWiresThePrimaryClusterServiceWithoutCycleOrDuplicate() {
    assertDoesNotThrow(
        () -> {
          try (AnnotationConfigApplicationContext context =
              new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
            context.registerBean(IdeCommandTransport.class, () -> mock(IdeCommandTransport.class));
            context.registerBean(
                IdeBootstrapTicketService.class, () -> new InMemoryIdeBootstrapTicketService());
            context.registerBean(Clock.class, () -> Clock.systemUTC());
            context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            context.register(
                EnvironmentIdeConfiguration.class, ClusterEnvironmentIdeConfiguration.class);
            context.refresh();

            assertInstanceOf(
                ClusterEnvironmentIdeService.class, context.getBean(EnvironmentIdeService.class));
            assertInstanceOf(
                EnvironmentIdeServiceImpl.class, context.getBean("localEnvironmentIdeService"));
            assertNotNull(context.getBean(ClusterNodeRegistry.class));
            assertNotNull(context.getBean(ClusterInternalAuthentication.class));
            assertNotNull(context.getBean(ClusterInternalClient.class));
            assertEquals(1, context.getBeansOfType(ClusterInternalClient.class).size());
          }
        });
  }
}
