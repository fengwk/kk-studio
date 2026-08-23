package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** ApplicationReady 恢复 durable RUNNING，且 dispatcher 对同 node/request 进程内去重。 */
class CanvasFunctionRecoveryTest {

  private static final UUID NODE_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID NODE_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID REQ_1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
  private static final UUID REQ_2 = UUID.fromString("00000000-0000-0000-0000-000000000012");

  @Test
  void recoversAndDeduplicatesRunningWork() throws Exception {
    CanvasFunctionRunRepository repository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            NODE_1,
            REQ_1,
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
        .run(NODE_1, REQ_1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CanvasFunctionDispatcher dispatcher = new CanvasFunctionDispatcher(executor, worker);
      CanvasFunctionRecovery recovery =
          new CanvasFunctionRecovery(
              repository, mock(CanvasFunctionRunTransactions.class), dispatcher);
      recovery.recover();
      dispatcher.dispatch(NODE_1, REQ_1);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(dispatcher.isDispatched(NODE_1, REQ_1));
      release.countDown();
      verify(worker, timeout(5000).times(1)).run(NODE_1, REQ_1);
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
      CanvasFunctionRecovery recovery =
          new CanvasFunctionRecovery(
              repository, mock(CanvasFunctionRunTransactions.class), dispatcher);
      assertDoesNotThrow(recovery::recover);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void failsRecoveredRunWhenExecutorRejectsDispatch() {
    CanvasFunctionRunRepository repository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);
    CanvasFunctionDispatcher dispatcher = mock(CanvasFunctionDispatcher.class);
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            NODE_1,
            REQ_1,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH);
    when(repository.findRunning()).thenReturn(List.of(run));
    when(dispatcher.dispatch(NODE_1, REQ_1)).thenReturn(false);

    new CanvasFunctionRecovery(repository, transactions, dispatcher).recover();

    verify(transactions)
        .failIfRunning(NODE_1, REQ_1.toString(), "Function execution could not be scheduled");
  }

  @Test
  void continuesRecoveringAfterOneRunFailsUnexpectedly() {
    CanvasFunctionRunRepository repository = mock(CanvasFunctionRunRepository.class);
    CanvasFunctionDispatcher dispatcher = mock(CanvasFunctionDispatcher.class);
    CanvasFunctionRun first =
        new CanvasFunctionRun(
            NODE_1,
            REQ_1,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH);
    CanvasFunctionRun second =
        new CanvasFunctionRun(
            NODE_2,
            REQ_2,
            CanvasFunctionRunStatus.RUNNING,
            "QUEUED",
            "{\"stage\":\"QUEUED\"}",
            null,
            Instant.EPOCH);
    when(repository.findRunning()).thenReturn(List.of(first, second));
    doThrow(new IllegalStateException("unexpected")).when(dispatcher).dispatch(NODE_1, REQ_1);
    when(dispatcher.dispatch(NODE_2, REQ_2)).thenReturn(true);

    new CanvasFunctionRecovery(repository, mock(CanvasFunctionRunTransactions.class), dispatcher)
        .recover();

    verify(dispatcher).dispatch(NODE_2, REQ_2);
  }
}
