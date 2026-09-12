package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentSessionListener;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlHarnessStore;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSink;
import fun.fengwk.kkstudio.harness.infra.postgresql.PostgresqlRealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.realtime.RealtimeEventSource;
import fun.fengwk.kkstudio.harness.infra.resource.LocalFileResourceStore;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicy;
import fun.fengwk.kkstudio.harness.runtime.retry.InvocationRetryPolicyProvider;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessDispatcherProperties;
import fun.fengwk.kkstudio.platform.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.platform.harness.task.SystemPromptPreviewService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.web.WebTestApplication;
import fun.fengwk.kkstudio.web.events.postgresql.PostgresqlNotificationLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Web 组合根装配测试：完整 Spring 上下文（{@code workers-enabled=false}）中验证 bean 构造、资源根目录、PostgreSQL
 * realtime、executor 拒绝策略与「控制平面可用但 worker 不启动」。
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
    // HarnessRuntimeConfiguration 的众多装配 bean 在上下文创建期会通过 SystemSettingsSnapshot 读取一次
    // system_setting 默认行，
    // 因此该测试上下文加载前必须完成 baseline 迁移（V1 默认行 + Harness 表），否则缺行会启动失败。
    try (Connection conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      Flyway.configure()
          .dataSource(new SingleConnectionDataSource(conn, true))
          .locations("classpath:db/migration")
          .validateMigrationNaming(true)
          .load()
          .migrate();
    } catch (SQLException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @DynamicPropertySource
  static void overrideDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.driver-class-name", Driver.class::getName);
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("kk-studio.harness.runtime.workers-enabled", () -> "false");
    registry.add("kk-studio.harness.dispatcher.max-dispatch-tasks", () -> "7");
    registry.add("kk-studio.harness.dispatcher.lease-duration", () -> "45s");
    registry.add("kk-studio.harness.dispatcher.poll-interval", () -> "2s");
    registry.add("kk-studio.harness.dispatcher.rejection-delay", () -> "250ms");
    registry.add("kk-studio.harness.dispatcher.worker.concurrency", () -> "3");
    registry.add("kk-studio.harness.dispatcher.worker.queue-capacity", () -> "5");
  }

  @Autowired private HarnessRuntimeProperties properties;
  @Autowired private HarnessStore harnessStore;
  @Autowired private ResourceStore resourceStore;
  @Autowired private SystemSettingsSnapshot systemSettingsSnapshot;
  @Autowired private RealtimeEventSink realtimeEventSink;
  @Autowired private RealtimeEventSource realtimeEventSource;
  @Autowired private InvocationRetryPolicyProvider invocationRetryPolicyProvider;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ModelProcessor modelProcessor;
  @Autowired private ToolProcessor toolProcessor;
  @Autowired private HarnessRuntime harnessRuntime;
  @Autowired private SystemPromptPreviewService systemPromptPreviewService;
  @Autowired private HarnessWorkDispatcher harnessWorkDispatcher;
  @Autowired private HarnessDispatcherProperties harnessDispatcherProperties;
  @Autowired private PostgresqlNotificationLoop postgresqlNotificationLoop;
  @Autowired private EnvironmentSessionListener environmentSessionListener;

  @Autowired
  @Qualifier("harnessRuntimeLifecycle")
  private SmartLifecycle harnessRuntimeLifecycle;

  @Autowired
  @Qualifier("harnessDispatcherDrainExecutor")
  private ExecutorService drainExecutor;

  @Autowired
  @Qualifier("harnessDispatcherWorkerExecutor")
  private ExecutorService workerExecutor;

  @Autowired
  @Qualifier("harnessModelFlushExecutor")
  private ExecutorService flushExecutor;

  @Test
  void composesTheFullRuntimeBeanGraph() {
    assertInstanceOf(PostgresqlHarnessStore.class, harnessStore);
    assertInstanceOf(LocalFileResourceStore.class, resourceStore);
    assertInstanceOf(PostgresqlRealtimeEventSource.class, realtimeEventSource);
    assertInstanceOf(PostgresqlRealtimeEventSink.class, realtimeEventSink);
    assertNotNull(threadProcessor);
    assertNotNull(modelProcessor);
    assertNotNull(toolProcessor);
    assertNotNull(harnessRuntime);
    assertNotNull(systemPromptPreviewService);
    assertNotNull(harnessWorkDispatcher);
    assertNotNull(harnessDispatcherProperties);
    assertEquals(7, harnessDispatcherProperties.getMaxDispatchTasks());
    assertEquals(Duration.ofSeconds(45), harnessDispatcherProperties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(2), harnessDispatcherProperties.getPollInterval());
    assertEquals(Duration.ofMillis(250), harnessDispatcherProperties.getRejectionDelay());
    assertEquals(3, harnessDispatcherProperties.getWorker().getConcurrency());
    assertEquals(5, harnessDispatcherProperties.getWorker().getQueueCapacity());
    assertNotNull(postgresqlNotificationLoop);
    assertNotNull(environmentSessionListener);
    assertEquals(
        new InvocationRetryPolicy(
            3,
            InvocationRetryBackoffStrategy.EXPONENTIAL,
            Duration.ofSeconds(2),
            Duration.ofSeconds(60)),
        invocationRetryPolicyProvider.retryPolicy());
  }

  @Test
  void resourceStoreRootIsCreatedAtStartup() {
    Path root = properties.resolvedResourceRoot();
    assertTrue(Files.isDirectory(root), "resource store root must exist: " + root);
  }

  @Test
  void resourceStoreRejectsOverBudgetContent() {
    int resourceMaxBytes =
        Math.toIntExact(systemSettingsSnapshot.get().advanced().resourceMaxBytes());
    assertThrows(
        IllegalArgumentException.class,
        () -> resourceStore.put("text/plain", "too-big.bin", new byte[resourceMaxBytes + 1]));
  }

  @Test
  void workersDisabledKeepsControlPlaneButDoesNotStartWorkers() {
    assertFalse(properties.isWorkersEnabled(), "web test profile must keep workers disabled");
    assertNotNull(harnessRuntime, "control/query plane must stay available");
    assertFalse(harnessRuntimeLifecycle.isRunning());
    assertTrue(
        postgresqlNotificationLoop.isRunning(),
        "application notification loop remains active for Thread/Canvas when workers are disabled");
  }

  /**
   * 验证 Dispatcher 的 worker executor 是由配置确定的有界平台线程池：固定并发、有界阻塞队列、 AbortPolicy 拒绝策略（fail-fast
   * 触发退避归还），以及平台线程工厂命名规范。
   */
  @Test
  void workerExecutorIsBoundedWithAbortPolicy() {
    assertInstanceOf(ThreadPoolExecutor.class, workerExecutor);
    ThreadPoolExecutor pool = (ThreadPoolExecutor) workerExecutor;
    assertEquals(3, pool.getCorePoolSize());
    assertEquals(3, pool.getMaximumPoolSize());
    assertInstanceOf(LinkedBlockingQueue.class, pool.getQueue());
    assertEquals(5, pool.getQueue().remainingCapacity());
    assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
    Thread thread = pool.getThreadFactory().newThread(() -> {});
    assertNotNull(thread);
    assertFalse(thread.isVirtual());
    assertTrue(thread.getName().startsWith("harness-dispatch-worker-"));
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

  @Test
  void flushExecutorRunsOnVirtualThread() throws Exception {
    AtomicBoolean isVirtual = new AtomicBoolean();
    CountDownLatch latch = new CountDownLatch(1);
    flushExecutor.execute(
        () -> {
          isVirtual.set(Thread.currentThread().isVirtual());
          latch.countDown();
        });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertTrue(isVirtual.get(), "flush executor must use virtual threads");
  }
}
