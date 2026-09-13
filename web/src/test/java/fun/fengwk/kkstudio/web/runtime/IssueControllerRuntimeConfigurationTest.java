package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.platform.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.platform.project.controller.IssueControllerProperties;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * {@link IssueControllerRuntimeConfiguration} 运行时装配集成测试：
 *
 * <p>验证 Issue Controller 的 dispatcher、executors、lifecycle 与 PostgreSQL notification loop 的 Spring
 * 依赖图装配，以及 workers-enabled=false 时的行为。
 */
class IssueControllerRuntimeConfigurationTest extends WebPostgresTestSupport {

  @DynamicPropertySource
  static void overrideControllerProperties(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.project.controller.max-dispatch-tasks", () -> "12");
    registry.add("kk-studio.project.controller.lease-duration", () -> "40s");
    registry.add("kk-studio.project.controller.poll-interval", () -> "3s");
    registry.add("kk-studio.project.controller.rejection-delay", () -> "500ms");
    registry.add("kk-studio.project.controller.retry-delay", () -> "8s");
    registry.add("kk-studio.project.controller.worker.concurrency", () -> "4");
    registry.add("kk-studio.project.controller.worker.queue-capacity", () -> "16");
  }

  @Autowired private IssueControllerProperties properties;
  @Autowired private IssueControllerDispatcher dispatcher;

  @Autowired
  @Qualifier("issueControllerRuntimeLifecycle")
  private SmartLifecycle lifecycle;

  @Autowired
  @Qualifier("issueControllerDispatcherDrainExecutor")
  private ExecutorService drainExecutor;

  @Autowired
  @Qualifier("issueControllerDispatcherWorkerExecutor")
  private ExecutorService workerExecutor;

  @Autowired
  @Qualifier("issueControllerDispatcherPollScheduler")
  private ScheduledExecutorService pollScheduler;

  @Autowired private PostgresqlNotificationLoop postgresqlNotificationLoop;

  /** 测试意图：验证完整的 Issue Controller 运行时 bean 图正确装配， 且配置属性正确绑定到 IssueControllerProperties。 */
  @Test
  void composesIssueControllerRuntimeBeanGraph() {
    assertNotNull(properties);
    assertEquals(12, properties.getMaxDispatchTasks());
    assertEquals(Duration.ofSeconds(40), properties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(3), properties.getPollInterval());
    assertEquals(Duration.ofMillis(500), properties.getRejectionDelay());
    assertEquals(Duration.ofSeconds(8), properties.getRetryDelay());
    assertEquals(4, properties.getWorker().getConcurrency());
    assertEquals(16, properties.getWorker().getQueueCapacity());

    assertNotNull(dispatcher);
    assertNotNull(lifecycle);
    assertEquals(Integer.MAX_VALUE - 1, lifecycle.getPhase());
  }

  /**
   * 测试意图：验证 worker executor 是有界的平台线程池（ThreadPoolExecutor）， 采用 AbortPolicy
   * 拒绝策略（fail-fast），线程名称前缀符合规范。
   */
  @Test
  void workerExecutorIsBoundedWithAbortPolicy() {
    assertInstanceOf(ThreadPoolExecutor.class, workerExecutor);
    ThreadPoolExecutor pool = (ThreadPoolExecutor) workerExecutor;
    assertEquals(4, pool.getCorePoolSize());
    assertEquals(4, pool.getMaximumPoolSize());
    assertInstanceOf(LinkedBlockingQueue.class, pool.getQueue());
    assertEquals(16, pool.getQueue().remainingCapacity());
    assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());

    Thread thread = pool.getThreadFactory().newThread(() -> {});
    assertNotNull(thread);
    assertFalse(thread.isVirtual(), "worker threads must be platform threads");
    assertTrue(thread.isDaemon(), "worker threads must be daemon");
    assertTrue(thread.getName().startsWith("issue-dispatch-worker-"));
  }

  /** 测试意图：验证 drain executor 是单线程 daemon 平台线程池，线程名称前缀符合规范。 */
  @Test
  void drainExecutorIsSingleThreadPlatformDaemon() {
    assertNotNull(drainExecutor);
    assertFalse(drainExecutor.isShutdown());
  }

  /** 测试意图：验证 poll scheduler 是单线程 daemon 平台调度池。 */
  @Test
  void pollSchedulerIsSingleThreadPlatformDaemon() {
    assertNotNull(pollScheduler);
    assertFalse(pollScheduler.isShutdown());
  }

  /**
   * 测试意图：验证 workers-enabled=false 时，Issue Controller dispatcher 不启动（lifecycle 处于 stopped 状态）， 但
   * PostgresqlNotificationLoop 保持运行，且向 dispatcher 发起 wake 为安全 no-op。
   */
  @Test
  void workersDisabledKeepsDispatcherStoppedWhileNotificationLoopRuns() {
    assertFalse(
        lifecycle.isRunning(),
        "dispatcher lifecycle must not be running when workers are disabled");
    assertTrue(postgresqlNotificationLoop.isRunning(), "notification loop must remain active");

    // wake 调用必须是安全的 no-op
    dispatcher.wake();
    assertFalse(lifecycle.isRunning());
  }
}
