package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.ide.gateway.IdeBrowserSessionRegistry;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

/** 意图：唯一快照存储依赖的 {@link IdeCurrentGeneration} 由 {@link IdeCommandTransport} 唯一提供，装配不成环。 */
class IdeCurrentGenerationWiringTest {

  @Test
  void snapshotsResolveTheTransportAsCurrentGeneration() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          EnvironmentDaemonServer.class, () -> mock(EnvironmentDaemonServer.class));
      context.registerBean(IdeCommandTransport.class);
      context.registerBean(IdeViewSnapshotStore.class);
      context.registerBean(IdeBrowserSessionRegistry.class);
      context.refresh();

      IdeCommandTransport transport = context.getBean(IdeCommandTransport.class);
      assertSame(transport, context.getBean(IdeCurrentGeneration.class));
      assertNotNull(context.getBean(IdeViewSnapshotStore.class));
      assertNotNull(context.getBean(IdeBrowserSessionRegistry.class));
    }
  }
}
