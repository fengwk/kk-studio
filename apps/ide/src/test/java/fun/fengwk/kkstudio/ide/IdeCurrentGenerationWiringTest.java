package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.ide.gateway.IdeBrowserSessionRegistry;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

/**
 * 意图：授权与路由登记依赖的 {@link IdeCurrentGeneration} 由 {@link IdeCommandTransport} 唯一提供，装配不成环。
 *
 * <p>{@code IdeCommandTransport} → {@code
 * EnvironmentDaemonServer}，登记侧只单向依赖该接口；本测试固定这一形状，避免以后反向依赖造成循环装配。
 */
class IdeCurrentGenerationWiringTest {

  @Test
  void registriesResolveTheTransportAsCurrentGeneration() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          EnvironmentDaemonServer.class, () -> mock(EnvironmentDaemonServer.class));
      context.registerBean(IdeCommandTransport.class);
      context.registerBean(IdeViewBindingRegistry.class);
      context.registerBean(IdeBrowserSessionRegistry.class);
      context.refresh();

      IdeCommandTransport transport = context.getBean(IdeCommandTransport.class);
      assertSame(transport, context.getBean(IdeCurrentGeneration.class));
      assertNotNull(context.getBean(IdeViewBindingRegistry.class));
      assertNotNull(context.getBean(IdeBrowserSessionRegistry.class));
    }
  }
}
