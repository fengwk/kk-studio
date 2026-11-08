package fun.fengwk.kkstudio.platform.daemon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 配置同步单飞状态机的确定性回归：dirty 再读、拒绝保留与旧 worker 不得影响新 state。 */
class ConfigSyncCoordinatorTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  @Test
  void scheduleAndFinishConsumeDirtyInsideOneAtomicBoundary() {
    ConfigSyncCoordinator coordinator = new ConfigSyncCoordinator();

    ConfigSyncCoordinator.State first = coordinator.schedule(ENVIRONMENT_ID);
    assertNotNull(first);
    assertEquals(ConfigSyncCoordinator.Phase.SCHEDULED, first.phase());
    assertTrue(first.dirty());
    assertNull(coordinator.schedule(ENVIRONMENT_ID), "SCHEDULED state must single-flight");
    assertEquals(1, coordinator.size());

    assertTrue(coordinator.begin(ENVIRONMENT_ID, first));
    assertEquals(ConfigSyncCoordinator.Phase.RUNNING, first.phase());
    assertFalse(first.dirty());
    assertNull(coordinator.schedule(ENVIRONMENT_ID), "RUNNING state must single-flight");
    assertTrue(first.dirty(), "notification during query must be retained");

    assertTrue(coordinator.repeat(ENVIRONMENT_ID, first));
    assertFalse(first.dirty());
    assertTrue(coordinator.begin(ENVIRONMENT_ID, first));
    assertFalse(coordinator.repeat(ENVIRONMENT_ID, first));
    assertEquals(0, coordinator.size());
  }

  @Test
  void staleWorkerCannotRunOrRemoveItsReplacementState() {
    ConfigSyncCoordinator coordinator = new ConfigSyncCoordinator();
    ConfigSyncCoordinator.State first = coordinator.schedule(ENVIRONMENT_ID);
    assertTrue(coordinator.begin(ENVIRONMENT_ID, first));
    assertFalse(coordinator.repeat(ENVIRONMENT_ID, first));

    ConfigSyncCoordinator.State replacement = coordinator.schedule(ENVIRONMENT_ID);
    assertNotNull(replacement);
    assertNotSame(first, replacement);
    assertEquals(1, coordinator.size());

    assertFalse(coordinator.begin(ENVIRONMENT_ID, first));
    assertFalse(coordinator.repeat(ENVIRONMENT_ID, first));
    assertEquals(1, coordinator.size(), "stale worker must not remove the replacement state");
    assertTrue(coordinator.begin(ENVIRONMENT_ID, replacement));
    assertFalse(coordinator.repeat(ENVIRONMENT_ID, replacement));
    assertEquals(0, coordinator.size());
  }

  @Test
  void rejectedSubmissionKeepsDirtyForTheNextNotification() {
    ConfigSyncCoordinator coordinator = new ConfigSyncCoordinator();
    ConfigSyncCoordinator.State state = coordinator.schedule(ENVIRONMENT_ID);
    coordinator.rejected(ENVIRONMENT_ID, state);
    assertEquals(ConfigSyncCoordinator.Phase.IDLE, state.phase());
    assertTrue(state.dirty());
    assertEquals(1, coordinator.size());

    assertSame(state, coordinator.schedule(ENVIRONMENT_ID));
    assertEquals(ConfigSyncCoordinator.Phase.SCHEDULED, state.phase());
    assertTrue(coordinator.begin(ENVIRONMENT_ID, state));
    assertFalse(coordinator.repeat(ENVIRONMENT_ID, state));
    assertEquals(0, coordinator.size());
  }

  @Test
  void concurrentSchedulingReturnsOnlyOneSubmittedState() throws Exception {
    ConfigSyncCoordinator coordinator = new ConfigSyncCoordinator();
    int workers = 32;
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      CyclicBarrier barrier = new CyclicBarrier(workers);
      List<Future<ConfigSyncCoordinator.State>> results = new ArrayList<>();
      for (int i = 0; i < workers; i++) {
        results.add(
            executor.submit(
                () -> {
                  barrier.await();
                  return coordinator.schedule(ENVIRONMENT_ID);
                }));
      }
      int submitted = 0;
      for (Future<ConfigSyncCoordinator.State> result : results) {
        if (result.get() != null) {
          submitted++;
        }
      }
      assertEquals(1, submitted);
      assertEquals(1, coordinator.size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentFinishAndScheduleNeverOverlapStates() throws Exception {
    int workers = 2;
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    try {
      for (int iteration = 0; iteration < 200; iteration++) {
        ConfigSyncCoordinator coordinator = new ConfigSyncCoordinator();
        ConfigSyncCoordinator.State running = coordinator.schedule(ENVIRONMENT_ID);
        assertTrue(coordinator.begin(ENVIRONMENT_ID, running));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Boolean> finished =
            executor.submit(
                () -> {
                  barrier.await();
                  return coordinator.repeat(ENVIRONMENT_ID, running);
                });
        Future<ConfigSyncCoordinator.State> scheduled =
            executor.submit(
                () -> {
                  barrier.await();
                  return coordinator.schedule(ENVIRONMENT_ID);
                });

        boolean repeated = finished.get();
        ConfigSyncCoordinator.State replacement = scheduled.get();
        assertTrue(
            repeated ^ (replacement != null),
            "finish and schedule must either retain dirty or hand off to a new state");
        assertEquals(1, coordinator.size());
        if (replacement != null) {
          assertNotSame(running, replacement);
          assertEquals(ConfigSyncCoordinator.Phase.SCHEDULED, replacement.phase());
        } else {
          assertEquals(ConfigSyncCoordinator.Phase.RUNNING, running.phase());
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
