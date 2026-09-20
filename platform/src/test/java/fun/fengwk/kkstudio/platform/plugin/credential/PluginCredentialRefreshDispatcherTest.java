package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Plugin 凭据刷新调度器测试。
 *
 * <p>测试 SmartLifecycle 生命周期契约：启动后立即执行首次扫描、fixed-delay 循环调度、stop 后停止调度并安全等待、 wake 异步唤醒、start
 * 幂等以及异常不中断后续调度周期。
 */
class PluginCredentialRefreshDispatcherTest {

  @TempDir Path tempDir;

  private final Instant now = Instant.parse("2026-09-21T16:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private CountingRepository repository;
  private PluginCredentialRefreshService refreshService;
  private PluginProperties properties;
  private PluginCredentialRefreshDispatcher dispatcher;

  @BeforeEach
  void setUp() throws IOException {
    repository = new CountingRepository();
    PluginCredentialCodec codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("dispatcher-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());

    PluginDescriptor descriptor =
        new PluginDescriptor("dispatcher-plugin", "Dispatcher Plugin", "1.0", List.of("CN"));
    StudioPlugin plugin = () -> descriptor;
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    properties = new PluginProperties();
    properties.getRefresh().setPollDelay(Duration.ofMillis(50));
    properties.getRefresh().setLeaseDuration(Duration.ofMinutes(2));

    refreshService =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);
  }

  @AfterEach
  void tearDown() {
    if (dispatcher != null && dispatcher.isRunning()) {
      dispatcher.stop();
    }
  }

  /** start() 后立即执行一次扫描，随后按周期执行；stop() 后不再有新的扫描执行。 */
  @Test
  void startsImmediatelyAndStopsScheduling() throws InterruptedException {
    dispatcher = new PluginCredentialRefreshDispatcher(refreshService, properties);
    assertFalse(dispatcher.isRunning());

    dispatcher.start();
    assertTrue(dispatcher.isRunning());

    // 启动后立即扫描一次，等待至少执行 2 次扫描（首次 0ms 立即执行 + fixed-delay 50ms）
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());

    int countAtStop = repository.claimCalls.get();
    // 观察 300ms，确认停止后计数保持不变
    Thread.sleep(300);
    assertEquals(countAtStop, repository.claimCalls.get(), "no scans should occur after stop()");
  }

  /** start() 幂等：重复调用 start 不会创建重复调度线程或增加并发执行。 */
  @Test
  void startIsIdempotent() throws InterruptedException {
    dispatcher = new PluginCredentialRefreshDispatcher(refreshService, properties);
    dispatcher.start();
    dispatcher.start(); // 重复调用

    assertTrue(dispatcher.isRunning());
    awaitCondition(() -> repository.claimCalls.get() >= 1, 2000);

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
  }

  /** wake() 在未启动时不抛异常且不触发扫描；启动后能主动触发一次扫描。 */
  @Test
  void wakeTriggersScanWhenRunningAndIgnoredWhenStopped() throws InterruptedException {
    // 设置很长的 pollDelay，避免自动周期干扰
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    dispatcher = new PluginCredentialRefreshDispatcher(refreshService, properties);

    // 未启动时 wake 不报错且不触发扫描
    dispatcher.wake();
    assertEquals(0, repository.claimCalls.get());

    dispatcher.start();
    // 启动后立即有 1 次初始扫描
    awaitCondition(() -> repository.claimCalls.get() == 1, 2000);

    // 显式 wake 触发新一次扫描
    dispatcher.wake();
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    dispatcher.stop();
  }

  /** SmartLifecycle 契约：isRunning() 状态正确，getPhase() 为 Integer.MAX_VALUE（排在最后启动）。 */
  @Test
  void lifecycleAndPhaseContract() {
    dispatcher = new PluginCredentialRefreshDispatcher(refreshService, properties);
    assertEquals(Integer.MAX_VALUE, dispatcher.getPhase());
    assertFalse(dispatcher.isRunning());

    dispatcher.start();
    assertTrue(dispatcher.isRunning());

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
  }

  /** 单次扫描抛出未检查异常不会终止后续周期的调度。 */
  @Test
  void scanExceptionDoesNotTerminateScheduler() throws InterruptedException {
    repository.throwOnClaim.set(true);

    dispatcher = new PluginCredentialRefreshDispatcher(refreshService, properties);
    dispatcher.start();

    // 等待异常被捕获且触发了至少 2 次 claimCalls
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    // 恢复正常
    repository.throwOnClaim.set(false);
    int countBeforeRecovery = repository.claimCalls.get();

    // 验证后续周期依然在继续执行
    awaitCondition(() -> repository.claimCalls.get() > countBeforeRecovery, 2000);

    dispatcher.stop();
  }

  private static void awaitCondition(BooleanSupplier condition, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Condition not met within " + timeoutMillis + "ms");
      }
      Thread.sleep(10);
    }
  }

  /** 记录 claimDue 调用次数并在需要时模拟异常的测试仓库。 */
  private static class CountingRepository extends InMemoryPluginCredentialRepository {
    private final AtomicInteger claimCalls = new AtomicInteger();
    private final AtomicBoolean throwOnClaim = new AtomicBoolean(false);

    @Override
    public synchronized List<PluginCredentialRow> claimDue(
        List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
      claimCalls.incrementAndGet();
      if (throwOnClaim.get()) {
        throw new RuntimeException("transient repository failure during claimDue");
      }
      return super.claimDue(pluginIds, now, leaseUntil, leaseToken, limit);
    }
  }
}
