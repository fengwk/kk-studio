package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 有界 executor 拒绝任务时必须移除进程内 dedup 标记，允许 durable run 后续重派发。 */
class CanvasFunctionDispatcherTest {

  @Test
  void clearsDedupMarkerWhenExecutorRejects() {
    ExecutorService executor = mock(ExecutorService.class);
    CanvasFunctionWorker worker = mock(CanvasFunctionWorker.class);
    doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
    CanvasFunctionDispatcher dispatcher = new CanvasFunctionDispatcher(executor, worker);

    dispatcher.dispatch(1L, "request");

    assertFalse(dispatcher.isDispatched(1L, "request"));
    verify(executor).execute(any());
  }
}
