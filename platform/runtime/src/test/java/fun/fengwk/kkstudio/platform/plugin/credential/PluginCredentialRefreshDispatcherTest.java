package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** {@link PluginCredentialRefreshDispatcher} 的到期调度、唤醒合并与生命周期契约。 */
class PluginCredentialRefreshDispatcherTest {

  private static final Duration WAIT = Duration.ofSeconds(5);

  private final PluginCredentialRefreshService service = mock(PluginCredentialRefreshService.class);
  private final PluginProperties properties = new PluginProperties();
  private final AtomicInteger scans = new AtomicInteger();
  private PluginCredentialRefreshDispatcher dispatcher;

  @AfterEach
  void tearDown() {
    if (dispatcher != null) {
      dispatcher.stop();
    }
  }

  /** start 立即扫描一次且幂等；stop 后不再扫描，wake 也不复活调度。 */
  @Test
  void startScansImmediatelyAndStopEndsScheduling() throws InterruptedException {
    countScans(0);
    dispatcher = dispatcher();

    dispatcher.start();
    dispatcher.start();
    await(() -> scans.get() == 1);
    assertTrue(dispatcher.isRunning());
    assertEquals(Integer.MAX_VALUE, dispatcher.getPhase());

    dispatcher.stop();
    dispatcher.wake();
    Thread.sleep(100);
    assertFalse(dispatcher.isRunning());
    assertEquals(1, scans.get());
  }

  /** 未启动时 wake 是空操作；运行中 wake 立即补一次扫描。 */
  @Test
  void wakeTriggersAScanOnlyWhileRunning() {
    countScans(0);
    dispatcher = dispatcher();
    dispatcher.wake();
    assertEquals(0, scans.get());

    dispatcher.start();
    await(() -> scans.get() == 1);
    dispatcher.wake();
    await(() -> scans.get() == 2);
  }

  /** 最早到期时刻早于兜底间隔时按到期时刻排期。 */
  @Test
  void schedulesAtTheEarliestDueTime() {
    countScans(0);
    when(service.earliestRefreshAt())
        .thenAnswer(invocation -> Optional.of(Instant.now().plusMillis(50)));
    dispatcher = dispatcher();

    dispatcher.start();
    await(() -> scans.get() >= 3);
  }

  /** 已到期但本轮没有推进时退回兜底间隔；有推进时立即继续排空。 */
  @Test
  void dueRowsRescanImmediatelyOnlyWhileProgressing() throws InterruptedException {
    AtomicInteger finalized = new AtomicInteger(2);
    when(service.refreshOnce())
        .thenAnswer(
            invocation -> {
              scans.incrementAndGet();
              return Math.max(0, finalized.getAndDecrement());
            });
    when(service.earliestRefreshAt()).thenReturn(Optional.of(Instant.now().minusSeconds(1)));
    dispatcher = dispatcher();

    dispatcher.start();
    await(() -> scans.get() == 3);
    Thread.sleep(200);
    assertEquals(3, scans.get());
  }

  /** 扫描或读取到期时刻失败都不终止调度，退回兜底间隔。 */
  @Test
  void failuresFallBackToThePollDelay() {
    properties.getRefresh().setPollDelay(Duration.ofMillis(50));
    when(service.refreshOnce())
        .thenAnswer(
            invocation -> {
              scans.incrementAndGet();
              throw new IllegalStateException("scan failed");
            });
    when(service.earliestRefreshAt()).thenThrow(new IllegalStateException("read failed"));
    dispatcher = dispatcher();

    dispatcher.start();
    await(() -> scans.get() >= 3);
  }

  /** 扫描在途时的重复唤醒合并为一次补扫，不丢失也不堆积。 */
  @Test
  void wakesDuringAScanAreCoalescedIntoOneRescan() throws InterruptedException {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(service.refreshOnce())
        .thenAnswer(
            invocation -> {
              if (scans.incrementAndGet() == 1) {
                entered.countDown();
                release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
              }
              return 0;
            });
    when(service.earliestRefreshAt()).thenReturn(Optional.empty());
    dispatcher = dispatcher();

    dispatcher.start();
    assertTrue(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
    dispatcher.wake();
    dispatcher.wake();
    dispatcher.wake();
    release.countDown();
    await(() -> scans.get() == 2);
    Thread.sleep(200);
    assertEquals(2, scans.get());
  }

  private void countScans(int finalized) {
    when(service.refreshOnce())
        .thenAnswer(
            invocation -> {
              scans.incrementAndGet();
              return finalized;
            });
    when(service.earliestRefreshAt()).thenReturn(Optional.empty());
  }

  private PluginCredentialRefreshDispatcher dispatcher() {
    return new PluginCredentialRefreshDispatcher(service, properties, Clock.systemUTC());
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition was not met within " + WAIT);
      }
      Thread.onSpinWait();
    }
  }
}
