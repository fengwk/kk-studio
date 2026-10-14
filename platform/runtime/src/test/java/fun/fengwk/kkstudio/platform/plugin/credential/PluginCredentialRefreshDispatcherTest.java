package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;

import javax.crypto.SecretKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Plugin 凭据刷新调度器测试。
 *
 * <p>核心契约：启动立即扫描；按数据库最早 {@code next_refresh_at} 安排下一次单次调度，{@code pollDelay} 只作最慢兜底；wake
 * 触发即时扫描但进行中的重复唤醒被合并；stop 之后调度绝不复活（包括与 wake 并发竞争）；单次扫描异常不终止后续调度。
 */
class PluginCredentialRefreshDispatcherTest {

  private static final String PLUGIN_ID = "dispatcher-plugin";

  private static final String SECOND_PLUGIN_ID = "dispatcher-plugin-2";
  private static final String REGION = "CN";

  @TempDir Path tempDir;

  private final Instant now = Instant.parse("2026-09-21T16:00:00Z");

  private MutableClock clock;
  private CountingRepository repository;
  private PluginProperties properties;
  private PluginCredentialRefreshDispatcher dispatcher;
  private PluginCredentialCodec codec;
  private PluginCredentialKeyLoader keyLoader;

  @BeforeEach
  void setUp() throws IOException {
    clock = new MutableClock(now);
    repository = new CountingRepository();
    codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("dispatcher-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());

    properties = new PluginProperties();
    properties.getRefresh().setPollDelay(Duration.ofMillis(50));
    properties.getRefresh().setLeaseDuration(Duration.ofMinutes(2));
  }

  @AfterEach
  void tearDown() {
    if (dispatcher != null && dispatcher.isRunning()) {
      dispatcher.stop();
    }
  }

  /** start() 后立即执行一次扫描，随后按兜底间隔执行；stop() 后不再有新的扫描执行。 */
  @Test
  void startsImmediatelyAndStopsScheduling() throws InterruptedException {
    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    assertFalse(dispatcher.isRunning());

    dispatcher.start();
    assertTrue(dispatcher.isRunning());

    // 无到期行时退化为 pollDelay 兜底：至少 2 次扫描（首次 0ms 立即执行 + 50ms）
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());

    int countAtStop = repository.claimCalls.get();
    Thread.sleep(300);
    assertEquals(countAtStop, repository.claimCalls.get(), "no scans should occur after stop()");
  }

  /** start() 幂等：重复调用 start 不会创建重复调度线程或增加并发执行。 */
  @Test
  void startIsIdempotent() throws InterruptedException {
    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();
    dispatcher.start(); // 重复调用

    assertTrue(dispatcher.isRunning());
    awaitCondition(() -> repository.claimCalls.get() >= 1, 2000);

    dispatcher.stop();
    assertFalse(dispatcher.isRunning());
  }

  /** 扫描相位排在最后，保证数据库与依赖 bean 就绪后才第一次扫描。 */
  @Test
  void lifecycleAndPhaseContract() {
    dispatcher = dispatcher(snapshot -> refreshedMaterial());
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

    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();

    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    repository.throwOnClaim.set(false);
    int countBeforeRecovery = repository.claimCalls.get();

    awaitCondition(() -> repository.claimCalls.get() > countBeforeRecovery, 2000);

    dispatcher.stop();
  }

  /** wake() 在未启动时不抛异常且不触发扫描；启动后能主动触发一次扫描；stop 后不再触发。 */
  @Test
  void wakeTriggersScanWhenRunningAndIgnoredWhenStopped() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    dispatcher = dispatcher(snapshot -> refreshedMaterial());

    dispatcher.wake();
    assertEquals(0, repository.claimCalls.get(), "wake before start must be a no-op");

    dispatcher.start();
    awaitCondition(() -> repository.claimCalls.get() == 1, 2000);

