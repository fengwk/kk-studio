package fun.fengwk.kkstudio.platform.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                      "kk-studio.cluster.internal-url", "https://platform-only.internal:8443",
                      "kk-studio.cluster.ssl-bundle", "cluster-internal",
                      "kk-studio.cluster.secret", "test-cluster-secret-0123456789abcdef")));
      context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
      context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
      context.register(ClusterPresenceConfiguration.class);
      context.refresh();

      ClusterProperties properties = context.getBean(ClusterProperties.class);
      assertEquals("https://platform-only.internal:8443", properties.getInternalUrl());
      assertEquals("cluster-internal", properties.getSslBundle());
      assertTrue(properties.remoteRoutingConfigured());
      assertNotNull(context.getBean(RealtimeSubscriptionService.class));
    }
  }

  /** 明文 http 的内部地址在属性绑定时即失败，使应用启动失败并指明配置项。 */
  @Test
  void plaintextInternalUrlFailsStartup() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "cluster-test",
                  Map.of("kk-studio.cluster.internal-url", "http://platform-only.internal:8080")));
      context.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
      context.registerBean("nodeInstanceId", UUID.class, () -> UUID.randomUUID());
      context.register(ClusterPresenceConfiguration.class);

      RuntimeException error = assertThrows(RuntimeException.class, context::refresh);

      assertTrue(
          messageChain(error).contains("kk-studio.cluster.internal-url"),
          "startup failure must name the config item: " + messageChain(error));
    }
  }

  private static String messageChain(Throwable error) {
    StringBuilder messages = new StringBuilder();
    for (Throwable current = error; current != null; current = current.getCause()) {
      messages.append(current.getMessage()).append('\n');
      if (current.getCause() == current) {
        break;
      }
    }
    return messages.toString();
  }
}
