package fun.fengwk.kkstudio.ide.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.ide.EnvironmentIdeConfiguration;
import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeBootstrapTicketService;
import fun.fengwk.kkstudio.ide.IdeBrowserUseGateway;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeLifecycle;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodePresence;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterPresenceConfiguration;
import fun.fengwk.kkstudio.platform.cluster.ClusterProperties;
import fun.fengwk.kkstudio.platform.cluster.RealtimeSubscriptionService;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;

import javax.sql.DataSource;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 意图：同时装配既有 IDE 配置与本包配置必须无循环/重复——本地 delegate 以 {@code localEnvironmentIdeService} 暴露，组合服务以
 * {@code @Primary EnvironmentIdeService} 暴露，且上下文一次即可启动。
 */
class ClusterEnvironmentIdeConfigurationTest {

  /** 没有 IDE delegate 时，认证、节点注册、共享 HTTP 客户端与存在快照仍可独立装配。 */
  @Test
  void genericInternalBeansAreAvailableWithoutIdeContext() {
    assertDoesNotThrow(
        () -> {
          try (AnnotationConfigApplicationContext context =
              new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
            context.registerBean(Clock.class, () -> Clock.systemUTC());
            context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            context.register(
                ClusterEnvironmentIdeConfiguration.class, ClusterPresenceConfiguration.class);
            context.refresh();

            assertNotNull(context.getBean(ClusterNodeRegistry.class));
            assertNotNull(context.getBean(ClusterInternalAuthentication.class));
            assertNotNull(context.getBean("clusterInternalHttpClient", HttpClient.class));
            assertNotNull(context.getBean(IdeClusterClient.class));
            assertNotNull(context.getBean(ClusterProperties.class));
            assertNotNull(context.getBean(ClusterNodePresence.class));
            assertNotNull(context.getBean(RealtimeSubscriptionService.class));
            assertTrue(context.getBeansOfType(EnvironmentIdeService.class).isEmpty());
          }
        });
  }

  @Test
  void unknownSslBundleFailsStartup() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test", Map.of("kk-studio.cluster.ssl-bundle", "missing-cluster-bundle")));
      context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
      context.registerBean(Clock.class, Clock::systemUTC);
      context.registerBean("nodeInstanceId", UUID.class, UUID::randomUUID);
      context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
      context.registerBean(SslBundles.class, DefaultSslBundleRegistry::new);
      context.register(
          ClusterEnvironmentIdeConfiguration.class, ClusterPresenceConfiguration.class);

      BeanCreationException failure = assertThrows(BeanCreationException.class, context::refresh);
      NoSuchSslBundleException cause =
          assertInstanceOf(NoSuchSslBundleException.class, failure.getMostSpecificCause());
      assertTrue(cause.getMessage().contains("missing-cluster-bundle"));
    }
  }

  @Test
  void contextWiresThePrimaryClusterServiceWithoutCycleOrDuplicate() {
    assertDoesNotThrow(
        () -> {
          try (AnnotationConfigApplicationContext context =
              new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
            context.registerBean(
                JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)));
            context.registerBean(IdeCommandTransport.class, () -> mock(IdeCommandTransport.class));
            context.registerBean(ResourceAccess.class, () -> mock(ResourceAccess.class));
            context.registerBean(
                SessionBlobRefManager.class, () -> mock(SessionBlobRefManager.class));
            context.registerBean(
                StorageBlobRepository.class, () -> mock(StorageBlobRepository.class));
            context.registerBean(
                StorageBlobContentService.class, () -> mock(StorageBlobContentService.class));
            context.registerBean(
                IdeBrowserUseGateway.class, () -> mock(IdeBrowserUseGateway.class));
            context.registerBean(IdeBootstrapTicketService.class, IdeBootstrapTicketService::new);
            context.registerBean(Clock.class, () -> Clock.systemUTC());
            context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
            context.registerBean(ObjectMapper.class, () -> JsonMapper.builder().build());
            context.registerBean(
                ContextSnapshotFactory.class,
                () -> ContextSnapshotFactory.builder().clearMissing(true).build());
            context.register(
                EnvironmentIdeConfiguration.class,
                ClusterEnvironmentIdeConfiguration.class,
                ClusterPresenceConfiguration.class);
            context.refresh();

            assertInstanceOf(
                ClusterEnvironmentIdeService.class, context.getBean(EnvironmentIdeService.class));
            assertInstanceOf(
                EnvironmentIdeServiceImpl.class, context.getBean("localEnvironmentIdeService"));
            assertNotNull(context.getBean(ClusterNodeRegistry.class));
            assertNotNull(context.getBean(ClusterInternalAuthentication.class));
            assertNotNull(context.getBean(IdeClusterClient.class));
            assertNotNull(context.getBean(ClusterNodePresence.class));
            assertNotNull(context.getBean(RealtimeSubscriptionService.class));
            assertNotNull(context.getBean(ClusterNodeLifecycle.class));
            assertEquals(1, context.getBeansOfType(IdeClusterClient.class).size());
          }
        });
  }
}
