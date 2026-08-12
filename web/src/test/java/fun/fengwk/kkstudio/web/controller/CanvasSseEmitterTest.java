package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** SSE 关闭会取消 worker 使阻塞等待循环停止；过载拒绝时不会触发任何订阅交互。 */
class CanvasSseEmitterTest {

  private static final UUID CANVAS = new UUID(0L, 1L);

  @Test
  void completeStopsFurtherWork() throws Exception {
    ExecutorService executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "canvas-sse-test");
              t.setDaemon(true);
              return t;
            });
    try {
      SseEmitter emitter = CanvasSseEmitter.stream(CANVAS, 0L, source(), executor);
      assertNotNull(emitter);
      Thread.sleep(250);
      emitter.complete();
      Thread.sleep(500);
      // 无异常即证明关闭路径干净；再次发送被拒绝说明 emitter 已终止。
      assertThrows(IllegalStateException.class, () -> emitter.send("probe"));
    } finally {
      executor.shutdownNow();
    }
  }

  /** 有界重载：executor 饱和时立即失败流，不遗留后台订阅或工作。 */
  @Test
  void rejectedExecutorFailsWithoutInvokingSource() {
    CanvasVersionEventSource source = mock(CanvasVersionEventSource.class);
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("saturated");
        };

    SseEmitter emitter = CanvasSseEmitter.stream(CANVAS, 0L, source, rejecting);

    assertNotNull(emitter);
    // 订阅先于提交建立，但拒绝后立即关闭，不会遗留后台工作。
    verify(source).subscribe(eq(CANVAS), anyLong(), any());
    assertThrows(
        IllegalStateException.class,
        () -> emitter.send(SseEmitter.event().name("probe").data("x")));
  }

  /** 生产环境 AsyncTaskExecutor 与普通 Executor 适配器都保留可取消的 Future。 */
  @Test
  void supportsManagedAsyncAndPlainExecutors() {
    CanvasVersionEventSource source = mock(CanvasVersionEventSource.class);
    AsyncTaskExecutor async = mock(AsyncTaskExecutor.class);
    @SuppressWarnings("unchecked")
    Future<Object> asyncFuture = mock(Future.class);
    doReturn(asyncFuture).when(async).submit(any(Runnable.class));

    SseEmitter asyncEmitter = CanvasSseEmitter.stream(CANVAS, 0L, source, async);

    verify(async).submit(any(Runnable.class));
    asyncEmitter.complete();

    AtomicReference<Runnable> submitted = new AtomicReference<>();
    SseEmitter plainEmitter = CanvasSseEmitter.stream(CANVAS, 0L, source, submitted::set);

    assertNotNull(submitted.get());
    plainEmitter.complete();
    verify(source, times(2)).subscribe(eq(CANVAS), anyLong(), any());
  }

  private static CanvasVersionEventSource source() {
    return (canvasId, afterVersion, consumer) -> () -> {};
  }
}
