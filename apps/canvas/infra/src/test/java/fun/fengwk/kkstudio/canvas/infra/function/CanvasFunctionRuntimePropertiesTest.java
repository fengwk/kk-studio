package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

/** 部署属性必须提供稳定默认值，并在创建有界 executor 前严格拒绝非法拓扑。 */
class CanvasFunctionRuntimePropertiesTest {

  @Test
  void defaultsMatchTheDurableWorkerDeploymentContract() {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.validate();
    assertEquals(2, properties.getWorkerConcurrency());
    assertEquals(2, properties.getMaxDispatchTasks());
    assertEquals(30_000, properties.getLeaseDurationMillis());
    assertEquals(10_000, properties.getHeartbeatIntervalMillis());
    assertEquals(1_000, properties.getPollIntervalMillis());
    assertEquals(1_000, properties.getRejectionDelayMillis());
  }

  /** worker executor 必须固定 N + SynchronousQueue + AbortPolicy，不能形成第二个内存等待队列。 */
  @Test
  void workerExecutorHasNoInMemoryQueue() {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    ExecutorService executor =
        new CanvasFunctionRuntimeConfiguration().canvasFunctionWorkerExecutor(properties);
    try {
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      assertEquals(2, pool.getCorePoolSize());
      assertEquals(2, pool.getMaximumPoolSize());
      assertEquals(SynchronousQueue.class, pool.getQueue().getClass());
      assertEquals(
          ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler().getClass());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsInvalidConcurrencyAndHeartbeatLeaseOrdering() {
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.setWorkerConcurrency(0);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties.setWorkerConcurrency(2);
    properties.setMaxDispatchTasks(3);
    assertThrows(IllegalArgumentException.class, properties::validate);

    properties.setMaxDispatchTasks(2);
    properties.setHeartbeatIntervalMillis(properties.getLeaseDurationMillis());
    assertThrows(IllegalArgumentException.class, properties::validate);
  }
}
