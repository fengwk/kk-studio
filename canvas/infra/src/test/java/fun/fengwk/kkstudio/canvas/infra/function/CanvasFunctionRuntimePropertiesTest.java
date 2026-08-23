package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/** 部署属性必须提供稳定默认值，并在创建有界 executor 前严格拒绝非法拓扑。 */
class CanvasFunctionRuntimePropertiesTest {

  @Test
  void defaultsProduceTheExpectedBoundedExecutor() {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    CanvasFunctionRuntimeConfiguration configuration = new CanvasFunctionRuntimeConfiguration();

    ExecutorService executor = configuration.canvasFunctionExecutor(properties);
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(4, pool.getMaximumPoolSize());
      assertEquals(64, pool.getQueue().remainingCapacity());
      assertTrue(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsNonPositiveAndInvertedBounds() {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.setCoreSize(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties.setCoreSize(4);
    properties.setMaxSize(3);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties.setCoreSize(1);
    properties.setMaxSize(1);
    properties.setQueueCapacity(0);
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
