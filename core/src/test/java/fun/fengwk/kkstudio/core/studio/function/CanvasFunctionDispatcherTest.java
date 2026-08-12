package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 有界 executor 拒绝任务时必须移除进程内 dedup 标记，允许 durable run 后续重派发。 */
class CanvasFunctionDispatcherTest {

  private static final UUID NODE = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();

  @Test
  void clearsDedupMarkerWhenExecutorRejects() {
    ExecutorService executor = mock(ExecutorService.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
    CanvasFunctionDispatcher dispatcher = new CanvasFunctionDispatcher(executor, worker);

    assertFalse(dispatcher.dispatch(NODE, REQUEST));
    assertFalse(dispatcher.dispatch(NODE, REQUEST));

    assertFalse(dispatcher.isDispatched(NODE, REQUEST));
    verify(executor, times(2)).execute(any());
  }

  @Test
  void treatsDuplicateInFlightDispatchAsAcceptedWithoutSubmittingTwice() {
    ExecutorService executor = mock(ExecutorService.class);
    CanvasFunctionDispatcher dispatcher =
        new CanvasFunctionDispatcher(executor, mock(CanvasFunctionWorker.class));

    assertTrue(dispatcher.dispatch(NODE, REQUEST));
    assertTrue(dispatcher.dispatch(NODE, REQUEST));

    assertTrue(dispatcher.isDispatched(NODE, REQUEST));
    verify(executor).execute(any());
  }
}
