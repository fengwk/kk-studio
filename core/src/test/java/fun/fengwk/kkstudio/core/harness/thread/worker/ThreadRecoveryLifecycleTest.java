package fun.fengwk.kkstudio.core.harness.thread.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/** 低频恢复仅 kick 可恢复 Thread，workersEnabled=false 时不扫描。 */
class ThreadRecoveryLifecycleTest {

  @Test
  void scanOnceKicksRecoverableThreadsWhenWorkersEnabled() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(true);
    HarnessThreadMapper mapper = mock(HarnessThreadMapper.class);
    when(mapper.listRecoverableThreadIds(any(LocalDateTime.class), eq(100)))
        .thenReturn(List.of(11L, 12L));
    AtomicInteger kicks = new AtomicInteger();
    ThreadKick kick = threadId -> kicks.addAndGet((int) threadId);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadRecoveryLifecycle lifecycle =
          new ThreadRecoveryLifecycle(
              properties, mapper, kick, scheduler, Duration.ofSeconds(30), 100);
      assertEquals(2, lifecycle.scanOnce());
      assertEquals(23, kicks.get());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void scanOnceIsNoOpWhenWorkersDisabled() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(false);
    HarnessThreadMapper mapper = mock(HarnessThreadMapper.class);
    ThreadKick kick = mock(ThreadKick.class);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadRecoveryLifecycle lifecycle =
          new ThreadRecoveryLifecycle(
              properties, mapper, kick, scheduler, Duration.ofSeconds(30), 100);
      lifecycle.start();
      assertFalse(lifecycle.isRunning(), "workers-disabled must not leave running=true");
      assertEquals(0, lifecycle.scanOnce());
      verify(mapper, never()).listRecoverableThreadIds(any(), anyInt());
      verify(kick, never()).kick(anyLong());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void startSchedulesImmediateScanWhenEnabled() throws Exception {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setWorkersEnabled(true);
    HarnessThreadMapper mapper = mock(HarnessThreadMapper.class);
    when(mapper.listRecoverableThreadIds(any(LocalDateTime.class), anyInt()))
        .thenReturn(List.of(7L));
    AtomicInteger kicks = new AtomicInteger();
    ThreadKick kick = threadId -> kicks.incrementAndGet();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadRecoveryLifecycle lifecycle =
          new ThreadRecoveryLifecycle(properties, mapper, kick, scheduler, Duration.ofHours(1), 50);
      lifecycle.start();
      assertTrue(lifecycle.isRunning());
      for (int i = 0; i < 50 && kicks.get() == 0; i++) {
        Thread.sleep(20);
      }
      assertTrue(kicks.get() >= 1, "startup scan must kick");
      lifecycle.stop();
    } finally {
      scheduler.shutdownNow();
    }
  }
}
