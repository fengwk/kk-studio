package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** SSE 关闭会取消 tail worker 以便阻塞读取循环停止；过载拒绝时不会触发任何工作。 */
class StudioHarnessThreadSseEmitterTest {

  private static final UUID THREAD_ID = new UUID(0L, 1L);

  @Test
  void completeStopsFurtherTailReads() throws Exception {
    RealtimeEventTail tail = mock(RealtimeEventTail.class);
    AtomicInteger calls = new AtomicInteger();
    when(tail.readAfter(any(), anyString(), anyInt(), any(Duration.class)))
        .thenAnswer(
            inv -> {
              calls.incrementAndGet();
              Thread.sleep(50);
              return List.of(
                  new RealtimeEventTail.Record(
                      "1-0",
                      "{\"threadId\":\"1\",\"subjectKind\":\"MODEL_INVOCATION\",\"subjectId\":\"2\",\"attempt\":1,\"type\":\"MODEL_DELTA\",\"payload\":{\"kind\":\"TEXT_DELTA\",\"text\":\"x\"},\"createdAt\":\"2026-01-01T00:00:00Z\"}"));
            });
    ExecutorService executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "thread-realtime-sse-test");
              t.setDaemon(true);
              return t;
            });
    try {
      SseEmitter emitter =
          StudioHarnessThreadSseEmitter.stream(THREAD_ID, 0L, "0-0", tail, source(), executor);
      assertNotNull(emitter);
      Thread.sleep(250);
      emitter.complete();
      int afterClose = calls.get();
      Thread.sleep(500);
      assertTrue(calls.get() <= afterClose + 1);
      verify(tail, atLeastOnce()).readAfter(any(), anyString(), anyInt(), any(Duration.class));
    } finally {
      executor.shutdownNow();
    }
  }

  /** 有界重载：当 executor 饱和时立即失败流，既不会启动 tail 轮询，也不会遗留稍后调用 tail 的后台工作。 */
  @Test
  void rejectedExecutorFailsWithoutInvokingTail() throws Exception {
    RealtimeEventTail tail = mock(RealtimeEventTail.class);
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("saturated");
        };

    SseEmitter emitter =
        StudioHarnessThreadSseEmitter.stream(THREAD_ID, 0L, "0-0", tail, source(), rejecting);

    assertNotNull(emitter);
    verifyNoInteractions(tail);
    // 已失败的 emitter 拒绝后续发送；证明流以错误结束。
    assertThrows(
        IllegalStateException.class,
        () -> emitter.send(SseEmitter.event().name("probe").data("x")));
    Thread.sleep(200);
    verifyNoInteractions(tail);
  }

  /** 生产环境 AsyncTaskExecutor 与普通 Executor 适配器都保留可取消的 Future。 */
  @Test
  void supportsManagedAsyncAndPlainExecutorsWithoutInlinePolling() {
    RealtimeEventTail tail = mock(RealtimeEventTail.class);
    AsyncTaskExecutor async = mock(AsyncTaskExecutor.class);
    @SuppressWarnings("unchecked")
    Future<Object> asyncFuture = mock(Future.class);
    doReturn(asyncFuture).when(async).submit(any(Runnable.class));

    SseEmitter asyncEmitter =
        StudioHarnessThreadSseEmitter.stream(THREAD_ID, 0L, "0-0", tail, source(), async);

    verify(async).submit(any(Runnable.class));
    asyncEmitter.complete();

    AtomicReference<Runnable> submitted = new AtomicReference<>();
    SseEmitter plainEmitter =
        StudioHarnessThreadSseEmitter.stream(THREAD_ID, 0L, "0-0", tail, source(), submitted::set);

    assertNotNull(submitted.get());
    plainEmitter.complete();
    verifyNoInteractions(tail);
  }

  private static ThreadRevisionEventSource source() {
    return (threadId, afterRevision, consumer) -> () -> {};
  }

  /** runtime-spring tail 仅接受具体的 {@code ms-seq} 游标；瞬态的 "$" 会被拒绝。 */
  @Test
  void concreteCursorNormalizationRejectsTransientDollar() {
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId(null));
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId(""));
    assertEquals("0-0", RealtimeEventTail.normalizeAfterId("0"));
    assertEquals("123-456", RealtimeEventTail.normalizeAfterId("123-456"));
    assertThrows(IllegalArgumentException.class, () -> RealtimeEventTail.normalizeAfterId("$"));
  }
}
