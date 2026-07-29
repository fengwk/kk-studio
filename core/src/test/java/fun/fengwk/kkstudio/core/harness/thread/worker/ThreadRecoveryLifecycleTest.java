package fun.fengwk.kkstudio.core.harness.thread.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.reconcile.ThreadReconcileMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ThreadRecoveryLifecycleTest {

  @Test
  void scanOnceKicksOnlyValidRecoverableThreadsAndIsolatesRejection() {
    HarnessRuntimeProperties properties = enabledProperties();
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    when(mapper.listRecoverableThreadIds(any(OffsetDateTime.class), eq(100)))
        .thenReturn(Arrays.asList(11L, null, -1L, 12L));
    AtomicInteger kicks = new AtomicInteger();
    ThreadKick kick =
        threadId -> {
          if (threadId == 11L) {
            throw new RejectedExecutionException("full");
          }
          kicks.addAndGet((int) threadId);
        };
    ThreadRecoveryLifecycle lifecycle =
        lifecycle(properties, mapper, kick, mock(ScheduledExecutorService.class));

    assertEquals(1, lifecycle.scanOnce());
    assertEquals(12, kicks.get());
  }

  @Test
  void remainsStoppedAndDoesNotScanWhenWorkersDisabled() {
    HarnessRuntimeProperties properties = enabledProperties();
    properties.setWorkersEnabled(false);
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    ThreadKick kick = mock(ThreadKick.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ThreadRecoveryLifecycle lifecycle = lifecycle(properties, mapper, kick, scheduler);

    lifecycle.start();

    assertFalse(lifecycle.isAutoStartup());
    assertFalse(lifecycle.isRunning());
    assertEquals(0, lifecycle.scanOnce());
    verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    verify(mapper, never()).listRecoverableThreadIds(any(), anyInt());
    verify(kick, never()).kick(anyLong());
  }

  @Test
  void schedulesImmediateScanAndReportsLifecycleState() {
    HarnessRuntimeProperties properties = enabledProperties();
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    when(mapper.listRecoverableThreadIds(any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of(7L));
    ThreadKick kick = mock(ThreadKick.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future)
        .when(scheduler)
        .scheduleWithFixedDelay(any(), eq(0L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    ThreadRecoveryLifecycle lifecycle = lifecycle(properties, mapper, kick, scheduler);

    lifecycle.start();

    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler)
        .scheduleWithFixedDelay(callback.capture(), eq(0L), eq(1_000L), eq(TimeUnit.MILLISECONDS));
    assertTrue(lifecycle.isAutoStartup());
    assertTrue(lifecycle.isRunning());
    assertEquals(Integer.MAX_VALUE - 70, lifecycle.getPhase());
    callback.getValue().run();
    verify(kick).kick(7L);
  }

  @Test
  void isolatesScheduledScanFailureAndResetsAfterRejectedStart() {
    HarnessRuntimeProperties properties = enabledProperties();
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    when(mapper.listRecoverableThreadIds(any(), anyInt()))
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(List.of());
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ThreadRecoveryLifecycle lifecycle =
        lifecycle(properties, mapper, mock(ThreadKick.class), scheduler);
    lifecycle.start();
    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());

    assertDoesNotThrow(callback.getValue()::run);
    assertDoesNotThrow(callback.getValue()::run);
    verify(mapper, times(2)).listRecoverableThreadIds(any(), anyInt());

    ScheduledExecutorService rejectingScheduler = mock(ScheduledExecutorService.class);
    when(rejectingScheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenThrow(new RejectedExecutionException("stopped"));
    ThreadRecoveryLifecycle rejected =
        lifecycle(properties, mapper, mock(ThreadKick.class), rejectingScheduler);
    assertThrows(RejectedExecutionException.class, rejected::start);
    assertFalse(rejected.isRunning());
  }

  @Test
  void stopWaitsForActiveKickAndPreventsTheNextDispatch() {
    HarnessRuntimeProperties properties = enabledProperties();
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    when(mapper.listRecoverableThreadIds(any(), anyInt())).thenReturn(List.of(1L, 2L));
    CountDownLatch kickStarted = new CountDownLatch(1);
    CountDownLatch releaseKick = new CountDownLatch(1);
    ThreadKick kick = mock(ThreadKick.class);
    doAnswer(
            ignored -> {
              kickStarted.countDown();
              await(releaseKick);
              return null;
            })
        .when(kick)
        .kick(1L);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ThreadRecoveryLifecycle lifecycle = lifecycle(properties, mapper, kick, scheduler);
    lifecycle.start();
    ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());
    Thread scanThread = new Thread(callback.getValue(), "thread-recovery-scan-test");
    scanThread.start();
    await(kickStarted);
    Thread stopThread = new Thread(lifecycle::stop, "thread-recovery-stop-test");
    stopThread.start();
    awaitStopped(lifecycle);

    releaseKick.countDown();
    join(scanThread);
    join(stopThread);

    verify(kick).kick(1L);
    verify(kick, never()).kick(2L);
    verify(future).cancel(false);
  }

  @Test
  void stopCancelsFutureAndAlwaysInvokesCallback() {
    HarnessRuntimeProperties properties = enabledProperties();
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> future = scheduledFuture();
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ThreadRecoveryLifecycle lifecycle =
        lifecycle(properties, mock(ThreadReconcileMapper.class), mock(ThreadKick.class), scheduler);
    lifecycle.start();
    AtomicBoolean called = new AtomicBoolean();

    lifecycle.stop(() -> called.set(true));

    InOrder order = inOrder(future);
    order.verify(future).cancel(false);
    assertTrue(called.get());
    assertFalse(lifecycle.isRunning());
  }

  @Test
  void validatesCadenceAndReportsStoppedWhenFutureCompletes() {
    HarnessRuntimeProperties properties = enabledProperties();
    ThreadReconcileMapper mapper = mock(ThreadReconcileMapper.class);
    ThreadKick kick = mock(ThreadKick.class);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadRecoveryLifecycle(
                properties, mapper, kick, scheduler, Duration.ofNanos(999_999), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ThreadRecoveryLifecycle(
                properties, mapper, kick, scheduler, Duration.ofMillis(1), 0));

    ScheduledFuture<?> future = scheduledFuture();
    when(future.isDone()).thenReturn(true);
    doReturn(future).when(scheduler).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    ThreadRecoveryLifecycle lifecycle = lifecycle(properties, mapper, kick, scheduler);
    lifecycle.start();
    assertFalse(lifecycle.isRunning());
  }

  private static HarnessRuntimeProperties enabledProperties() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(true);
    return properties;
  }

  private static ThreadRecoveryLifecycle lifecycle(
      HarnessRuntimeProperties properties,
      ThreadReconcileMapper mapper,
      ThreadKick kick,
      ScheduledExecutorService scheduler) {
    return new ThreadRecoveryLifecycle(
        properties, mapper, kick, scheduler, Duration.ofSeconds(1), 100);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(2, TimeUnit.SECONDS), "timed out waiting for test synchronization");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail("interrupted while waiting for test synchronization", error);
    }
  }

  private static void awaitStopped(ThreadRecoveryLifecycle lifecycle) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (lifecycle.isRunning()) {
      if (System.nanoTime() >= deadline) {
        fail("timed out waiting for lifecycle stop request");
      }
      Thread.onSpinWait();
    }
  }

  private static void join(Thread thread) {
    try {
      thread.join(TimeUnit.SECONDS.toMillis(2));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      fail("interrupted while joining test thread", error);
    }
    assertFalse(thread.isAlive(), "test thread did not finish");
  }

  @SuppressWarnings("unchecked")
  private static ScheduledFuture<?> scheduledFuture() {
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    when(future.isCancelled()).thenReturn(false);
    when(future.isDone()).thenReturn(false);
    return future;
  }
}
