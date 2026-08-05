package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.runtime.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.spring.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.spring.postgresql.PostgresqlWorkListener;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RedisRealtimeConfig;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RedisRealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.spring.resource.LocalFileResourceStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 组合根装配测试：完整 Spring 上下文（{@code workers-enabled=false}）中验证 bean 构造、资源根目录、Redis 配置、 executor
 * 拒绝策略与「控制平面可用但 worker 不启动」。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    classes = WebTestApplication.class)
class HarnessRuntimeConfigurationTest {

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
          .withDatabaseName("kk_studio_web_runtime_config_test");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.multi.primary.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.multi.primary.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.multi.primary.username", POSTGRES::getUsername);
    registry.add("spring.datasource.multi.primary.password", POSTGRES::getPassword);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "false");
  }

  @Autowired private HarnessRuntimeProperties properties;
  @Autowired private HarnessStore harnessStore;
  @Autowired private ResourceStore resourceStore;
  @Autowired private RedisRealtimeConfig redisRealtimeConfig;
  @Autowired private RealtimeEventSink realtimeEventSink;
  @Autowired private RealtimeEventTail realtimeEventTail;
  @Autowired private InvocationRetryPolicy invocationRetryPolicy;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ModelProcessor modelProcessor;
  @Autowired private ToolProcessor toolProcessor;
  @Autowired private HarnessRuntime harnessRuntime;
  @Autowired private HarnessWorkDispatcher harnessWorkDispatcher;
  @Autowired private PostgresqlWorkListener postgresqlWorkListener;
  @Autowired private EnvironmentReadyListener environmentReadyListener;

  @Autowired
  @Qualifier("harnessRuntimeLifecycle")
  private SmartLifecycle harnessRuntimeLifecycle;

  @Autowired
  @Qualifier("harnessDispatcherDrainExecutor")
  private ExecutorService drainExecutor;

  @Autowired
  @Qualifier("harnessDispatcherWorkerExecutor")
  private ExecutorService workerExecutor;

  @Test
  void composesTheFullRuntimeBeanGraph() {
    assertInstanceOf(PostgresqlHarnessStore.class, harnessStore);
    assertInstanceOf(LocalFileResourceStore.class, resourceStore);
    assertInstanceOf(RedisRealtimeEventTail.class, realtimeEventTail);
    assertNotNull(realtimeEventSink);
    assertNotNull(threadProcessor);
    assertNotNull(modelProcessor);
    assertNotNull(toolProcessor);
    assertNotNull(harnessRuntime);
    assertNotNull(harnessWorkDispatcher);
    assertNotNull(postgresqlWorkListener);
    assertNotNull(environmentReadyListener);
    assertEquals(InvocationRetryPolicy.DEFAULT, invocationRetryPolicy);
  }

  @Test
  void resourceStoreRootIsCreatedUnderEnvironmentRootAtStartup() {
    Path root = properties.resolvedEnvironmentRoot().resolve(".kkstudio").resolve("resources");
    assertTrue(Files.isDirectory(root), "resource store root must exist: " + root);
  }

  @Test
  void resourceStoreRejectsOverBudgetContent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            resourceStore.put(
                "text/plain", "too-big.bin", new byte[properties.getResourceMaxBytes() + 1]));
  }

  @Test
  void redisRealtimeConfigUsesDeploymentDefaults() {
    assertEquals("kk-studio:harness:realtime:", redisRealtimeConfig.prefix());
    assertEquals(5_000L, redisRealtimeConfig.maxLength());
  }

  @Test
  void workersDisabledKeepsControlPlaneButDoesNotStartWorkers() {
    assertFalse(properties.isWorkersEnabled(), "web test profile must keep workers disabled");
    assertNotNull(harnessRuntime, "control/query plane must stay available");
    assertFalse(harnessRuntimeLifecycle.isRunning());
    assertFalse(postgresqlWorkListener.isRunning());
  }

  @Test
  void workerExecutorIsBoundedWithAbortPolicy() {
    assertInstanceOf(ThreadPoolExecutor.class, workerExecutor);
    ThreadPoolExecutor pool = (ThreadPoolExecutor) workerExecutor;
    assertEquals(16, pool.getCorePoolSize());
    assertEquals(16, pool.getMaximumPoolSize());
    assertInstanceOf(LinkedBlockingQueue.class, pool.getQueue());
    assertEquals(64, pool.getQueue().remainingCapacity());
    assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
  }

  @Test
  void drainExecutorRunsTasksSeriallyOffTheCallingThread() throws Exception {
    AtomicReference<Thread> first = new AtomicReference<>();
    AtomicReference<Thread> second = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(2);
    drainExecutor.execute(
        () -> {
          first.set(Thread.currentThread());
          latch.countDown();
        });
    drainExecutor.execute(
        () -> {
          second.set(Thread.currentThread());
          latch.countDown();
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(first.get(), second.get());
    assertNotEquals(Thread.currentThread(), first.get(), "drain executor must not run inline");
  }
}
