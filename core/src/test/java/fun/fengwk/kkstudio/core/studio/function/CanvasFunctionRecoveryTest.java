package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** ApplicationReady 恢复 durable RUNNING，且 dispatcher 对同 node/request 进程内去重。 */
class CanvasFunctionRecoveryTest {

  @Test
  void recoversAndDeduplicatesRunningWork() throws Exception {
    CanvasFunctionRunRepository repository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            1L,
            "request",
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH);
    when(repository.findRunning()).thenReturn(List.of(run));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(worker)
        .run(1L, "request");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CanvasFunctionDispatcher dispatcher = new CanvasFunctionDispatcher(executor, worker);
      CanvasFunctionRecovery recovery = new CanvasFunctionRecovery(repository, dispatcher);
      recovery.recover();
      dispatcher.dispatch(1L, "request");
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(dispatcher.isDispatched(1L, "request"));
      release.countDown();
      verify(worker, timeout(5000).times(1)).run(1L, "request");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void doesNotAbortApplicationReadyWhenRecoveryStorageIsUnavailable() {
    CanvasFunctionRunRepository repository = mock(CanvasFunctionRunRepository.class);
    when(repository.findRunning()).thenThrow(new IllegalStateException("schema unavailable"));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CanvasFunctionDispatcher dispatcher =
          new CanvasFunctionDispatcher(executor, mock(CanvasFunctionWorker.class));
      CanvasFunctionRecovery recovery = new CanvasFunctionRecovery(repository, dispatcher);
      assertDoesNotThrow(recovery::recover);
    } finally {
      executor.shutdownNow();
    }
  }
}