    dispatcher.wake();
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2000);

    dispatcher.stop();

    int countAtStop = repository.claimCalls.get();
    dispatcher.wake();
    Thread.sleep(200);
    assertEquals(
        countAtStop, repository.claimCalls.get(), "wake after stop must not schedule work");
  }

  /**
   * 到期调度必须准确：行在 {@code pollDelay} 之前就到期时，下一次扫描按该到期时刻安排，而不是等到兜底轮询。这是「刚扫描完就登录 得到的短寿命
   * token」不再被固定间隔漏掉的核心证据。
   */
  @Test
  void schedulesAtEarliestDueInsteadOfWaitingForPollDelay() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    repository.setDirect(
        row(PluginCredentialStatus.CONNECTED, now.plusSeconds(7200), now.plusMillis(500)));
    dispatcher = dispatcher(snapshot -> refreshedMaterial());

    dispatcher.start();

    // 初始扫描尚未到期（0 次 claim），但必须按 500ms 的到期时刻排下一次扫描。
    Thread.sleep(150);
    assertEquals(1, repository.claimCalls.get(), "only the immediate startup scan has run so far");

    // 让该行真正到期；若调度按 pollDelay 而非到期时刻，这次扫描就不会在 2 秒内发生。
    clock.advance(Duration.ofMinutes(10));
    awaitCondition(() -> refreshedBy(PLUGIN_ID), 2_000);
    assertTrue(
        repository.claimCalls.get() >= 2,
        "the due-time scan must have claimed the row once it became due");

    PluginCredentialRow refreshed = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, refreshed.status());
    assertEquals(
        clock.instant().plusSeconds(3600),
        refreshed.nextRefreshAt(),
        "the due-time scan must actually refresh the row");

    dispatcher.stop();
  }

  /** 并发 wake 与 stop 竞争：stop 之后绝不复活调度，也不会因 wake 产生新的扫描。 */
  @Test
  void concurrentWakeAndStopNeverResurrectsScheduling() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofMillis(30));
    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();

    awaitCondition(() -> repository.claimCalls.get() >= 1, 2000);

    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean stopDone = new AtomicBoolean();
    Thread[] wakers = new Thread[4];
    for (int i = 0; i < wakers.length; i++) {
      wakers[i] =
          new Thread(
              () -> {
                awaitQuietly(start);
                while (!stopDone.get()) {
                  dispatcher.wake();
                }
              });
      wakers[i].start();
    }

    start.countDown();
    dispatcher.stop();
    stopDone.set(true);
    for (Thread waker : wakers) {
      waker.join(2_000);
    }
    assertFalse(dispatcher.isRunning());

    int countAtStop = repository.claimCalls.get();
    Thread.sleep(300);
    assertEquals(countAtStop, repository.claimCalls.get(), "no scan may be scheduled after stop()");
  }

  /**
   * 扫描进行中的重复 wake 被合并为一次补跑：既不会压入 5 个重复扫描，也绝不会被丢掉。
   *
   * <p>唤醒对应的凭据提交可能发生在本轮调度读取 {@code next_refresh_at} 之后，丢弃它会让刚登录的短寿命 token 一直等到兜底轮询，因此这里必须补跑 且只补跑一次。
   */
  @Test
  void duplicateWakesDuringRunningScanAreCoalescedIntoOneRescan() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    CountDownLatch scanEntered = new CountDownLatch(1);
    CountDownLatch releaseScan = new CountDownLatch(1);
    repository.onClaim =
        () -> {
          scanEntered.countDown();
          awaitQuietly(releaseScan);
        };

    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();

    assertTrue(scanEntered.await(2, TimeUnit.SECONDS), "the first scan must start");
    assertEquals(1, repository.claimCalls.get(), "only the running scan has claimed so far");

    for (int i = 0; i < 5; i++) {
      dispatcher.wake();
    }
    releaseScan.countDown();

    awaitCondition(() -> repository.claimCalls.get() >= 2, 2_000);
    Thread.sleep(200);
    assertEquals(
        2,
        repository.claimCalls.get(),
        "wakes arriving during a running scan must coalesce into exactly one rescan");

    dispatcher.stop();
  }

  /** 已经到期但本轮无法推进的行（例如主密钥不可用导致 lease 被释放）必须退回兜底轮询，而不是零延迟重扫形成热点循环。 */
  @Test
  void stalledDueRowBacksOffToPollDelay() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofMillis(200));
    // 该 Plugin 没有刷新器：claim 之后只能 releaseLease，行仍然到期。
    dispatcher = dispatcherWithoutRefresher();
    repository.setDirect(
        row(PluginCredentialStatus.CONNECTED, now.plusSeconds(7200), now.minusSeconds(10)));

    dispatcher.start();

    // 首次扫描释放 lease 后退避到 pollDelay：退避窗口内不得反复重扫。
    Thread.sleep(120);
    assertEquals(
        1,
        repository.claimCalls.get(),
        "a stalled due row must back off to pollDelay instead of rescanning immediately");

    // 兜底窗口到达后仍然会重试，避免永久放弃。
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2_000);

    dispatcher.stop();
  }

  /**
   * 调度读取与提交之间的窗口：唤醒绝不能被丢掉。
   *
   * <p>复现 F06 的漏刷场景：本轮扫描已经读完 {@code next_refresh_at}（读到的是提交前的旧状态），随后凭据才提交并触发 wake。
   * 此时扫描仍在途，唤醒必须被记为待处理并在本轮结束后补跑，否则刚提交、马上到期的凭据只能等到 {@code pollDelay} 兜底轮询。
   */
  @Test
  void wakeArrivingAfterSchedulingReadIsNotLost() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    CountDownLatch schedulingReadEntered = new CountDownLatch(1);
    CountDownLatch releaseSchedulingRead = new CountDownLatch(1);
    repository.onEarliestRefresh =
        () -> {
          schedulingReadEntered.countDown();
          awaitQuietly(releaseSchedulingRead);
        };
    // 起始行在 1 小时兜底之外到期：只有真正的唤醒才能让它更早被扫描。
    repository.setDirect(
        row(PluginCredentialStatus.CONNECTED, now.plusSeconds(7200), now.plusSeconds(7200)));

    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();

    assertTrue(
        schedulingReadEntered.await(2, TimeUnit.SECONDS),
        "the startup scan must reach the scheduling read");
    assertEquals(1, repository.claimCalls.get(), "the startup scan must not be repeated");

    // 提交新凭据并唤醒：调度读取已经发生，本轮扫描不可能再看到这次提交。
    repository.setDirect(
        row(PluginCredentialStatus.CONNECTED, now.plusSeconds(7200), now.plusSeconds(1)));
    clock.advance(Duration.ofSeconds(30));
    dispatcher.wake();
    releaseSchedulingRead.countDown();

    awaitCondition(() -> repository.schedulingReads.get() >= 2, 2_000);
    assertEquals(
        clock.instant().plusSeconds(3600),
        repository.getDirect(PLUGIN_ID).nextRefreshAt(),
        "the pending wake must deliver the committed credential to a refresh");
    Thread.sleep(200);
    assertEquals(
        2,
        repository.schedulingReads.get(),
        "the pending wake must be served by exactly one rescan");

    dispatcher.stop();
  }

  /** 积压排空：一轮只 claim 一行，只要刷新成功后仍有更早到期的行，就立即零延迟继续下一轮，而不是等到 {@code pollDelay}。 */
  @Test
  void drainsDueBacklogWithImmediateRescans() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofHours(1));
    repository.setDirect(
        row(
            PLUGIN_ID,
            PluginCredentialStatus.CONNECTED,
            now.plusSeconds(7200),
            now.minusSeconds(5)));
    repository.setDirect(
        row(
            SECOND_PLUGIN_ID,
            PluginCredentialStatus.CONNECTED,
            now.plusSeconds(7200),
            now.minusSeconds(4)));
    dispatcher =
        dispatcher(
            List.of(
                plugin(PLUGIN_ID, snapshot -> refreshedMaterial()),
                plugin(SECOND_PLUGIN_ID, snapshot -> refreshedMaterial())));

    dispatcher.start();

    awaitCondition(() -> refreshedBy(PLUGIN_ID) && refreshedBy(SECOND_PLUGIN_ID), 2_000);

    dispatcher.stop();
  }

  /** 读取最早到期时刻失败必须退化为 {@code pollDelay} 兜底，而不是放弃后续调度。 */
  @Test
  void schedulingReadFailureFallsBackToPollDelay() throws InterruptedException {
    properties.getRefresh().setPollDelay(Duration.ofMillis(400));
    repository.setDirect(
        row(PluginCredentialStatus.CONNECTED, now.plusSeconds(7200), now.plusMillis(50)));
    repository.throwOnEarliestRefresh.set(true);

    dispatcher = dispatcher(snapshot -> refreshedMaterial());
    dispatcher.start();

    // 若按 50ms 的到期时刻调度，这里已经出现第二轮扫描；读取失败必须退回 400ms 兜底。
    Thread.sleep(250);
    assertEquals(
        1,
        repository.claimCalls.get(),
        "a failed scheduling read must fall back to pollDelay instead of rescanning sooner");

    // 兜底窗口到达后仍然继续调度，绝不因为一次读取失败而停摆。
    awaitCondition(() -> repository.claimCalls.get() >= 2, 2_000);

    dispatcher.stop();
  }

  private boolean refreshedBy(String pluginId) {
    return clock.instant().plusSeconds(3600).equals(repository.getDirect(pluginId).nextRefreshAt());
  }

  private PluginCredentialRefreshDispatcher dispatcher(List<StudioPlugin> plugins) {
    PluginCredentialRefreshService refreshService =
        new PluginCredentialRefreshService(
            new StudioPluginRegistry(plugins), repository, codec, keyLoader, properties, clock);
    return new PluginCredentialRefreshDispatcher(refreshService, properties, clock);
  }

  /** 固定 region、带刷新器的插件装配。 */
  private static StudioPlugin plugin(String pluginId, PluginCredentialRefresher refresher) {
    PluginDescriptor descriptor =
        new PluginDescriptor(pluginId, "Dispatcher Plugin", "1.0", List.of(REGION));
    return new StudioPlugin() {
      @Override
      public PluginDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public Optional<PluginCredentialRefresher> refresher() {
        return Optional.of(refresher);
      }
    };
  }

  private PluginCredentialRefreshDispatcher dispatcher(PluginCredentialRefresher refresher) {
    return dispatcher(List.of(plugin(PLUGIN_ID, refresher)));
  }

  /** 没有刷新器的 Plugin：claim 后只能释放 lease，用于验证「无法推进时退避」。 */
  private PluginCredentialRefreshDispatcher dispatcherWithoutRefresher() {
    PluginDescriptor descriptor =
        new PluginDescriptor(PLUGIN_ID, "Dispatcher Plugin", "1.0", List.of(REGION));
    StudioPlugin plugin = () -> descriptor;
    PluginCredentialRefreshService refreshService =
        new PluginCredentialRefreshService(
            new StudioPluginRegistry(List.of(plugin)),
            repository,
            codec,
            keyLoader,
            properties,
            clock);
    return new PluginCredentialRefreshDispatcher(refreshService, properties, clock);
  }

  /** 成功刷新的新材料：到期时间与下一刷新时刻都相对当前时钟向前推进，使该行离开到期窗口。 */
  private PluginCredentialMaterial refreshedMaterial() {
    return new PluginCredentialMaterial(
        REGION, clock.instant().plusSeconds(7200), clock.instant().plusSeconds(3600), "{}");
  }

  private PluginCredentialRow row(
      PluginCredentialStatus status, Instant expiresAt, Instant nextRefreshAt) {
    return row(PLUGIN_ID, status, expiresAt, nextRefreshAt);
  }

  private PluginCredentialRow row(
      String pluginId, PluginCredentialStatus status, Instant expiresAt, Instant nextRefreshAt) {
    SecretKey key = keyLoader.load().orElseThrow();
    return new PluginCredentialRow(
        pluginId,
        codec.encrypt(key, pluginId, REGION, "{}"),
        REGION,
        expiresAt,
        nextRefreshAt,
        status,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  /** 记录 claim 次数、可注入 claim 异常与阻塞钩子的测试仓库。 */
  private static final class CountingRepository extends InMemoryPluginCredentialRepository {
    private final AtomicInteger claimCalls = new AtomicInteger();
    private final AtomicInteger schedulingReads = new AtomicInteger();
    private final AtomicBoolean throwOnClaim = new AtomicBoolean(false);
    private final AtomicBoolean throwOnEarliestRefresh = new AtomicBoolean(false);
    private volatile Runnable onClaim = () -> {};
    private volatile Runnable onEarliestRefresh = () -> {};

    @Override
    public synchronized List<PluginCredentialRow> claimDue(
        List<String> pluginIds, Instant now, Instant leaseUntil, String leaseToken, int limit) {
      claimCalls.incrementAndGet();
      onClaim.run();
      if (throwOnClaim.get()) {
        throw new RuntimeException("transient repository failure during claimDue");
      }
      return super.claimDue(pluginIds, now, leaseUntil, leaseToken, limit);
    }

    /** 每轮扫描结束时恰好读取一次最早到期时刻，因此本计数等价于已完成（含被丢弃）的扫描轮数。 */
    @Override
    public Optional<Instant> earliestRefreshAt(List<String> pluginIds, Instant now) {
      if (throwOnEarliestRefresh.get()) {
        throw new RuntimeException("transient repository failure during earliestRefreshAt");
      }
      Optional<Instant> earliest = super.earliestRefreshAt(pluginIds, now);
      if (schedulingReads.incrementAndGet() == 1) {
        // 钩子在真实读取之后进入：调用方已经拿到「提交之前」的旧快照，用于复现调度读取与提交之间的窗口。
        onEarliestRefresh.run();
      }
      return earliest;
    }
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

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for latch");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  /** 可推进的测试时钟：让数据库行在真实时间窗口内到期，从而验证到期调度而非固定间隔。 */
  private static final class MutableClock extends Clock {

    private volatile Instant current;
    private final ZoneId zone = ZoneOffset.UTC;

    MutableClock(Instant initial) {
      this.current = initial;
    }

    void advance(Duration duration) {
      this.current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
