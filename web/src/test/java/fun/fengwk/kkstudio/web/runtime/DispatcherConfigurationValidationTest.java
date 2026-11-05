package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeConfiguration;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionRuntimeProperties;
import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionWorker;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessDispatcherProperties;
import fun.fengwk.kkstudio.platform.harness.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileProperties;
import fun.fengwk.kkstudio.project.reconcile.IssueReconcileWorkHandler;
import fun.fengwk.kkstudio.work.BoundedWorkDispatcher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

class DispatcherConfigurationValidationTest {

  /** 默认部署值能够完成同一组装路径的 Spring 启动。 */
  @Test
  void defaultsStartSuccessfully() {
    for (Class<?> beans : List.of(HarnessBeans.class, IssueBeans.class, CanvasBeans.class)) {
      new ApplicationContextRunner()
          .withUserConfiguration(beans)
          .run(context -> assertNull(context.getStartupFailure()));
    }
  }

  /** 通过 Spring 绑定部署属性，确认三个真实装配路径在启动时拒绝非法配置并给出前缀。 */
  @Test
  void invalidDeploymentValuesFailAtStartup() {
    check(
        HarnessBeans.class,
        "kk-studio.harness.dispatcher.",
        "lease-duration=0ms",
        "lease-duration=PT0.0015S",
        "poll-interval=0ms",
        "rejection-delay=-1ms",
        "max-dispatch-tasks=0",
        "worker.concurrency=0",
        "worker.queue-capacity=0");
    check(
        IssueBeans.class,
        "kk-studio.project.reconcile.",
        "lease-duration=0ms",
        "poll-interval=PT0.0015S",
        "rejection-delay=0ms",
        "retry-delay=0ms",
        "active-delay=PT0.0015S",
        "blocked-delay=-1ms",
        "max-dispatch-tasks=0",
        "worker.concurrency=0",
        "worker.queue-capacity=0");
    check(
        CanvasBeans.class,
        "kk-studio.canvas.function.runtime.",
        "worker-concurrency=0",
        "max-dispatch-tasks=0",
        "max-dispatch-tasks=3",
        "lease-duration-millis=0",
        "heartbeat-interval-millis=0",
        "heartbeat-interval-millis=30000",
        "poll-interval-millis=0",
        "rejection-delay-millis=0");
  }

  /** 项目 reconcile 的部署默认值在属性类精简后保持不变。 */
  @Test
  void issueDefaultsRemainStable() {
    IssueReconcileProperties properties = new IssueReconcileProperties();
    assertEquals(Duration.ofSeconds(30), properties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(1), properties.getRejectionDelay());
    assertEquals(Duration.ofSeconds(5), properties.getRetryDelay());
    assertEquals(Duration.ofSeconds(1), properties.getActiveDelay());
    assertEquals(Duration.ofSeconds(60), properties.getBlockedDelay());
    assertEquals(64, properties.getMaxDispatchTasks());
    assertEquals(8, properties.getWorker().getConcurrency());
    assertEquals(64, properties.getWorker().getQueueCapacity());
  }

  private static void check(Class<?> beans, String prefix, String... invalidValues) {
    for (String value : invalidValues) {
      new ApplicationContextRunner()
          .withUserConfiguration(beans)
          .withPropertyValues(prefix + value)
          .run(
              context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure, prefix + value);
                boolean includesPrefix = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                  includesPrefix |=
                      cause.getMessage() != null
                          && cause.getMessage().contains(prefix.substring(0, prefix.length() - 1));
                }
                assertTrue(includesPrefix, prefix + value);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(HarnessDispatcherProperties.class)
  static class HarnessBeans {
    @Bean
    HarnessWorkDispatcherConfig config(HarnessDispatcherProperties properties) {
      return new HarnessRuntimeConfiguration().harnessWorkDispatcherConfig(properties);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService worker(HarnessDispatcherProperties properties) {
      return new HarnessRuntimeConfiguration().harnessDispatcherWorkerExecutor(properties);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(IssueReconcileProperties.class)
  static class IssueBeans {
    @Bean(destroyMethod = "shutdown")
    ExecutorService worker(IssueReconcileProperties properties) {
      return new IssueReconcileRuntimeConfiguration()
          .issueReconcileDispatcherWorkerExecutor(
              properties, ContextSnapshotFactory.builder().build());
    }

    @Bean
    BoundedWorkDispatcher<?> dispatcher(
        IssueReconcileProperties properties, ExecutorService worker) {
      return new IssueReconcileRuntimeConfiguration()
          .issueReconcileDispatcher(
              mock(IssueReconcileWorkHandler.class),
              properties,
              Runnable::run,
              worker,
              mock(ScheduledExecutorService.class));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CanvasFunctionRuntimeProperties.class)
  static class CanvasBeans {
    @Bean(destroyMethod = "shutdown")
    ExecutorService worker(CanvasFunctionRuntimeProperties properties) {
      return new CanvasFunctionRuntimeConfiguration()
          .canvasFunctionWorkerExecutor(properties, ContextSnapshotFactory.builder().build());
    }

    @Bean
    BoundedWorkDispatcher<?> dispatcher(
        CanvasFunctionRuntimeProperties properties, ExecutorService worker) {
      return new CanvasFunctionRuntimeConfiguration()
          .canvasFunctionDispatcher(
              mock(CanvasFunctionWorkStore.class),
              properties,
              Runnable::run,
              worker,
              mock(ScheduledExecutorService.class),
              mock(CanvasFunctionWorker.class));
    }
  }
}
