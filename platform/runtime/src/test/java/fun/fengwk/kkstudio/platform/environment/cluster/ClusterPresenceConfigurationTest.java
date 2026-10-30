package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

/** Platform-only 装配：公共集群配置自行绑定属性，并创建不依赖 IDE 模块的 realtime 订阅服务。 */
class ClusterPresenceConfigurationTest {

  /** 没有 IDE 配置时，集群属性与服务仍能由公共配置独立注入。 */
  @Test
  void platformOnlyContextBindsClusterPropertiesAndCreatesSubscriptionService() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "cluster-test",
                  Map.of(
                      "kk-studio.cluster.internal-url", "http://platform-only.internal:8080",
                      "kk-studio.cluster.secret", "test-cluster-secret-0123456789abcdef")));
      context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
      context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
      context.register(ClusterPresenceConfiguration.class);
      context.refresh();

      ClusterProperties properties = context.getBean(ClusterProperties.class);
      assertEquals("http://platform-only.internal:8080", properties.getInternalUrl());
      assertTrue(properties.remoteRoutingConfigured());
      assertNotNull(context.getBean(RealtimeSubscriptionService.class));
    }
  }
}
